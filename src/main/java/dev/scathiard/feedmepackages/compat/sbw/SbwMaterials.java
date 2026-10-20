package dev.scathiard.feedmepackages.compat.sbw;

import com.atsuishio.superbwarfare.data.gun.*;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.ammo.AmmoBoxItem;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.*;
import java.util.function.Supplier;

/** Scoped last source for the author's ITEM/PLAYER_AMMO strategies, never a global entity capability. */
public final class SbwMaterials {
    private SbwMaterials() {}
    private static Supplier<List<ItemStack>> client = List::of;
    public static void clientSources(Supplier<List<ItemStack>> value) { client = Objects.requireNonNull(value); }
    private static final Set<GunData> SUPPLIED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static Player operator(GunData gun, Entity supplier) {
        if (supplier instanceof Player player) {
            return !(player instanceof FakePlayer) && player.isAlive() && !player.isSpectator()
                    && (player.getMainHandItem() == gun.stack || player.getOffhandItem() == gun.stack) ? player : null;
        }
        if (!(supplier instanceof VehicleEntity vehicle) || !vehicle.isAlive() || vehicle.getAmmoSupplier() != vehicle) return null;
        Player result = null;
        // Resolve the weapon's native seat first, then inspect only that seat's occupant. Never search inventories.
        for (int seat = 0; seat < vehicle.getOrderedPassengers().size(); seat++) {
            if (vehicle.getGunData(seat) != gun) continue;
            if (!(vehicle.getNthEntity(seat) instanceof Player player) || player instanceof FakePlayer
                    || player.getVehicle() != vehicle || !player.isAlive() || player.isSpectator()) continue;
            if (result != null) return null; // Shared/ambiguous weapon control does not authorize either cache.
            result = player;
        }
        return result;
    }
    public static void refreshCount(GunData gun, Entity entity) {
        var player = operator(gun, entity);
        if (SUPPLIED.contains(gun) || player instanceof ServerPlayer server && AccessGate.resolve(server).active()) {
            // A pendant/seat can change within SBW's three-tick count cache. Ask its native counter again.
            gun.cachedBackupAmmo = -1;
        }
    }
    public static IItemHandler append(IItemHandler nativeSource, GunData gun, Entity supplier) {
        if(!dev.scathiard.feedmepackages.FmpConfig.sbwEnabled()) return nativeSource;
        var player = operator(gun, supplier);
        if (player == null) return nativeSource;
        List<ItemStack> prototypes;
        if (player instanceof ServerPlayer server) {
            if (!server.getServer().isSameThread()) return nativeSource;
            var tx = MaterialTransaction.open(server).orElse(null); if (tx == null) return nativeSource;
            prototypes = tx.candidates(s -> !(s.getItem() instanceof AmmoBoxItem)).stream()
                    .filter(s -> tx.cacheAvailable(s) > 0).toList();
        } else if (player.level().isClientSide && supplier == player) prototypes = client.get();
        else return nativeSource;
        prototypes = prototypes.stream().filter(s -> !(s.getItem() instanceof AmmoBoxItem)).map(ItemStack::copy).toList();
        if (prototypes.isEmpty()) return nativeSource;
        SUPPLIED.add(gun);
        var extra = new CacheSource(gun, supplier, player, prototypes);
        return nativeSource == null ? extra : new Sources(nativeSource, extra);
    }
    private record Sources(IItemHandler nativeSource,IItemHandler cache) implements IItemHandler {
        private IItemHandler source(int slot) { return slot<nativeSource.getSlots()?nativeSource:cache; }
        private int index(int slot) { return slot<nativeSource.getSlots()?slot:slot-nativeSource.getSlots(); }
        public int getSlots() { return nativeSource.getSlots()+cache.getSlots(); }
        public ItemStack getStackInSlot(int slot) { return source(slot).getStackInSlot(index(slot)); }
        public ItemStack extractItem(int slot,int amount,boolean simulate) { return source(slot).extractItem(index(slot),amount,simulate); }
        public ItemStack insertItem(int slot,ItemStack stack,boolean simulate) { return source(slot).insertItem(index(slot),stack,simulate); }
        public int getSlotLimit(int slot) { return source(slot).getSlotLimit(index(slot)); }
        public boolean isItemValid(int slot,ItemStack stack) { return source(slot).isItemValid(index(slot),stack); }
    }
    private record CacheSource(GunData gun, Entity supplier, Player player, List<ItemStack> prototypes) implements IItemHandler {
        public int getSlots() { return prototypes.size(); }
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= prototypes.size() || operator(gun, supplier) != player) return ItemStack.EMPTY;
            var prototype = prototypes.get(slot);
            if (player instanceof ServerPlayer server) {
                var tx = MaterialTransaction.open(server).orElse(null);
                int count = tx == null ? 0 : tx.cacheAvailable(prototype);
                return count > 0 ? prototype.copyWithCount(count) : ItemStack.EMPTY;
            }
            return prototype.copy();
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var current = getStackInSlot(slot); int taken = Math.min(Math.max(0, amount), current.getCount());
            if (taken == 0) return ItemStack.EMPTY;
            if (simulate) return current.copyWithCount(taken);
            if (!(player instanceof ServerPlayer server)) return ItemStack.EMPTY;
            var tx = MaterialTransaction.open(server).orElse(null);
            if (tx == null || operator(gun, supplier) != player || !tx.takeCache(current, taken) || !tx.commit()) return ItemStack.EMPTY;
            return current.copyWithCount(taken);
        }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        public int getSlotLimit(int slot) { return Integer.MAX_VALUE; }
        public boolean isItemValid(int slot, ItemStack stack) { return false; }
    }
}
