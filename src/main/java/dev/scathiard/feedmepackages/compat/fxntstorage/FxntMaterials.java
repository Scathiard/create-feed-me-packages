package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.*;
import java.util.function.Consumer;

/** Call-local sources for fxntstorage 1.3.4. No handler registration or backpack cache mutation. */
public final class FxntMaterials {
    private FxntMaterials() {}
    private static int batch(long available, int limit) { return (int) Math.max(0, Math.min(available, limit)); }
    public record Offer(int cell, ItemStack stack) {
        public Offer { stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    private static List<Offer> available(ServerPlayer player, CacheRecord record, List<Ingredient> wanted) {
        var result = new ArrayList<Offer>();
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot);
            if (cell.filter() == null || cell.amount() <= 0) continue;
            try {
                var stack = cell.filter().stack(player.registryAccess(), 1);
                int free = batch(cell.amount() - CraftingReservations.reservedCache(record.state().id(), slot, null),
                        sourceLimit(stack, wanted));
                addOffers(result, slot, stack, free);
            } catch (IllegalArgumentException unreadable) {
                FeedMePackages.LOGGER.debug("FMP skipped unreadable fxnt source cell {}", slot);
            }
        }
        return result;
    }
    private static int sourceLimit(ItemStack stack, List<Ingredient> wanted) {
        // One legal stack per matching recipe input, not one stack for an entire repeated recipe.
        int inputs = (int) wanted.stream().filter(ingredient -> ingredient.test(stack)).count();
        return Math.min(inputs, 9) * Math.min(stack.getMaxStackSize(), 64);
    }
    private static void addOffers(List<Offer> offers, int cell, ItemStack stack, int count) {
        while (count > 0) {
            int batch = Math.min(count, stack.getMaxStackSize());
            offers.add(new Offer(cell, stack.copyWithCount(batch))); count -= batch;
        }
    }
    public static Inventory countView(Inventory original, List<Ingredient> wanted) {
        if (!FmpConfig.fxntMaterialsEnabled() || !FxntTransferContext.cacheAllowed() || !(original.player instanceof ServerPlayer player) || wanted.isEmpty()) return original;
        var access = AccessGate.resolve(player);
        if (!access.active() || CraftingReservations.operating(access.handle().cacheId())) return original;
        return new View(original, available(player, CacheLedger.get(player.getServer()).find(access.handle().cacheId()), wanted));
    }
    public static Inventory preview(Inventory original, List<ItemStack> hints, List<Ingredient> wanted) {
        var offers = new ArrayList<Offer>();
        for (var stack : hints) if (!stack.isEmpty())
            addOffers(offers, -1, stack, Math.min(stack.getCount(), sourceLimit(stack, wanted)));
        return offers.isEmpty() ? original : new View(original, offers);
    }

    /** All caller reads/writes are staged. Virtual sources follow, never displace, the real inventory. */
    public static final class View extends Inventory {
        private final Inventory original;
        private final List<ItemStack> before, values;
        private final List<Offer> offers;
        private final BitSet reserved = new BitSet();
        private View(Inventory original, List<Offer> offers) {
            super(original.player); this.original = original; this.offers = List.copyOf(offers);
            before = new ArrayList<>(); values = new ArrayList<>();
            for (int slot = 0; slot < original.getContainerSize(); slot++) {
                var stack = original.getItem(slot); before.add(stack.copy());
                // A direct call must not spend a recipe-book source which is still reserved elsewhere.
                if (original.player instanceof ServerPlayer server && CraftingReservations.reservedInventory(server, slot) > 0) reserved.set(slot);
                values.add(reserved.get(slot) ? ItemStack.EMPTY : stack.copy());
            }
            for (var offer : offers) values.add(offer.stack());
        }
        @Override public int getContainerSize() { return values.size(); }
        @Override public ItemStack getItem(int slot) { return slot >= 0 && slot < values.size() ? values.get(slot) : ItemStack.EMPTY; }
        @Override public void setItem(int slot, ItemStack stack) { if (slot >= 0 && slot < values.size()) values.set(slot, stack.copy()); }
        @Override public ItemStack removeItem(int slot, int count) { return getItem(slot).split(Math.max(0, count)); }
        private boolean changed(int slot) { return !reserved.get(slot) && !ItemStack.matches(before.get(slot), values.get(slot)); }
        private boolean current() {
            for (int slot = 0; slot < before.size(); slot++)
                if (changed(slot) && !ItemStack.matches(before.get(slot), original.getItem(slot))) return false;
            return true;
        }
        private void publish() {
            for (int slot = 0; slot < before.size(); slot++) {
                var old = before.get(slot); var next = values.get(slot);
                if (!changed(slot)) continue;
                // Only consumed counts change; unrelated backpack/Curios stack objects stay the same objects.
                original.getItem(slot).setCount(next.getCount());
                if (next.isEmpty()) original.setItem(slot, ItemStack.EMPTY);
            }
            original.setChanged();
        }
        private void restore() {
            for (int slot = 0; slot < before.size(); slot++)
                if (changed(slot) && !ItemStack.matches(values.get(slot), original.getItem(slot)))
                    throw new IllegalStateException("Foreign transfer changed inventory during placement");
            for (int slot = 0; slot < before.size(); slot++) {
                if (!changed(slot)) continue;
                var live = original.getItem(slot);
                if (live.isEmpty()) original.setItem(slot, before.get(slot).copy());
                else live.setCount(before.get(slot).getCount());
            }
            original.setChanged();
        }
    }

