package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.FmpAdvancement;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.domain.MaterialVariant;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
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
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Return surplus in legal package-sized batches, with one coherent cache debit. */
public final class ReturnService {
    private static final Item CARD = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
    private static final Item BEE = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));
    private static final Item PHANTOM = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createphantom:mini_phantom"));
    private static final Item ALLAY = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_biotech:allay_courier"));
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

    public static int dispatchState(CacheHandle handle, String address, int slot, String filter, int maximum, int minimumBatch) {
        return ReturnDispatchState.state(handle, address, slot, filter, maximum, minimumBatch);
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
        int firstItem = 0;
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
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), cell.settings().returnBatch(), RETURN_UNKNOWN);
                continue;
            }
            int overage = Math.max(0, cell.amount() - cell.settings().upper());
            if (overage < cell.settings().returnBatch()) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), cell.settings().returnBatch(), RETURN_UNKNOWN);
                continue;
            }
            if (!dev.scathiard.feedmepackages.domain.ReturnAddress.valid(address) || address.isEmpty()) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), cell.settings().returnBatch(), RETURN_FAILED);
                continue;
            }
            // Preserve plane-first preference. A rejected attempt changes no FMP stock
            // and the bee receives its own newly built package, never the plane's object.
            if (cell.filter() instanceof ItemVariantKey) {
                while (firstItem < slot && !hasItemSurplus(live, firstItem)) firstItem++;
            }
            ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), cell.settings().returnBatch(), RETURN_FAILED);
            int result = dispatch(endpoint, handle, ledger, slot, firstItem, CARD, address);
            if (result == RETURN_FAILED && ledger.returnEscrow(handle.cacheId()) == null) result = dispatch(endpoint, handle, ledger, slot, firstItem, BEE, address);
            if (phantomDispatcher(bearer) && result == RETURN_FAILED && ledger.returnEscrow(handle.cacheId()) == null)
                result = dispatch(endpoint, handle, ledger, slot, firstItem, PHANTOM, address);
            if (result == RETURN_FAILED && ledger.returnEscrow(handle.cacheId()) == null) result = dispatch(endpoint, handle, ledger, slot, firstItem, ALLAY, address);
            if (result != RETURN_SENT)
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.settings().upper(), cell.settings().returnBatch(), result);
        }
    }

    private static boolean hasItemSurplus(CacheRecord record, int slot) {
        var cell = record.state().cells().get(slot);
        return record.state().writable(slot) && cell.filter() != null && cell.settings().upper() >= 0
                && cell.amount() - cell.settings().upper() >= cell.settings().returnBatch();
    }

    private static int dispatch(LogisticsEndpoint endpoint, CacheHandle handle, CacheLedger ledger,
            int slot, int firstItem, Item carrierItem, String address) {
        var bearer = endpoint.entity(); var inventory = new CarrierInventory(bearer);
        if (carrierItem == Items.AIR) return RETURN_FAILED;
        CacheRecord record = ledger.find(handle.cacheId());
        if (record == null || slot >= record.state().cells().size()) return RETURN_FAILED;
        boolean fluid = record.fluids().state().cells().get(slot).filter() != null;
        CacheState<? extends MaterialVariant> state = fluid ? record.fluids().state() : record.state();
        Cell<? extends MaterialVariant> cell = state.cells().get(slot);
        if (!state.writable(slot) || cell.filter() == null || cell.settings().upper() < 0) return RETURN_UNKNOWN;
        int overage = Math.max(0, cell.amount() - cell.settings().upper());
        if (overage < cell.settings().returnBatch()) return RETURN_UNKNOWN;
        Carrier carrier = findCarrier(bearer, handle, record.state(), carrierItem);
        if (carrier == null) return RETURN_FAILED;

        List<ReturnEscrow.Line> extra = List.of();
        int amount;
        if (fluid) {
            amount = cell.settings().returnable(cell.amount(), available(handle, slot, cell, true, carrier),
                    FluidPackages.capacity(), 0);
            if (amount == 0) return RETURN_FAILED;
        } else {
            var lines = planItems(record, handle, carrier, firstItem);
            if (lines.isEmpty()) return RETURN_FAILED;
            var first = lines.getFirst();
            slot = first.slot();
            cell = state.cells().get(slot);
            amount = first.amount();
            extra = lines.subList(1, lines.size());
        }
        ItemStack box = buildBox(bearer, cell.filter(), amount, extra, address);
        if (box == null) return RETURN_FAILED;
        if (carrierItem == PHANTOM && !dev.scathiard.feedmepackages.compat.PhantomDispatch.canDispatch(bearer, box))
            return RETURN_FAILED;

        if (carrierItem == ALLAY && !dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility.canDispatch(bearer, box)) return RETURN_FAILED;

        ItemStack carrierStack = carrier.inventorySlot() >= 0 ? inventory.get(carrier.inventorySlot())
                : record.state().cells().get(carrier.cacheSlot()).filter().stack(bearer.registryAccess(), 1);
        var access = endpoint.access();
        if (!access.active() || !handle.equals(access.handle()) || ledger.find(handle.cacheId()) != record
                || !address.equals(ledger.returnAddress(handle.cacheId())) || carrierStack.isEmpty() || !carrierStack.is(carrierItem))
            return RETURN_FAILED;
        ReturnEscrow hold;
        try {
            hold = new ReturnEscrow(UUID.randomUUID(), handle, state.revision(), slot, cell.filter(), amount,
                    carrierStack.copyWithCount(1), carrier.inventorySlot(), carrier.cacheSlot(), carrierItem == CARD ? "paper" : carrierItem == BEE ? "bee" : carrierItem == PHANTOM ? "phantom" : "biotech", address, false, bearer instanceof TamableAnimal pet ? pet.getOwnerUUID() : null, extra);
        } catch (IllegalArgumentException refused) {
            // A combination the escrow refuses must never take the server tick down with it: refuse this dispatch
            // instead, leaving stock, carrier and escrow untouched. (User crash, maid + phantom, 2026-10-13.)
            FeedMePackages.LOGGER.error("FMP return dispatch refused by the escrow: cache={} transport={} reason={}",
                    handle.cacheId(), carrierItem == CARD ? "paper" : carrierItem == BEE ? "bee" : carrierItem == PHANTOM ? "phantom" : "biotech", refused.getMessage());
            return RETURN_FAILED;
        }
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
                    : carrierItem == ALLAY ? dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility.send(bearer, box.copy())
                    : bearer instanceof ServerPlayer phantomSender
                            ? dev.scathiard.feedmepackages.compat.PhantomDispatch.send(phantomSender, box.copy())
                            : dev.scathiard.feedmepackages.compat.PhantomDispatch.sendForMaid(bearer, box.copy());
        } finally { OPERATING.remove(handle.cacheId()); }
        if (result.outcome() == TransportDispatch.Outcome.SENT) {
            ledger.finishReturn(hold, bearer.registryAccess());
            publishDeparture(hold);
            rememberSent(handle, slot, cell);
            for (var line : extra) rememberSent(handle, line.slot(), state.cells().get(line.slot()));
            if (bearer instanceof ServerPlayer player) FmpAdvancement.AUTO_RETURN.award(player);
            else FmpAdvancement.maidWorked(bearer);
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
    private record CarrierInventory(LivingEntity bearer, IItemHandlerModifiable maidSlots) {
        CarrierInventory(LivingEntity bearer) {
            // Native backpack-first order preserves existing backpack slot indices, then appends both hands.
            this(bearer, bearer instanceof ServerPlayer ? null : MaidCompatibility.pickupInventory(bearer));
        }
        private ServerPlayer player() { return bearer instanceof ServerPlayer player ? player : null; }
        int size() { return player() != null ? player().getInventory().getContainerSize() : maidSlots.getSlots(); }
        ItemStack get(int slot) { return player() != null ? player().getInventory().getItem(slot) : maidSlots.getStackInSlot(slot); }
        long reserved(int slot) {
            if (player() != null) return CraftingReservations.reservedInventory(player(), slot);
            return bearer.isUsingItem() && get(slot) == bearer.getItemInHand(bearer.getUsedItemHand()) ? get(slot).getCount() : 0;
        }
        void debit(int slot) {
            if (player() != null) { get(slot).shrink(1); player().getInventory().setChanged(); }
            else maidSlots.setStackInSlot(slot, get(slot).copyWithCount(get(slot).getCount() - 1));
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
            var inventory = maidSlots;
            // Prefer the exact source, then this maid's backpack and hands. Never overwrite an active use.
            for (int pass = -1; pass < inventory.getSlots(); pass++) {
                int slot = pass < 0 ? original : pass;
                if (slot < 0 || slot >= inventory.getSlots() || pass >= 0 && slot == original || reserved(slot) > 0) continue;
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
        if (sent) publishDeparture(hold);
    }


    private static String encoded(MaterialVariant variant) {
        return variant instanceof FluidVariantKey fluid ? "fluid:" + fluid.encoded() : ((ItemVariantKey) variant).encoded();
    }
    private static int available(CacheHandle handle, int slot, Cell<?> cell, boolean fluid, Carrier carrier) {
        long reserved = fluid ? FluidActions.reserved(handle.cacheId(), slot)
                : CraftingReservations.reservedCache(handle.cacheId(), slot, null);
        return (int) Math.max(0L, cell.amount() - reserved - (!fluid && carrier.cacheSlot() == slot ? 1 : 0));
    }

    private static List<ReturnEscrow.Line> planItems(CacheRecord record, CacheHandle handle, Carrier carrier, int first) {
        int freeSlots = PACKAGE_SLOTS;
        var lines = new ArrayList<ReturnEscrow.Line>();
        var state = record.state();
        for (int slot = first; freeSlots > 0 && slot < state.capacity().slots(); slot++) {
            var cell = state.cells().get(slot);
            if (!state.writable(slot) || cell.filter() == null || cell.settings().upper() < 0) continue;
            int stack = cell.filter().stackSize();
            int amount = cell.settings().returnable(cell.amount(), available(handle, slot, cell, false, carrier),
                    freeSlots * stack, carrier.cacheSlot() == slot ? 1 : 0);
            if (amount == 0) continue;
            lines.add(new ReturnEscrow.Line(slot, cell.filter(), amount));
            freeSlots -= Math.ceilDiv(amount, stack);
        }
        return lines;
    }

    private static void rememberSent(CacheHandle handle, int slot, Cell<? extends MaterialVariant> cell) {
        ReturnDispatchState.rememberCell(handle, slot, encoded(cell.filter()), cell.settings().upper(), cell.settings().returnBatch(), RETURN_SENT);
    }

    private static void publishDeparture(ReturnEscrow hold) {
        var kind = dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.DEPARTURE;
        LogisticsSignals.publish(hold.handle().cacheId(), hold.slot(), kind);
        for (var line : hold.extra()) LogisticsSignals.publish(hold.handle().cacheId(), line.slot(), kind);
    }

    private static ItemStack buildBox(LivingEntity bearer, MaterialVariant first, int amount,
            List<ReturnEscrow.Line> extra, String address) {
        try {
            ItemStack box;
            if (first instanceof FluidVariantKey fluid) {
                box = FluidPackages.create(fluid.stack(bearer.registryAccess(), amount));
            } else {
                var contents = new net.neoforged.neoforge.items.ItemStackHandler(PACKAGE_SLOTS);
                appendStacks(contents, bearer, (ItemVariantKey) first, amount);
                for (var line : extra) appendStacks(contents, bearer, line.variant(), line.amount());
                box = PackageItem.containing(contents);
            }
            PackageItem.addAddress(box, address);
            return box;
        } catch (IllegalArgumentException invalid) {
            FeedMePackages.LOGGER.warn("FMP could not pack return cargo for {}", address, invalid);
            return null;
        }
    }

    private static void appendStacks(net.neoforged.neoforge.items.ItemStackHandler contents, LivingEntity bearer,
            ItemVariantKey variant, int amount) {
        for (int remaining = amount; remaining > 0;) {
            int count = Math.min(remaining, variant.stackSize());
            var stack = variant.stack(bearer.registryAccess(), count);
            var remainder = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(contents, stack, false);
            if (!remainder.isEmpty()) throw new IllegalArgumentException("Create parcel did not accept its complete manifest");
            remaining -= count;
        }
    }

    private static Carrier findCarrier(LivingEntity player, CacheHandle handle,
            CacheState<ItemVariantKey> state, Item item) {
        var inventory = new CarrierInventory(player);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty() && stack.is(item)
                    && (item != PHANTOM || dev.scathiard.feedmepackages.compat.PhantomDispatch.carrierUsable(stack))
                    && (item != ALLAY || dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility.carrierUsable(stack))
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
