package dev.scathiard.feedmepackages.compat.emi;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** An owned source appended only to EMI's native server collection, never to a real menu or inventory. */
public final class EmiCacheSources {
    private EmiCacheSources() {}
    private static int batch(long available, int limit) { return (int) Math.max(0, Math.min(available, limit)); }
    public static boolean supported(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu || menu instanceof CraftingMenu || menu instanceof StonecutterMenu;
    }
    public static List<Slot> append(Player player, List<Slot> original, ItemStack wanted) {
        if (!FmpConfig.emiCraftingEnabled() || !(player instanceof ServerPlayer server) || !server.getServer().isSameThread()
                || !supported(player.containerMenu) || !player.containerMenu.stillValid(player) || wanted.isEmpty()
                || wanted.getCount() < 1 || wanted.getCount() > Math.min(64, wanted.getMaxStackSize())) return original;
        var access = AccessGate.resolve(server);
        if (!access.active() || CraftingReservations.operating(access.handle().cacheId())) return original;
        var record = CacheLedger.get(server.getServer()).find(access.handle().cacheId());
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot);
            if (cell.filter() == null || cell.amount() <= 0) continue;
            ItemStack prototype;
            try { prototype = cell.filter().stack(server.registryAccess(), 1); }
            catch (IllegalArgumentException unreadable) { continue; }
            if (!ItemStack.isSameItemSameComponents(wanted, prototype)) continue;
            int count = batch(cell.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), slot, null), wanted.getCount());
            if (count <= 0) return original;
            var sources = new ArrayList<>(original);
            sources.add(new Source(server, access.handle(), record, slot, prototype.copyWithCount(count)));
            return sources;
        }
        return original;
    }
    private static final class Source extends Slot {
        private final ServerPlayer owner;
        private final CacheHandle handle;
        private final CacheRecord before;
        private final AbstractContainerMenu menu;
        private final ItemStack offered;
        private final int cell;
        private final long epoch;
        private boolean spent;
        Source(ServerPlayer owner, CacheHandle handle, CacheRecord before, int cell, ItemStack offered) {
            super(new SimpleContainer(offered.copy()), 0, 0, 0);
            this.owner = owner; this.handle = handle; this.before = before; this.cell = cell; this.offered = offered.copy();
            menu = owner.containerMenu; epoch = CraftingReservations.epoch(); index = -1;
        }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) {
            var access = AccessGate.resolve(owner);
            return !spent && player == owner && owner.getServer().isSameThread() && FmpConfig.emiCraftingEnabled()
                    && owner.containerMenu == menu && menu.stillValid(owner) && access.active() && handle.equals(access.handle())
                    && CacheLedger.get(owner.getServer()).find(handle.cacheId()) == before && CraftingReservations.epoch() == epoch;
        }
        @Override public void onTake(Player player, ItemStack ignoredNativeSnapshot) {
            var remainder = getItem(); int amount = offered.getCount() - remainder.getCount();
            // Native collection has no foreign callback between mayPickup/getItem and onTake for this owned slot.
            if (!mayPickup(player) || amount < 1 || amount > offered.getCount()
                    || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(offered, remainder))
                throw new IllegalStateException("EMI cache source changed during native collection");
            var edit = before.state().edit();
            if (edit.extract(cell, amount) != amount) throw new IllegalStateException("EMI cache source no longer available");
            CacheLedger.get(owner.getServer()).replace(handle, before.state().revision(), before.withState(edit.finish()));
            spent = true;
        }
    }
}
