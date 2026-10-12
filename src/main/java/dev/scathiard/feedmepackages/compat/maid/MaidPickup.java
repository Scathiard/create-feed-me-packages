package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Routes only actual native pickup increments, never a post-event display copy or old inventory. */
public final class MaidPickup {
    private static final String ENABLED = "create_feed_me_packages:pickup_to_cache";
    private MaidPickup() {}
    public static boolean enabled(LivingEntity maid) {
        return maid != null && MaidCompatibility.isMaid(maid) && maid.getPersistentData().getBoolean(ENABLED);
    }
    public static void setEnabled(LivingEntity maid, boolean value) {
        if (!MaidAccess.resolve(maid).active()) throw new IllegalStateException("Unavailable maid pickup target");
        maid.getPersistentData().putBoolean(ENABLED, value);
    }
    public record Capture(CacheHandle handle, ItemStack ground, List<ItemStack> before) {}
    public static Capture before(LivingEntity maid, ItemEntity ground, boolean simulate) {
        if (simulate || maid.level().isClientSide || !enabled(maid) || !ground.isAlive() || ground.hasPickUpDelay()) return null;
        var access = MaidAccess.resolve(maid);
        if (!access.active() || busy(access.handle())) return null;
        var inventory = MaidCompatibility.inventory(maid); var stacks = new ArrayList<ItemStack>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) stacks.add(inventory.getStackInSlot(slot).copy());
        return new Capture(access.handle(), ground.getItem().copy(), List.copyOf(stacks));
    }
    private static boolean busy(CacheHandle handle) {
        return CraftingReservations.operating(handle.cacheId()) || FluidActions.operating(handle.cacheId());
    }
    public static void after(LivingEntity maid, Capture capture, boolean pickedUp) {
        if (capture == null || !pickedUp || !enabled(maid)) return;
        var access = MaidAccess.resolve(maid);
        if (!access.active() || !capture.handle().equals(access.handle()) || busy(access.handle())) return;
        var inventory = MaidCompatibility.inventory(maid);
        if (inventory.getSlots() != capture.before().size()) return;
        var ledger = CacheLedger.get(maid.getServer()); var record = ledger.find(access.handle().cacheId());
        ItemVariantKey key;
        try { key = ItemVariantKey.of(capture.ground(), maid.registryAccess()); }
        catch (IllegalArgumentException unsupported) { return; }
        int cell = record.state().find(key);
        if (cell < 0 || !record.state().writable(cell)) return;
        var edit = record.state().edit(); var changed = new LinkedHashMap<Integer, ItemStack>();
        int remaining = capture.ground().getCount();
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
            var before = capture.before().get(slot); var actual = inventory.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameComponents(actual, capture.ground())
                    || !before.isEmpty() && !ItemStack.isSameItemSameComponents(before, actual)) continue;
            int added = Math.min(remaining, actual.getCount() - before.getCount());
            if (added <= 0) continue;
            remaining -= added;
            int moved = edit.insert(cell, key, added);
            if (moved > 0) changed.put(slot, actual.copyWithCount(actual.getCount() - moved));
        }
        if (changed.isEmpty()) return;
        // No foreign callback between proof and commit. Native backpack setters preserve the handler and slots.
        ledger.replace(access.handle(), record.state().revision(), record.withState(edit.finish()));
        changed.forEach(inventory::setStackInSlot);
    }
}
