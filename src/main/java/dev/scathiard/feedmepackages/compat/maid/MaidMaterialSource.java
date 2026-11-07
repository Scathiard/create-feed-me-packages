package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.Predicate;

/** A cache identity bound to one native consumption operation, never an entity capability. */
public final class MaidMaterialSource {
    private final LivingEntity maid;
    private final CacheHandle handle;
    private MaidMaterialSource(LivingEntity maid, CacheHandle handle) { this.maid = maid; this.handle = handle; }
    public static MaidMaterialSource open(LivingEntity maid) {
        if (maid.level().isClientSide || !MaidCompatibility.isMaid(maid)) return null;
        var access = MaidAccess.resolve(maid);
        return access.active() ? new MaidMaterialSource(maid, access.handle()) : null;
    }
    private CacheRecord current() {
        var access = MaidAccess.resolve(maid);
        if (!access.active() || !handle.equals(access.handle())
                || CraftingReservations.operating(handle.cacheId()) || FluidActions.operating(handle.cacheId())) return null;
        return CacheLedger.get(maid.getServer()).find(handle.cacheId());
    }
    public List<ItemStack> candidates(Predicate<ItemStack> accepts) {
        var record = current(); if (record == null) return List.of();
        var result = new ArrayList<ItemStack>();
        for (var cell : record.state().cells()) if (cell.filter() != null && cell.amount() > 0) {
            var stack = cell.filter().stack(maid.registryAccess(), 1);
            if (!stack.isEmpty() && accepts.test(stack.copy()) && available(stack) > 0) result.add(stack);
        }
        return result;
    }
    private int index(CacheRecord record, ItemStack prototype) {
        if (record == null || prototype.isEmpty()) return -1;
        try { return record.state().find(ItemVariantKey.of(prototype, maid.registryAccess())); }
        catch (IllegalArgumentException unsupported) { return -1; }
    }
    public int available(ItemStack prototype) {
        var record = current(); int cell = index(record, prototype);
        return cell < 0 ? 0 : Math.max(0, record.state().cells().get(cell).amount()
                - CraftingReservations.reservedCache(handle.cacheId(), cell, null));
    }
    /** No foreign callbacks between the final access check and the ledger replacement. */
    public boolean take(ItemStack prototype, int amount) {
        if (amount <= 0) return false;
        var record = current(); int cell = index(record, prototype);
        if (cell < 0 || record.state().cells().get(cell).amount()
                - CraftingReservations.reservedCache(handle.cacheId(), cell, null) < amount) return false;
        var edit = record.state().edit(); edit.extract(cell, amount);
        CacheLedger.get(maid.getServer()).replace(handle, record.state().revision(), record.withState(edit.finish()));
        return true;
    }
}