    /** Caller still selects ingredients and destination. This source can commit only a complete exact stack. */
    public static final class Transfer implements AutoCloseable {
        private final ServerPlayer player;
        private final CacheHandle handle;
        private final CacheLedger ledger;
        private final CacheRecord before;
        private final long epoch;
        private final Slot target;
        private final net.minecraft.world.inventory.AbstractContainerMenu menu;
        private final int requested;
        private final View view;
        private final IItemHandlerModifiable backpack;
        private final Map<Integer, ItemStack> reads = new HashMap<>();
        private final List<ItemStack> removed = new ArrayList<>();
        private boolean placed, closed, uncertainDestination, nativeReturned;
        private Transfer(ServerPlayer player, CacheHandle handle, CacheRecord before, Slot target, int requested, View view, IItemHandlerModifiable backpack) {
            this.player = player; this.handle = handle; this.before = before; this.target = target;
            this.requested = requested; this.view = view; ledger = CacheLedger.get(player.getServer()); epoch = CraftingReservations.epoch();
            menu = player.containerMenu;
            this.backpack = backpack;
        }
        public Inventory inventory() { return view; }
        public boolean placed() { return placed; }
        /** Observe the caller's original reads and writes. Never replace or write its container. */
        public void read(IItemHandlerModifiable source, int slot, ItemStack stack) {
            if (source == backpack && !closed) reads.put(slot, stack.copy());
        }
        public void wrote(IItemHandlerModifiable source, int slot, ItemStack intendedRemainder) {
            if (source != backpack || closed) return;
            var old = reads.remove(slot);
            if (old == null || old.isEmpty()) return;
            var live = source.getStackInSlot(slot); // Pure read, including after a native setter throws.
            if ((!live.isEmpty() && !ItemStack.isSameItemSameComponents(old, live))
                    || (!intendedRemainder.isEmpty() && !ItemStack.isSameItemSameComponents(old, intendedRemainder))) return;
            int expected = old.getCount() - intendedRemainder.getCount();
            int actual = old.getCount() - live.getCount();
            if (actual > 0 && actual <= expected && expected <= requested) removed.add(old.copyWithCount(actual));
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (placed || uncertainDestination) return;
            if (!removed.isEmpty()) FxntTransferContext.deferFailed(this);
        }
        void returnPublishedNativeDebits() {
            if (nativeReturned || placed || uncertainDestination) return;
            nativeReturned = true;
            // A native draft shrinking is NOT proof its backing item was updated. Only the caller's
            // own completed persistence callback permits returning these debits, after packet processing.
            // Never call that callback ourselves or refresh/write the foreign backpack.
            for (var stack : removed) player.getInventory().placeItemBackInInventory(stack.copy());
        }
        public void place(Slot destination, ItemStack stack, Consumer<ItemStack> nativePlacement) {
            if (closed || placed || destination != target || player.containerMenu != menu || !menu.stillValid(player)
                    || !target.getItem().isEmpty() || stack.getCount() != requested) return;
            var access = AccessGate.resolve(player);
            if (!access.active() || !handle.equals(access.handle()) || ledger.find(handle.cacheId()) != before
                    || epoch != CraftingReservations.epoch() || !view.current()) return;
            var edit = before.state().edit();
            int used = 0;
            for (int slot = 0; slot < view.before.size(); slot++) {
                var old = view.before.get(slot); var next = view.values.get(slot);
                if (!view.changed(slot)) continue;
                if ((!next.isEmpty() && !ItemStack.isSameItemSameComponents(old, next)) || next.getCount() > old.getCount()
                        || !ItemStack.isSameItemSameComponents(old, stack)) return;
                used += old.getCount() - next.getCount();
            }
            for (int i = 0; i < view.offers.size(); i++) {
                var offer = view.offers.get(i); var rest = view.values.get(view.before.size() + i);
                int taken = offer.stack.getCount() - rest.getCount();
                if (taken < 0 || taken > offer.stack.getCount() || taken > 0 && !ItemStack.isSameItemSameComponents(offer.stack, stack)
                        || !rest.isEmpty() && !ItemStack.isSameItemSameComponents(offer.stack, rest)) return;
                if (edit.extract(offer.cell, taken) != taken) return;
                used += taken;
            }
            for (var physical : removed) {
                if (!ItemStack.isSameItemSameComponents(physical, stack)) return;
                used += physical.getCount();
            }
            if (used != requested) return;
            var published = before.withState(edit.finish());
            ledger.replace(handle, before.state().revision(), published);
            view.publish();
            try {
                nativePlacement.accept(stack);
                placed = true;
            } catch (RuntimeException | Error failed) {
                // An exception after the native slot accepted the items must not also refund their source.
                if (ItemStack.matches(target.getItem(), stack)) placed = true;
                else if (target.getItem().isEmpty() && ledger.find(handle.cacheId()) == published) {
                    view.restore();
                    var state = before.state();
                    var restored = state.withRevision(published.state().revision() + 1);
                    ledger.replace(handle, published.state().revision(), before.withState(restored));
                } else {
                    uncertainDestination = true;
                    FeedMePackages.LOGGER.error("FMP foreign transfer failed after an unrecognized destination mutation; refusing a duplicate refund");
                }
                throw failed;
            }
        }
    }
    public static Transfer prepare(Ingredient ingredient, int index, int amount, Inventory inventory, List<Slot> destinations) {
        return prepare(ingredient, index, amount, inventory, null, destinations);
    }
    public static Transfer prepare(Ingredient ingredient, int index, int amount, Inventory inventory,
                                   IItemHandlerModifiable backpack, List<Slot> destinations) {
        if (!FmpConfig.fxntMaterialsEnabled() || !FxntTransferContext.cacheAllowed() || !(inventory.player instanceof ServerPlayer player) || amount < 1 || amount > 64 || index < 0 || index >= destinations.size()) return null;
        var target = destinations.get(index);
        if (!player.containerMenu.slots.contains(target) || !target.getItem().isEmpty()) return null;
        var access = AccessGate.resolve(player);
        if (!access.active() || CraftingReservations.operating(access.handle().cacheId())) return null;
        var record = CacheLedger.get(player.getServer()).find(access.handle().cacheId());
        var offers = available(player, record, List.of(ingredient));
        ItemStack chosen = ItemStack.EMPTY;
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            var stack = inventory.getItem(slot);
            if (CraftingReservations.reservedInventory(player, slot) > 0) return null;
            if (stack.isEmpty() || !ingredient.test(stack)) continue;
            if (chosen.isEmpty()) chosen = stack;
            if (!ItemStack.isSameItemSameComponents(chosen, stack)) return null;
            remaining -= Math.min(remaining, stack.getCount());
        }
        // If the native player inventory already suffices, do not touch the foreign call at all.
        if (remaining == 0) return null;
        for (var offer : offers) if ((offer.stack.getCount() >= remaining || backpack != null)
                && (chosen.isEmpty() || ItemStack.isSameItemSameComponents(chosen, offer.stack))) {
            if (amount > offer.stack.getMaxStackSize() || amount > target.getMaxStackSize(offer.stack) || !target.mayPlace(offer.stack)) return null;
            var view = new View(inventory, List.of(new Offer(offer.cell, offer.stack.copyWithCount(Math.min(remaining, offer.stack.getCount())))));
            return new Transfer(player, access.handle(), record, target, amount, view, backpack);
        }
        // With no matching cache source the caller's native path is untouched.
        return null;
    }
}
