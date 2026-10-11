package dev.scathiard.feedmepackages.interaction;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import java.lang.ref.WeakReference;
import java.util.*;
import static dev.scathiard.feedmepackages.interaction.CacheActions.Result;

/** Fluid cursor is a reservation, never a fake item. Only an actual container placement spends it. */
public final class FluidActions {
    private FluidActions() {}
    public record Preview(int slot, String template, int amount, int sequence) {
        public static final Preview NONE = new Preview(-1, "", 0, 0);
    }
    private record Held(CacheHandle handle, UUID session, WeakReference<AbstractContainerMenu> menu,
                        int slot, FluidVariantKey variant, long filterRevision, int amount, int sequence) {}
    private static final Map<ServerPlayer, Held> HELD = new WeakHashMap<>();
    private static final Set<UUID> OPERATING = new HashSet<>();
    private static final Set<net.minecraft.world.item.Item> WARNED = Collections.newSetFromMap(new IdentityHashMap<>());
    public static boolean operating(UUID cache) { return OPERATING.contains(cache); }
    public static void cancel(ServerPlayer player) { HELD.remove(player); }
    public static long reserved(UUID cache, int slot) {
        return HELD.values().stream().filter(h -> h.handle().cacheId().equals(cache) && h.slot() == slot).mapToLong(Held::amount).sum();
    }
    public static Preview preview(ServerPlayer player) {
        validate(player); var held = HELD.get(player);
        return held == null ? Preview.NONE : new Preview(held.slot(), held.variant().encoded(), held.amount(), held.sequence());
    }
    public static void validate(ServerPlayer player) {
        var held = HELD.get(player); if (held == null) return;
        var access = AccessGate.resolve(player); var record = CacheLedger.get(player.getServer()).find(held.handle().cacheId());
        if (!access.active() || !held.handle().equals(access.handle()) || held.menu().get() != player.containerMenu
                || !player.containerMenu.getCarried().isEmpty() || record == null || held.slot() >= record.fluids().state().cells().size()) {
            cancel(player); return;
        }
        var cell = record.fluids().state().cells().get(held.slot());
        if (!held.variant().equals(cell.filter()) || held.filterRevision() != cell.filterRevision()
                || cell.amount() < reserved(held.handle().cacheId(), held.slot())) cancel(player);
    }
    public static Result release(ServerPlayer player, UUID session, int sequence) {
        var held = HELD.get(player);
        if (held == null) return Result.OK;
        if (!held.session().equals(session) || held.sequence() != sequence) return Result.STALE;
        cancel(player); return Result.OK;
    }
    public static boolean handles(CacheRecord record, int slot, CacheActions.Action action) {
        return switch (action) {
            case SET_FLUID_GHOST, TAKE_FLUID, PLACE_FLUID, DEPOSIT_FLUID -> true;
            default -> slot >= 0 && slot < record.fluids().state().cells().size()
                    && record.fluids().state().cells().get(slot).filter() != null;
        };
    }

