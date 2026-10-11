package dev.scathiard.feedmepackages.compat.sophisticated;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.*;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Native transfer works on call-local slots; no synthetic slot is installed in a real menu or packet. */
public final class SophisticatedMaterials {
    private SophisticatedMaterials() {}
    private static int batch(long available, int limit) { return (int) Math.max(0, Math.min(available, limit)); }
    private static final ThreadLocal<Transfer> CURRENT = new ThreadLocal<>();
    public static Transfer current() { return CURRENT.get(); }
    public static boolean refill(Object wrapper, Player player, ItemStack input) {
        if (!(player instanceof ServerPlayer server) || input.isEmpty() || CURRENT.get() != null) return false;
        var context = SophisticatedAccess.resolve(player);
        if (context == null || context.wrapper() != wrapper || !context.refill()) return false;
        var tx = MaterialTransaction.open(server).orElse(null);
        return tx != null && tx.takeCache(input, 1) && context.current() && tx.commit();
    }
    public static Transfer prepare(ServerPlayer player, int menu, int upgrade, UUID cache, RecipeHolder<CraftingRecipe> recipe, boolean maximum) {
        if (CURRENT.get() != null || recipe == null) return null;
        var context = SophisticatedAccess.resolve(player); var access = AccessGate.resolve(player);
        if (context == null || context.menu().containerId != menu || context.upgrade() != upgrade || !access.active()
                || !access.handle().cacheId().equals(cache) || (CraftingReservations.operating(cache) || dev.scathiard.feedmepackages.interaction.FluidActions.operating(cache))
                || !context.menu().getCarried().isEmpty()) return null;
        var tx = new Transfer(context, access.handle(), recipe, maximum);
        return tx.choice.error() == CraftingPlanner.Error.NONE ? tx : null;
    }
    private static final class Draft extends Slot {
        final Slot original;
        final int cell;
        final ItemStack before;
        ItemStack value;
        Draft(Slot original, int cell, ItemStack stack) {
            super(new SimpleContainer(1), 0, -100, -100);
            this.original = original; this.cell = cell; before = stack.copy(); value = stack.copy();
            index = original == null ? -1 : original.index;
        }
        @Override public ItemStack getItem() { return value; }
        @Override public void set(ItemStack stack) { value = stack.copy(); }
        @Override public ItemStack remove(int count) { return value.split(Math.max(0, count)); }
        @Override public void setChanged() {}
        @Override public boolean mayPickup(Player player) { return original == null || original.mayPickup(player); }
        @Override public boolean mayPlace(ItemStack stack) { return original != null && original.mayPlace(stack); }
        @Override public int getMaxStackSize() { return original == null ? 64 : original.getMaxStackSize(); }
        @Override public int getMaxStackSize(ItemStack stack) { return original == null ? stack.getMaxStackSize() : original.getMaxStackSize(stack); }
        boolean changed() { return !ItemStack.matches(before, value); }
    }
    public static final class Transfer {
        public enum Result { NOT_RUN, OK, STALE, NO_SPACE, INVALID_DRAFT }
        private Result result = Result.NOT_RUN;
        public Result result() { return result; }
        private boolean refused(Result reason) { result = reason; return false; }
        private final SophisticatedAccess.Context context;
        private final ServerPlayer player;
        private final CacheHandle handle;
        private final CacheLedger ledger;
        private final CacheRecord before;
        private final long epoch;
        private final RecipeHolder<CraftingRecipe> recipe;
        private final boolean maximum;
        private final List<Draft> physical = new ArrayList<>(), cache = new ArrayList<>();
        private final Map<Slot, Draft> slots = new IdentityHashMap<>();
        private final CraftingPlanner.Choice choice;
        private boolean overflow, used;
        private Transfer(SophisticatedAccess.Context context, CacheHandle handle, RecipeHolder<CraftingRecipe> recipe, boolean maximum) {
            this.context = context; player = (ServerPlayer) context.player(); this.handle = handle; this.recipe = recipe; this.maximum = maximum;
            ledger = CacheLedger.get(player.getServer()); before = ledger.find(handle.cacheId()); epoch = CraftingReservations.epoch();
            for (var slot : context.all()) {
                var draft = new Draft(slot, -1, slot.getItem()); physical.add(draft); slots.put(slot, draft);
            }
            for (int slot = 0; slot < before.state().cells().size(); slot++) {
                var cell = before.state().cells().get(slot);
                if (cell.filter() == null || cell.amount() == 0) continue;
                var prototype = cell.filter().stack(player.registryAccess(), 1);
                if (recipe.value().getIngredients().stream().noneMatch(i -> i.test(prototype))) continue;
                int count = batch(cell.amount() - CraftingReservations.reservedCache(handle.cacheId(), slot, null),
                        9 * Math.min(64, prototype.getMaxStackSize()));
                if (count > 0) cache.add(new Draft(null, slot, prototype.copyWithCount(count)));
            }
            var sources = new ArrayList<ItemStack>(); physical.forEach(d -> sources.add(d.before.copy())); cache.forEach(d -> sources.add(d.before.copy()));
            choice = CraftingPlanner.solve(recipe, 3, 3, 64, 1, maximum, sources, player.level());
        }
        public Slot slot(Slot real) { var draft = slots.get(real); return draft == null ? real : draft; }
        public Slot fallback(ItemStack wanted) {
            for (var draft : cache) if (!draft.value.isEmpty() && ItemStack.isSameItemSameComponents(draft.value, wanted)) return draft;
            return null;
        }
        /** Native fallback cannot drop or insert unstaged materials behind the transaction. */
        public void overflow() { overflow = true; }
        public boolean execute() {
            if (used || CURRENT.get() != null || !currentState()) return refused(Result.STALE);
            used = true; CURRENT.set(this);
            try { context.transfer(recipe.id(), choice.grid(), maximum); }
            finally { CURRENT.remove(); }
            if (overflow) return refused(Result.NO_SPACE);
            if (!currentState()) return refused(Result.STALE);
            if (!validDraft()) return refused(Result.INVALID_DRAFT);
            var edit = before.state().edit();
            for (var draft : cache) {
                int taken = draft.before.getCount() - draft.value.getCount();
                if (taken < 0 || !draft.value.isEmpty() && !ItemStack.isSameItemSameComponents(draft.before, draft.value)
                        || edit.extract(draft.cell, taken) != taken) return refused(Result.INVALID_DRAFT);
            }
            var changed = physical.stream().filter(Draft::changed).toList();
            var next = before.withState(edit.finish());
            ledger.replace(handle, before.state().revision(), next);
            try {
                // Native setters own component persistence and callbacks; untouched objects are never replaced.
                for (var draft : changed) draft.original.set(draft.value.copy());
            } catch (RuntimeException | Error failed) {
                restore(changed, next, failed); throw failed;
            }
            // The native CraftingItemHandler caches its item list. Publish a final slot notification
            // after all writes, including a one-input recipe starting from an empty cached matrix.
            context.grid().getFirst().setChanged();
            context.recipeUsed(recipe.id()); context.menu().broadcastChanges(); result = Result.OK; return true;
        }
        private boolean currentState() {
            var access = AccessGate.resolve(player);
            if (!context.current() || !context.menu().getCarried().isEmpty() || !access.active() || !handle.equals(access.handle())
                    || ledger.find(handle.cacheId()) != before || epoch != CraftingReservations.epoch()) return false;
            for (var draft : physical) if (!ItemStack.matches(draft.before, draft.original.getItem())) return false;
            return true;
        }
        private boolean validDraft() {
            var grid = context.grid().stream().map(s -> slots.get(s).value).toList();
            if (!recipe.value().matches(CraftingInput.of(3, 3, grid), player.level())) return false;
            for (var draft : physical) {
                if (!draft.changed() || draft.value.isEmpty()) continue;
                if (draft.value.getCount() > draft.original.getMaxStackSize(draft.value) || !draft.original.mayPlace(draft.value)) return false;
            }
            // Check conservation across the native draft, independently of the native selection algorithm.
            var types = new ArrayList<ItemStack>(); var balances = new ArrayList<Long>();
            for (var draft : physical) { balance(types, balances, draft.before, -1); balance(types, balances, draft.value, 1); }
            for (var draft : cache) { balance(types, balances, draft.before, -1); balance(types, balances, draft.value, 1); }
            return balances.stream().allMatch(n -> n == 0);
        }
        private void restore(List<Draft> changed, CacheRecord published, Throwable failed) {
            try {
                if (ledger.find(handle.cacheId()) != published || changed.stream().anyMatch(d ->
                        !ItemStack.matches(d.original.getItem(), d.before) && !ItemStack.matches(d.original.getItem(), d.value)))
                    throw new IllegalStateException("Unrecognized native mutation; refusing duplicate refund");
                for (var draft : changed) if (!ItemStack.matches(draft.original.getItem(), draft.before)) draft.original.set(draft.before.copy());
                if (changed.stream().anyMatch(d -> !ItemStack.matches(d.original.getItem(), d.before)))
                    throw new IllegalStateException("Native slot rollback was not retained");
                var state = before.state();
                var restored = new CacheState<>(state.id(), state.level(), published.state().revision() + 1, state.cells(),
                        state.orders());
                ledger.replace(handle, published.state().revision(), before.withState(restored));
            } catch (RuntimeException | Error recovery) {
                failed.addSuppressed(recovery);
                FeedMePackages.LOGGER.error("FMP Sophisticated transfer failed during native persistence; preserved evidence without speculative refund", failed);
            }
        }
    }
    private static void balance(List<ItemStack> types, List<Long> values, ItemStack stack, int sign) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < types.size(); i++) if (ItemStack.isSameItemSameComponents(types.get(i), stack)) {
            values.set(i, values.get(i) + (long) sign * stack.getCount()); return;
        }
        types.add(stack.copyWithCount(1)); values.add((long) sign * stack.getCount());
    }
}
