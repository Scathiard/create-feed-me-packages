package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.lang.reflect.*;
import java.util.*;

/** Native inventory access and logistics. Consumption sources are scoped to their actual native operations. */
public final class MaidCompatibility {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static Class<?> maidType;
    private static Method backpack, availableInventory, curiosEnabled;
    private static boolean ready, initialized;
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
            FeedMePackages.LOGGER.info("FMP maid native inventory ports registered");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            ready = false;
            FeedMePackages.LOGGER.warn("FMP maid compatibility disabled: native event API unavailable", incompatible);
        }
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
    public static IItemHandlerModifiable inventory(LivingEntity maid) {
        if (!isMaid(maid)) throw new IllegalArgumentException("Not a supported maid");
        try { return (IItemHandlerModifiable) backpack.invoke(maid); }
        catch (ReflectiveOperationException broken) { throw failure(broken); }
    }
    /** Native pickup order: backpack first, then hands. Never used as a global cache capability. */
    public static IItemHandlerModifiable pickupInventory(LivingEntity maid) {
        if (!isMaid(maid)) throw new IllegalArgumentException("Not a supported maid");
        try { return (IItemHandlerModifiable) availableInventory.invoke(maid, false); }
        catch (ReflectiveOperationException broken) { throw failure(broken); }
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
    /** Give one stack back, native backpack before hands; false when it did not fit entirely. */
    public static boolean insertInto(LivingEntity maid, ItemStack stack) {
        if (!ready || !isMaid(maid) || stack.isEmpty()) return false;
        try {
            var available = (IItemHandlerModifiable) availableInventory.invoke(maid, false);
            ItemStack preview = stack.copy();
            for (int slot = 0; slot < available.getSlots() && !preview.isEmpty(); slot++)
                preview = available.insertItem(slot, preview, true);
            if (!preview.isEmpty()) return false;
            ItemStack rest = stack.copy();
            for (int slot = 0; slot < available.getSlots() && !rest.isEmpty(); slot++)
                rest = available.insertItem(slot, rest, false);
            return rest.isEmpty();
        } catch (ReflectiveOperationException broken) { throw failure(broken); }
    }

    /** One ordinary returned carrier, not an authenticated parcel. Never creates a new filter. */
    public static boolean returnCarrier(LivingEntity maid, ItemStack carrier) {
        if (carrier == null || carrier.getCount() != 1) return false;
        if (insertInto(maid, carrier)) return true;
        var access = MaidAccess.resolve(maid);
        if (!access.active() || dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(access.handle().cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId())) return false;
        var ledger = CacheLedger.get(maid.getServer());
        var before = ledger.find(access.handle().cacheId());
        dev.scathiard.feedmepackages.item.ItemVariantKey key;
        try { key = dev.scathiard.feedmepackages.item.ItemVariantKey.of(carrier, maid.registryAccess()); }
        catch (IllegalArgumentException unsupported) { return false; }
        int slot = before.state().find(key);
        if (slot < 0 || !before.state().writable(slot)) return false;
        var edit = before.state().edit();
        if (edit.insert(slot, key, 1) != 1) return false;
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        return true;
    }
    /** Authenticated package preview uses the same contents, capacity and residual rules as real receipt. */
    public static boolean canReceive(LivingEntity maid, ItemStack stack) {
        if (!ready || !isMaid(maid) || stack == null || stack.isEmpty()) return false;
        return dev.scathiard.feedmepackages.logistics.ReceiveService.canReceive(MaidAccess.endpoint(maid), stack);
    }

    static RuntimeException failure(ReflectiveOperationException problem) {
        Throwable cause = problem instanceof InvocationTargetException wrapped ? wrapped.getCause() : problem;
        if (cause instanceof Error error) throw error;
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException("Maid native API failed", cause);
    }
}