    /** CacheActions has already proved the session, current access, revision and idle crafting transaction. */
    public static Result execute(ServerPlayer player, CacheHandle handle, CacheRecord before, CacheActions.Intent intent,
                                 ItemStack carried, boolean creative, int sequence) {
        int slot = intent.slot(); var state = before.fluids().state();
        if (slot < 0 || slot >= state.cells().size()) return Result.INVALID_REQUEST;
        if (operating(handle.cacheId())) return Result.STALE;
        if ((!state.writable(slot) || !before.state().writable(slot)) && switch(intent.action()) {
            case SET_GHOST, SET_FLUID_GHOST, DEPOSIT_FLUID, CLEAR_FILTER, THRESHOLDS -> true;
            default -> false;
        }) return Result.READ_ONLY;
        var cell = state.cells().get(slot); var ledger = CacheLedger.get(player.getServer());
        var itemCell = slot < before.state().cells().size() ? before.state().cells().get(slot) : Cell.<ItemVariantKey>empty();
        switch (intent.action()) {
            case TAKE_FLUID -> {
                validate(player);
                if (cell.filter() == null || !carried.isEmpty() || CursorReservations.reserved(handle.cacheId(), slot) != 0
                        || HELD.containsKey(player) || sequence < 1 || intent.first() < 1 || intent.first() > 8000) return Result.INVALID_REQUEST;
                int amount = (int)Math.min(intent.first(), Math.max(0, cell.amount() - reserved(handle.cacheId(), slot)));
                if (amount == 0) return Result.MISSING_MATERIAL;
                HELD.put(player, new Held(handle, intent.session(), new WeakReference<>(player.containerMenu), slot,
                        cell.filter(), cell.filterRevision(), amount, sequence));
                return Result.OK;
            }
            case PLACE_FLUID -> { return place(player, handle, before, intent); }
            case DEPOSIT_FLUID -> { return deposit(player, handle, before, intent, carried, creative, sequence); }
            case SET_FLUID_GHOST -> {
                if (!carried.isEmpty()) return Result.INVALID_REQUEST;
                if (cell.amount() != 0 || itemCell.amount() != 0) return Result.FILTER_OCCUPIED;
                FluidVariantKey key;
                try { key = FluidVariantKey.decode(intent.template(), player.registryAccess()); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
                if (!dev.scathiard.feedmepackages.service.CachePolicy.admits(key,player.registryAccess())) return Result.DENIED;
                int existing = state.find(key); if (existing >= 0 && existing != slot) return Result.DUPLICATE_FILTER;
                var fluids = state.edit(); fluids.filter(slot, key);
                var nextFluids = before.fluids().withState(fluids.finish());
                CacheRecord after;
                if (itemCell.filter() != null) {
                    var items = before.state().edit(); items.filter(slot, null);
                    after = before.withStates(items.finish(), nextFluids);
                } else after = before.withFluids(nextFluids);
                ledger.replace(handle, before.state().revision(), after); return Result.OK;
            }
            case SET_GHOST -> {
                if (!carried.isEmpty()) return Result.INVALID_REQUEST;
                if (cell.amount() != 0) return Result.FILTER_OCCUPIED;
                ItemVariantKey key;
                try { key = ItemVariantKey.decode(intent.template(), player.registryAccess()); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
                if (!dev.scathiard.feedmepackages.service.CachePolicy.admits(key,player.registryAccess())) return Result.DENIED;
                int existing = before.state().find(key); if (existing >= 0 && existing != slot) return Result.DUPLICATE_FILTER;
                var items = before.state().edit(); items.filter(slot, key);
                var fluids = state.edit(); fluids.filter(slot, null);
                ledger.replace(handle, before.state().revision(), before.withStates(items.finish(), before.fluids().withState(fluids.finish())));
                return Result.OK;
            }
            case CLEAR_FILTER, THRESHOLDS, RESET_REQUEST -> {
                var edit = state.edit();
                if (intent.action() == CacheActions.Action.CLEAR_FILTER) {
                    if (cell.amount() != 0) return Result.FILTER_OCCUPIED;
                    edit.filter(slot, null);
                } else if (intent.action() == CacheActions.Action.RESET_REQUEST) edit.reset(slot);
                else {
                    try { edit.thresholds(slot, intent.first(), intent.second()); }
                    catch (IllegalArgumentException invalid) { return Result.INVALID_REQUEST; }
                }
                ledger.replace(handle, before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
                return Result.OK;
            }
            default -> { return Result.INVALID_REQUEST; }
        }
    }

    private static Result place(ServerPlayer player, CacheHandle handle, CacheRecord before, CacheActions.Intent intent) {
        validate(player); var held = HELD.get(player); var inventory = player.getInventory();
        int target = (int)intent.first();
        if (held == null || !held.session().equals(intent.session()) || held.slot() != intent.slot()
                || held.sequence() != intent.second() || !held.handle().equals(handle)) return Result.STALE;
        if (target < 0 || target >= InventoryTransfer.SLOTS) return Result.INVALID_REQUEST;
        var actual = inventory.getItem(target); var original = actual.copy();
        if (actual.isEmpty() || actual.getCount() <= CraftingReservations.reservedInventory(player, target)) return Result.MISSING_MATERIAL;
        try {
            if (!ItemVariantKey.of(actual, player.registryAccess()).encoded().equals(intent.template())) return Result.STALE;
        } catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
        OPERATING.add(handle.cacheId());
        try {
            var transfer = fill(actual, held.variant(), held.amount(), player.registryAccess());
            if (transfer == null) return Result.NO_SPACE;
            var baseline = new ArrayList<ItemStack>(); for (int i = 0; i < InventoryTransfer.SLOTS; i++) baseline.add(inventory.getItem(i).copy());
            List<ItemStack> next;
            if (actual.getCount() == 1) { next = new ArrayList<>(baseline); next.set(target, transfer.container()); }
            else {
                var insertion = InventoryTransfer.insertAll(inventory, List.of(transfer.container()), Set.of(target));
                if (insertion.moved() != transfer.container().getCount()) return Result.NO_SPACE;
                next = insertion.after(); next.get(target).shrink(1);
            }
            var plan = new InventoryTransfer.Plan(baseline, next, transfer.amount());
            var ledger = CacheLedger.get(player.getServer());
            if (HELD.get(player) != held || held.menu().get() != player.containerMenu || !player.containerMenu.getCarried().isEmpty()
                    || !stable(player, handle, before) || !ItemStack.matches(original, inventory.getItem(target))
                    || !plan.stillValid(inventory) || actual.getCount() <= CraftingReservations.reservedInventory(player, target)) return Result.STALE;
            var edit = before.fluids().state().edit();
            if (edit.extract(held.slot(), transfer.amount()) != transfer.amount()) return Result.STALE;
            ledger.replace(handle, before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
            plan.commit(inventory);
            int remaining = held.amount() - transfer.amount();
            if (remaining == 0) cancel(player);
            else HELD.put(player, new Held(handle, held.session(), held.menu(), held.slot(), held.variant(), held.filterRevision(), remaining, held.sequence()));
            player.containerMenu.broadcastChanges(); return Result.OK;
        } finally { OPERATING.remove(handle.cacheId()); }
    }

    private static Result deposit(ServerPlayer player, CacheHandle handle, CacheRecord before, CacheActions.Intent intent,
                                  ItemStack carried, boolean creative, int sequence) {
        // One physical container per gesture. Stacked containers must first be split using vanilla inventory controls.
        if (carried.isEmpty() || carried.getCount() != 1 || HELD.containsKey(player)) return Result.INVALID_ITEM;
        var original = carried.copy(); var menu = player.containerMenu; int slot = intent.slot();
        if (slot < before.state().cells().size() && before.state().cells().get(slot).filter() != null) return Result.FILTER_OCCUPIED;
        var cell = before.fluids().state().cells().get(slot); OPERATING.add(handle.cacheId());
        try {
            var transfer = drain(carried, before, slot, creative, player.registryAccess());
            if (transfer.result() != Result.OK) return transfer.result();
            var key = transfer.variant();
            if (player.containerMenu != menu || !stable(player, handle, before)
                    || !ItemStack.matches(original, creative ? carried : menu.getCarried())
                    || creative && !menu.getCarried().isEmpty()) return Result.STALE;
            var edit = before.fluids().state().edit(); if (cell.filter() == null) edit.filter(slot, key);
            if (edit.insert(slot, key, transfer.amount()) != transfer.amount()) return Result.STALE;
            CacheLedger.get(player.getServer()).replace(handle, before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
            CacheActions.cursor(player, transfer.container(), creative, intent.session(), sequence); menu.broadcastChanges(); return Result.OK;
        } finally { OPERATING.remove(handle.cacheId()); }
    }

    private record Drained(Result result, FluidVariantKey variant, ItemStack container, int amount) {
        static Drained refused(Result result) { return new Drained(result, null, ItemStack.EMPTY, 0); }
    }
    /** Foreign capabilities execute only on private copies. Validate their actual output before publishing any state. */
    private static Drained drain(ItemStack original, CacheRecord before, int slot, boolean creative, HolderLookup.Provider registries) {
        try {
            var handler = FluidUtil.getFluidHandler(original.copy()).orElse(null);
            if (handler == null) return Drained.refused(Result.INVALID_ITEM);
            var contents = totals(handler, registries); var cell = before.fluids().state().cells().get(slot);
            var key = cell.filter() != null ? cell.filter() : contents.keySet().stream().findFirst().orElse(null);
            if (key == null || !contents.containsKey(key)) return Drained.refused(Result.INVALID_ITEM);
            if (!dev.scathiard.feedmepackages.service.CachePolicy.admits(key,registries)) return Drained.refused(Result.DENIED);
            int existing = before.fluids().state().find(key);
            if (existing >= 0 && existing != slot) return Drained.refused(Result.DUPLICATE_FILTER);
            int limit = (int)Math.min(Integer.MAX_VALUE, Math.max(0, (long)before.state().capacity().items(key) - cell.amount()));
            if (limit == 0) return Drained.refused(Result.NO_SPACE);
            var drained = handler.drain(key.stack(registries, limit), IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) return Drained.refused(Result.NO_SPACE);
            if (drained.getAmount() > limit || !key.equals(FluidVariantKey.of(drained, registries))) return Drained.refused(Result.INVALID_ITEM);
            var result = handler.getContainer().copy();
            if (result.getCount() > 1 || !delta(contents, totals(result, registries), key, -drained.getAmount()))
                return Drained.refused(Result.INVALID_ITEM);
            // Creative cursor replies use the bounded FMP codec; prove representability before deducting.
            if (creative && !result.isEmpty()) ItemVariantKey.of(result, registries);
            return new Drained(Result.OK, key, result, drained.getAmount());
        } catch (RuntimeException | LinkageError failure) {
            capabilityFailure(original, failure); return Drained.refused(Result.INVALID_ITEM);
        }
    }

    private static boolean stable(ServerPlayer player, CacheHandle handle, CacheRecord before) {
        var access = AccessGate.resolve(player);
        return access.active() && handle.equals(access.handle()) && CacheLedger.get(player.getServer()).find(handle.cacheId()) == before;
    }
    private record Filled(ItemStack container, int amount) {}
    private static Filled fill(ItemStack original, FluidVariantKey key, int amount, HolderLookup.Provider registries) {
        try {
            var handler = FluidUtil.getFluidHandler(original.copyWithCount(1)).orElse(null); if (handler == null) return null;
            var initial = totals(handler, registries);
            int filled = handler.fill(key.stack(registries, amount), IFluidHandler.FluidAction.EXECUTE);
            var result = handler.getContainer().copy();
            if (filled < 1 || filled > amount || result.getCount() != 1 || !delta(initial, totals(result, registries), key, filled)) return null;
            return new Filled(result, filled);
        } catch (RuntimeException | LinkageError failure) { capabilityFailure(original, failure); return null; }
    }
    private static void capabilityFailure(ItemStack stack, Throwable failure) {
        if (WARNED.add(stack.getItem())) dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn(
                "FMP fluid container refused: item={} failure={}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                failure.getClass().getSimpleName());
    }
    private static Map<FluidVariantKey, Long> totals(ItemStack stack, HolderLookup.Provider registries) {
        return stack.isEmpty() ? Map.of() : FluidUtil.getFluidHandler(stack.copy()).map(h -> totals(h, registries)).orElse(Map.of());
    }
    private static Map<FluidVariantKey, Long> totals(IFluidHandlerItem handler, HolderLookup.Provider registries) {
        int tanks = handler.getTanks(); if (tanks < 0 || tanks > 128) throw new IllegalArgumentException("Unsupported container tank count");
        var contents = new LinkedHashMap<FluidVariantKey, Long>();
        for (int i = 0; i < tanks; i++) {
            var fluid = handler.getFluidInTank(i);
            if (!fluid.isEmpty()) contents.merge(FluidVariantKey.of(fluid, registries), (long)fluid.getAmount(), Math::addExact);
        }
        return contents;
    }
    private static boolean delta(Map<FluidVariantKey, Long> before, Map<FluidVariantKey, Long> after, FluidVariantKey key, int change) {
        var expected = new LinkedHashMap<>(before); long amount = expected.getOrDefault(key, 0L) + change;
        if (amount < 0) return false;
        if (amount == 0) expected.remove(key); else expected.put(key, amount);
        return expected.equals(after);
    }
}
