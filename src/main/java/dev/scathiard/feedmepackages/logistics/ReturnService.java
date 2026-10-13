package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.CacheEdit;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.domain.MaterialVariant;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.server.level.ServerLevel;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import dev.scathiard.feedmepackages.storage.ReturnEscrow;
import dev.scathiard.feedmepackages.interaction.InventoryTransfer;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Return surplus in legal package-sized batches, with one coherent cache debit. */
public final class ReturnService {
    private static final Item CARD = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
    private static final Item BEE = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));
    private static final Item PHANTOM = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createphantom:mini_phantom"));
    private static final java.util.Set<UUID> OPERATING = new java.util.HashSet<>();
    public static boolean operating(net.minecraft.server.MinecraftServer server, UUID cache) { return server.isSameThread() && OPERATING.contains(cache); }
    private static final int PACKAGE_SLOTS = 9;
    private record Carrier(int inventorySlot, int cacheSlot) {}
    public static final int RETURN_UNKNOWN = 0;
    public static final int RETURN_FAILED = 1;
    public static final int RETURN_SENT = 2;
    public static final int RETURN_UNCERTAIN = 3;

    private ReturnService() {}

    public static void clearTransient() {
        ReturnDispatchState.clear(); OPERATING.clear();
    }

    public static void forget(ServerPlayer player) { forget((LivingEntity)player); }
    public static void forget(LivingEntity bearer) { ReturnDispatchState.forget(bearer.getUUID()); }

    public static int dispatchState(CacheHandle handle, String address, int slot, String filter, int maximum) {
        return ReturnDispatchState.state(handle, address, slot, filter, maximum);
    }

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 40 == 0) check(player);
    }

    public static void check(ServerPlayer player) { check(LogisticsEndpoint.player(player)); }

    public static void check(LogisticsEndpoint endpoint) {
        var bearer = endpoint.entity();
        if (!(bearer.level() instanceof ServerLevel) || !bearer.getServer().isSameThread()) return;
        resumeRejected(endpoint);
        AccessGate.Result access = endpoint.access();
        if (!access.active()) return;
        CacheHandle handle = access.handle();
        if (handle.networkId() == null || !endpoint.mayRequest(handle.networkId()) || CraftingReservations.operating(handle.cacheId()) || FluidActions.operating(handle.cacheId())) {
            ReturnDispatchState.reset(handle);
            return;
        }
        CacheLedger ledger = CacheLedger.get(bearer.getServer());
        String address = ledger.returnAddress(handle.cacheId());
        CacheRecord record = ledger.find(handle.cacheId());
        if (record == null) {
            ReturnDispatchState.reset(handle);
            return;
        }
        int slots = record.state().cells().size();
        ReturnDispatchState.ensureContext(handle, address, slots);
        for (int slot = 0; slot < slots; slot++) {
            AccessGate.Result current = endpoint.access();
            if (!current.active() || !current.handle().equals(handle)) return;
            CacheRecord live = ledger.find(handle.cacheId());
            if (live == null || slot >= live.state().cells().size()) {
                ReturnDispatchState.reset(handle);
                return;
            }
            Cell<? extends MaterialVariant> cell = live.fluids().state().cells().get(slot).filter() != null
                    ? live.fluids().state().cells().get(slot) : live.state().cells().get(slot);
            String filter = cell.filter() == null ? "" : encoded(cell.filter());
            if (cell.filter() == null || cell.settings().upper() < 0) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), RETURN_UNKNOWN);
                continue;
            }
            int overage = Math.max(0, cell.amount() - cell.settings().upper());
            if (overage == 0) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), RETURN_UNKNOWN);
                continue;
            }
            if (address == null || address.isEmpty()) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), RETURN_FAILED);
                continue;
            }
            // Preserve plane-first preference. A rejected attempt changes no FMP stock
            // and the bee receives its own newly built package, never the plane's object.
            int result = dispatch(endpoint, handle, ledger, slot, CARD, address);
            if (result == RETURN_FAILED && ledger.returnEscrow(handle.cacheId()) == null) result = dispatch(endpoint, handle, ledger, slot, BEE, address);
            if (phantomDispatcher(bearer) && result == RETURN_FAILED && ledger.returnEscrow(handle.cacheId()) == null)
                result = dispatch(endpoint, handle, ledger, slot, PHANTOM, address);
            ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), result);
        }
    }

    private static int dispatch(LogisticsEndpoint endpoint, CacheHandle handle, CacheLedger ledger,
            int slot, Item carrierItem, String address) {
        var bearer = endpoint.entity(); var inventory = new CarrierInventory(bearer);
        if (carrierItem == Items.AIR) return RETURN_FAILED;
        CacheRecord record = ledger.find(handle.cacheId());
        if (record == null || slot >= record.state().cells().size()) return RETURN_FAILED;
        boolean fluid = record.fluids().state().cells().get(slot).filter() != null;
        CacheState<? extends MaterialVariant> state = fluid ? record.fluids().state() : record.state();
        Cell<? extends MaterialVariant> cell = state.cells().get(slot);
        if (!state.writable(slot) || cell.filter() == null || cell.settings().upper() < 0) return RETURN_UNKNOWN;
        int overage = Math.max(0, cell.amount() - cell.settings().upper());
        if (overage == 0) return RETURN_UNKNOWN;
        Carrier carrier = findCarrier(bearer, handle, record.state(), carrierItem);
        if (carrier == null) return RETURN_FAILED;

        int available = (int)Math.max(0, cell.amount() - (fluid ? FluidActions.reserved(handle.cacheId(), slot)
                : CraftingReservations.reservedCache(handle.cacheId(), slot, null)));
        if (!fluid && carrier.cacheSlot() == slot) available--; // A carrier cannot also be its own cargo.
        int amount = Math.min(Math.min(overage, available), fluid ? FluidPackages.capacity() : PACKAGE_SLOTS * cell.filter().stackSize());
        if (amount <= 0) return RETURN_FAILED;
        ItemStack box = buildBox(bearer, cell.filter(), amount, address);
        if (box == null) return RETURN_FAILED;

        ItemStack carrierStack = carrier.inventorySlot() >= 0 ? inventory.get(carrier.inventorySlot())
                : record.state().cells().get(carrier.cacheSlot()).filter().stack(bearer.registryAccess(), 1);
        var access = endpoint.access();
        if (!access.active() || !handle.equals(access.handle()) || ledger.find(handle.cacheId()) != record
                || !address.equals(ledger.returnAddress(handle.cacheId())) || carrierStack.isEmpty() || !carrierStack.is(carrierItem))
            return RETURN_FAILED;
        var hold = new ReturnEscrow(UUID.randomUUID(), handle, state.revision(), slot, cell.filter(), amount,
                carrierStack.copyWithCount(1), carrier.inventorySlot(), carrier.cacheSlot(), carrierItem == CARD ? "paper" : carrierItem == BEE ? "bee" : "phantom", address, false, bearer instanceof TamableAnimal pet ? pet.getOwnerUUID() : null);
        ledger.beginReturn(hold, record, bearer.registryAccess());
        // The real carrier moves to escrow before any foreign callback can spend or replace it.
        if (carrier.inventorySlot() >= 0) {
            inventory.debit(carrier.inventorySlot());
        }
        TransportDispatch.Result result;
        OPERATING.add(handle.cacheId());
        try {
            result = carrierItem == CARD ? TransportDispatch.paperPlane((ServerLevel)bearer.level(), box.copy(), bearer.position())
                    : carrierItem == BEE ? TransportDispatch.bee((ServerLevel)bearer.level(), box.copy(), bearer.blockPosition(), handle.networkId(),
                            bearer instanceof TamableAnimal pet && dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(pet) ? pet.getUUID() : null)
                    : bearer instanceof ServerPlayer phantomSender
                            ? dev.scathiard.feedmepackages.compat.PhantomDispatch.send(phantomSender, box.copy())
                            : dev.scathiard.feedmepackages.compat.PhantomDispatch.sendForMaid(bearer, box.copy());
        } finally { OPERATING.remove(handle.cacheId()); }
        if (result.outcome() == TransportDispatch.Outcome.SENT) {
            ledger.finishReturn(hold, bearer.registryAccess());
            LogisticsSignals.publish(handle.cacheId(), slot, dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.DEPARTURE);
            return RETURN_SENT;
        }
        if (result.outcome() == TransportDispatch.Outcome.NOT_SENT) {
            var rejected = ledger.rejectReturn(hold);
            refundCarrier(bearer, ledger, rejected);
            return RETURN_FAILED;
        }
        FeedMePackages.LOGGER.error("FMP return handoff {} via {} is uncertain; cache {} and its carrier are held, not resent or charged",
                hold.id(), hold.transport(), handle.cacheId(), result.failure());
        return RETURN_UNCERTAIN;
    }

    /** A rejected handoff may owe a carrier if foreign code occupied every real inventory slot.
     * Recovery never launches another parcel and never treats an uncertain result as rejection. */
    public static void resumeRejected(ServerPlayer player) { resumeRejected(LogisticsEndpoint.player(player)); }
    private static void resumeRejected(LogisticsEndpoint endpoint) {
        var access = endpoint.access();
        if (access.status() != AccessGate.Status.RETURN_REFUND || access.handle() == null) return;
        var ledger = CacheLedger.get(endpoint.entity().getServer()); var hold = ledger.returnEscrow(access.handle().cacheId());
        if (hold != null) refundCarrier(endpoint.entity(), ledger, hold);
    }
    /**
     * Phantom carriers may be launched by a real player, or by one of our maids - she launches from her
     * own position and her owner supplies the native task's player identity. No fake player is involved.
     */
    private static boolean phantomDispatcher(LivingEntity bearer) {
        return bearer instanceof ServerPlayer
                || (bearer instanceof TamableAnimal pet && dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(pet));
    }
    private static boolean originalBearer(LivingEntity bearer, ReturnEscrow hold) {
        if (!bearer.getUUID().equals(hold.handle().playerId()) || !bearer.isAlive() || bearer.isRemoved()) return false;
        return hold.maidSource() ? MaidCompatibility.isMaid(bearer) && bearer instanceof TamableAnimal pet
                && hold.maidOwner().equals(pet.getOwnerUUID()) : bearer instanceof ServerPlayer;
    }
    private static boolean refundCarrier(LivingEntity bearer, CacheLedger ledger, ReturnEscrow hold) {
        if (!hold.rejected() || ledger.returnEscrow(hold.handle().cacheId()) != hold || !originalBearer(bearer, hold)) return false;
        if (hold.inventorySlot() >= 0 && !new CarrierInventory(bearer).refund(hold.inventorySlot(), hold.carrier())) return false;
        ledger.cancelRejectedReturn(hold);
        return true;
    }
    /** Recovery never redirects a held carrier to a different bearer or the maid's owner inventory. */
    static boolean retryRefund(net.minecraft.server.MinecraftServer server, ReturnEscrow hold) {
        var ledger = CacheLedger.get(server);
        if (!hold.rejected() || ledger.returnEscrow(hold.handle().cacheId()) != hold) return false;
        if (hold.inventorySlot() < 0) { ledger.cancelRejectedReturn(hold); return true; }
        if (!hold.maidSource()) {
            var player = server.getPlayerList().getPlayer(hold.handle().playerId());
            return player != null && refundCarrier(player, ledger, hold);
        }
        for (var level : server.getAllLevels())
            if (level.getEntity(hold.handle().playerId()) instanceof LivingEntity maid) return refundCarrier(maid, ledger, hold);
        return false;
    }

    /** The backing native slots remain authoritative; player slot ordering and reservations stay unchanged. */
    private record CarrierInventory(LivingEntity bearer) {
        private ServerPlayer player() { return bearer instanceof ServerPlayer player ? player : null; }
        int size() { return player() != null ? player().getInventory().getContainerSize() : MaidCompatibility.inventory(bearer).getSlots(); }
        ItemStack get(int slot) { return player() != null ? player().getInventory().getItem(slot) : MaidCompatibility.inventory(bearer).getStackInSlot(slot); }
        long reserved(int slot) { return player() != null ? CraftingReservations.reservedInventory(player(), slot) : 0; }
        void debit(int slot) {
            if (player() != null) { get(slot).shrink(1); player().getInventory().setChanged(); }
            else MaidCompatibility.inventory(bearer).setStackInSlot(slot, get(slot).copyWithCount(get(slot).getCount() - 1));
        }
        boolean refund(int original, ItemStack offered) {
            if (player() != null) {
                var inventory = player().getInventory(); var actual = inventory.getItem(original);
                if (actual.isEmpty()) inventory.setItem(original, offered);
                else if (ItemStack.isSameItemSameComponents(actual, offered)
                        && actual.getCount() < Math.min(actual.getMaxStackSize(), inventory.getMaxStackSize())) actual.grow(1);
                else {
                    var plan = InventoryTransfer.insert(inventory, offered, 1);
                    if (plan.moved() != 1 || !plan.stillValid(inventory)) return false;
                    plan.commit(inventory);
                }
                inventory.setChanged(); return true;
            }
            var inventory = MaidCompatibility.inventory(bearer);
            // Prefer the exact source, then search only this maid's available backpack. No equipment or drops.
            for (int pass = -1; pass < inventory.getSlots(); pass++) {
                int slot = pass < 0 ? original : pass;
                if (slot < 0 || slot >= inventory.getSlots() || pass >= 0 && slot == original) continue;
                var actual = inventory.getStackInSlot(slot);
                if (!inventory.isItemValid(slot, offered) || !actual.isEmpty() && !ItemStack.isSameItemSameComponents(actual, offered)
                        || actual.getCount() >= Math.min(offered.getMaxStackSize(), inventory.getSlotLimit(slot))) continue;
                inventory.setStackInSlot(slot, offered.copyWithCount(actual.getCount() + 1));
                return true;
            }
            return false;
        }
    }
    static void recovered(ReturnEscrow hold, boolean sent) {
        ReturnDispatchState.reset(hold.handle());
        if (sent) LogisticsSignals.publish(hold.handle().cacheId(),hold.slot(),dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.DEPARTURE);
    }


    private static String encoded(MaterialVariant variant) {
        return variant instanceof FluidVariantKey fluid ? "fluid:" + fluid.encoded() : ((ItemVariantKey) variant).encoded();
    }
    private static ItemStack buildBox(LivingEntity player, MaterialVariant material, int amount, String address) {
        if (material instanceof FluidVariantKey fluid) {
            try {
                var box = FluidPackages.create(fluid.stack(player.registryAccess(), amount));
                PackageItem.addAddress(box, address); return box;
            } catch (IllegalArgumentException unavailable) { return null; }
        }
        var variant = (ItemVariantKey) material;
        try {
            List<ItemStack> contents = new ArrayList<>();
            for (int remaining = amount; remaining > 0;) {
                int count = Math.min(remaining, variant.stackSize());
                contents.add(variant.stack(player.registryAccess(), count));
                remaining -= count;
            }
            if (contents.isEmpty() || contents.size() > PACKAGE_SLOTS) return null;
            ItemStack box = PackageItem.containing(contents);
            PackageItem.addAddress(box, address);
            return box;
        } catch (IllegalArgumentException invalid) {
            FeedMePackages.LOGGER.warn("FMP return package could not be built for " + variant + " x" + amount, invalid);
            return null;
        }
    }

    private static Carrier findCarrier(LivingEntity player, CacheHandle handle,
            CacheState<ItemVariantKey> state, Item item) {
        var inventory = new CarrierInventory(player);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty() && stack.is(item)
                    && (item != PHANTOM || dev.scathiard.feedmepackages.compat.PhantomDispatch.carrierUsable(stack))
                    && stack.getCount() > inventory.reserved(i))
                return new Carrier(i, -1);
        }
        try {
            ItemVariantKey key = ItemVariantKey.of(new ItemStack(item), player.registryAccess());
            int slot = state.find(key);
            if (slot >= 0 && state.cells().get(slot).amount()
                    > CraftingReservations.reservedCache(handle.cacheId(), slot, null))
                return new Carrier(-1, slot);
        } catch (IllegalArgumentException unsupportedCarrier) {
            // An optional carrier may prohibit container storage; inventory use still works.
        }
        return null;
    }
}
