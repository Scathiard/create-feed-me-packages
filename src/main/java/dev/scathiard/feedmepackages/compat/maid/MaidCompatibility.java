package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Native shortage events stage a real stack; the maid still owns eating, containers and gun behavior. */
public final class MaidCompatibility {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static Class<?> maidType, ammoType;
    private static Method backpack, availableInventory, curiosEnabled, eventMaid, eventFilter, eventLimit, eventItem, setEventItem;
    private static boolean ready, initialized;
    private static final Set<UUID> TRANSFERRING = new HashSet<>();
    private MaidCompatibility() {}

    public static boolean ready() { return ready; }
    public static boolean isMaid(Object entity) { return ready && maidType.isInstance(entity); }
    public static boolean enabled() {
        if (!ready || !FmpConfig.maidEnabled()) return false;
        try { return (boolean) curiosEnabled.invoke(null); }
        catch (ReflectiveOperationException broken) { throw failure(broken); }
    }
    public static void initialize() {
        if (initialized) return; initialized = true;
        if (!ModList.get().isLoaded("touhou_little_maid")) return;
        try {
            maidType = Class.forName(ROOT + "entity.passive.EntityMaid");
            backpack = maidType.getMethod("getAvailableBackpackInv");
            availableInventory = maidType.getMethod("getAvailableInv", boolean.class);
            if (!IItemHandlerModifiable.class.isAssignableFrom(backpack.getReturnType()))
                throw new NoSuchMethodException("Maid backpack is not a native modifiable handler");
            curiosEnabled = Class.forName(ROOT + "compat.curios.CuriosCompat").getMethod("isLoadedOrEnable");
            Class<? extends Event> event = Class.forName(ROOT + "api.event.MaidRequestItemEvent").asSubclass(Event.class);
            eventMaid = event.getMethod("getMaid"); eventFilter = event.getMethod("getItemFilter");
            eventLimit = event.getMethod("getMaxCount"); eventItem = event.getMethod("getRequestedItem");
            setEventItem = event.getMethod("setRequestedItem", ItemStack.class);
            if (ModList.get().isLoaded("tacz")) ammoType = Class.forName("com.tacz.guns.api.item.IAmmo");
            register(event);
            NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post e) -> {
                if (e.getEntity() instanceof LivingEntity maid && !maid.level().isClientSide && isMaid(maid)) tick(maid);
            });
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) -> {
                if (!e.getLevel().isClientSide() && e.getEntity() instanceof LivingEntity maid && isMaid(maid))
                    dev.scathiard.feedmepackages.logistics.ReturnService.forget(maid);
            });
            // Handing a package back to its maid (user 2026-10-13): server side, owner's own maid, and only
            // when the held stack is a package addressed to her - every other interaction stays native.
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false,
                    net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract.class,
                    MaidCompatibility::handover);
            ready = true;
            MaidCouriers.initialize();
            FeedMePackages.LOGGER.info("FMP maid native shortage source registered");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            ready = false;
            FeedMePackages.LOGGER.warn("FMP maid compatibility disabled: native event API unavailable", incompatible);
        }
    }
    private static <E extends Event> void register(Class<E> event) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, event, MaidCompatibility::request);
    }
    /** Right-click my own maid while holding a package addressed to her: unpack it into her cache. */
    private static void handover(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (!(event.getTarget() instanceof net.minecraft.world.entity.TamableAnimal pet) || !isMaid(pet)
                || !pet.isOwnedBy(player) || pet.level() != player.level()) return;
        ItemStack held = event.getItemStack();
        if (!MaidPackageHandover.addressedTo(held, pet)) return;
        if (MaidPackageHandover.deliver(player, pet, held) > 0) event.setCanceled(true);
    }
    private static void request(Event event) {
        try {
            if (!enabled() || !((ItemStack) eventItem.invoke(event)).isEmpty()) return;
            LivingEntity maid = (LivingEntity) eventMaid.invoke(event);
            @SuppressWarnings("unchecked") var filter = (Predicate<ItemStack>) eventFilter.invoke(event);
            ItemStack moved = supply(maid, filter, (int) eventLimit.invoke(event));
            if (!moved.isEmpty()) setEventItem.invoke(event, moved);
        } catch (ReflectiveOperationException broken) { throw failure(broken); }
    }
    public static IItemHandlerModifiable inventory(LivingEntity maid) {
        if (!isMaid(maid)) throw new IllegalArgumentException("Not a supported maid");
        try { return (IItemHandlerModifiable) backpack.invoke(maid); }
        catch (ReflectiveOperationException broken) { throw failure(broken); }
    }
    private static boolean consumable(LivingEntity maid, ItemStack stack) {
        return stack.getFoodProperties(maid) != null || stack.getItem() instanceof ArrowItem || stack.is(Items.FIREWORK_ROCKET)
                || FmpConfig.taczAmmoEnabled() && ammoType != null && ammoType.isInstance(stack.getItem());
    }
    public static ItemStack supply(LivingEntity maid, Predicate<ItemStack> accepts, int limit) {
        var access = MaidAccess.resolve(maid);
        if (!access.active() || limit == 0 || (CraftingReservations.operating(access.handle().cacheId()) || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId()))
                || !TRANSFERRING.add(access.handle().cacheId())) return ItemStack.EMPTY;
        try {
            var inv = inventory(maid); var beforeInventory = new ArrayList<ItemStack>();
            for (int i = 0; i < inv.getSlots(); i++) {
                var stack = inv.getStackInSlot(i); beforeInventory.add(stack.copy());
                if (!stack.isEmpty() && accepts.test(stack.copy())) return ItemStack.EMPTY;
            }
            var ledger = CacheLedger.get(maid.getServer()); var before = ledger.find(access.handle().cacheId());
            for (int cell = 0; cell < before.state().cells().size(); cell++) {
                var stock = before.state().cells().get(cell);
                long available = stock.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), cell, null);
                if (stock.filter() == null || available <= 0) continue;
                var item = stock.filter().stack(maid.registryAccess(), 1);
                if (item.isEmpty() || !consumable(maid, item) || !accepts.test(item.copy())) continue;
                int maximum = limit < 0 ? item.getMaxStackSize() : Math.min(limit, item.getMaxStackSize());
                int amount = (int) Math.min(available, maximum);
                if (amount <= 0) continue;
                for (int slot = 0; slot < inv.getSlots(); slot++) {
                    if (!inv.getStackInSlot(slot).isEmpty()) continue;
                    ItemStack offered = item.copyWithCount(Math.min(amount, inv.getSlotLimit(slot)));
                    if (offered.isEmpty() || !inv.isItemValid(slot, offered) || !inv.insertItem(slot, offered.copy(), true).isEmpty()) continue;
                    var again = MaidAccess.resolve(maid);
                    if (!again.active() || !access.handle().equals(again.handle()) || ledger.find(access.handle().cacheId()) != before
                            || inventory(maid).getSlots() != beforeInventory.size()
                            || (CraftingReservations.operating(access.handle().cacheId()) || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId()))
                            || stock.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), cell, null) < offered.getCount())
                        return ItemStack.EMPTY;
                    for (int i = 0; i < beforeInventory.size(); i++)
                        if (!ItemStack.matches(beforeInventory.get(i), inv.getStackInSlot(i))) return ItemStack.EMPTY;
                    var edit = before.state().edit(); edit.extract(cell, offered.getCount());
                    ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
                    // Native MaidBackpackHandler.setStackInSlot only updates its stack and backpack display.
                    // It is not giveItemToMaid: the latter drops overflow and cannot serve a transaction.
                    inv.setStackInSlot(slot, offered.copy());
                    return offered;
                }
            }
            return ItemStack.EMPTY;
        } finally { TRANSFERRING.remove(access.handle().cacheId()); }
    }
    public static void tick(LivingEntity maid) {
        if (maid.tickCount % 40 == 0) dev.scathiard.feedmepackages.logistics.ReturnService.check(MaidAccess.endpoint(maid));
        var access = MaidAccess.resolve(maid); if (!access.active()) return;
        MaidCouriers.refresh(maid);
        try {
            var available = (IItemHandlerModifiable) availableInventory.invoke(maid, true);
            for (int slot = 0; slot < available.getSlots(); slot++)
                if (dev.scathiard.feedmepackages.logistics.ReceiveService.receive(MaidAccess.endpoint(maid), available.getStackInSlot(slot)))
                    available.setStackInSlot(slot, ItemStack.EMPTY);
        } catch (ReflectiveOperationException broken) { throw failure(broken); }
        SupplyService.tick(MaidAccess.endpoint(maid));
    }
    /** Give one stack back to the maid's own inventory; false when it did not fit entirely. */
    public static boolean insertInto(LivingEntity maid, ItemStack stack) {
        if (!ready || !isMaid(maid) || stack.isEmpty()) return false;
        try {
            var available = (IItemHandlerModifiable) availableInventory.invoke(maid, true);
            ItemStack rest = stack.copy();
            for (int slot = 0; slot < available.getSlots() && !rest.isEmpty(); slot++)
                rest = available.insertItem(slot, rest, false);
            return rest.isEmpty();
        } catch (ReflectiveOperationException broken) { throw failure(broken); }
    }
    /**
     * Read-only answer to "could she take this stack?", in the same order the real path uses: <b>her inventory,
     * then her cache</b>. Simulation only - no ledger write, no NBT, no packet, no state change at all, because the
     * caller is a predicate (Create Phantom's {@code canReceivePackageTarget} branch).
     */
    public static boolean canReceive(LivingEntity maid, ItemStack stack) {
        if (!ready || !isMaid(maid) || stack == null || stack.isEmpty()) return false;
        ItemStack rest = stack.copy();
        try {
            var available = (IItemHandlerModifiable) availableInventory.invoke(maid, true);
            for (int slot = 0; slot < available.getSlots() && !rest.isEmpty(); slot++)
                rest = available.insertItem(slot, rest, true);
        } catch (ReflectiveOperationException broken) { throw failure(broken); }
        if (rest.isEmpty()) return true;
        return cacheRoom(maid, rest);
    }

    /**
     * Cache half of {@link #canReceive}: a cell she already filters, or a writable empty cell, can take it. Pure
     * reads of the typed ledger - the same predicate the deposit path uses, without its write half.
     */
    private static boolean cacheRoom(LivingEntity maid, ItemStack stack) {
        var access = MaidAccess.resolve(maid);
        if (!access.active() || maid.getServer() == null) return false;
        var record = CacheLedger.get(maid.getServer()).find(access.handle().cacheId());
        if (record == null) return false;
        dev.scathiard.feedmepackages.item.ItemVariantKey key;
        try { key = dev.scathiard.feedmepackages.item.ItemVariantKey.of(stack, maid.registryAccess()); }
        catch (IllegalArgumentException unsupported) { return false; }
        if (record.state().find(key) >= 0) return true;
        for (int slot = 0; slot < record.state().cells().size(); slot++)
            if (record.state().writable(slot) && record.state().cells().get(slot).filter() == null) return true;
        return false;
    }
    static RuntimeException failure(ReflectiveOperationException problem) {
        Throwable cause = problem instanceof InvocationTargetException wrapped ? wrapped.getCause() : problem;
        if (cause instanceof Error error) throw error;
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException("Maid native API failed", cause);
    }
}
