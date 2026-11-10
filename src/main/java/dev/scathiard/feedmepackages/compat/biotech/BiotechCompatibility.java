package dev.scathiard.feedmepackages.compat.biotech;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** Locked native ports. No task queue, flight physics, carrier manufacture, or fake player. */
public final class BiotechCompatibility {
    private BiotechCompatibility() {}
    private static String root;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static Constructor<?> playerTarget, resolvedTarget, deliveryResult;
    private static Class<?> portTarget;
    private static Object launchMode, returnMission;
    private static Field savedData;
    private static boolean ready;
    public static boolean ready() { return ready; }
    public static void initialize() {
        if (!OptionalMixins.biotechCompatible()) {
            if (net.neoforged.fml.ModList.get().isLoaded("create_biotech"))
                FeedMePackages.LOGGER.warn("FMP Allay Courier adapter disabled: required native interfaces unavailable");
            return;
        }
        try {
            root = OptionalMixins.biotechRoot();
            var dispatch = Class.forName(root + "logistics.courier.AllayCourierDispatchService");
            var task = Class.forName(root + "logistics.courier.AllayCourierTask");
            var manager = Class.forName(root + "logistics.courier.AllayCourierTaskManager");
            var saved = Class.forName(root + "logistics.courier.AllayCourierTaskSavedData");
            var target = Class.forName(root + "logistics.courier.AllayCourierTarget");
            portTarget = Class.forName(target.getName() + "$AllayPortTarget");
            var player = Class.forName(target.getName() + "$PlayerTarget");
            var port = Class.forName(root + "block.allayport.AllayPortBlockEntity");
            var mode = Class.forName(root + "logistics.courier.AllayCourierReturnMode");
            var mission = Class.forName(root + "entity.courier.AllayCourierEntity$Mission");
            var delivery = Class.forName(root + "logistics.courier.AllayCourierDeliveryService");
            playerTarget = player.getConstructor(UUID.class, ResourceKey.class);
            resolvedTarget = task.getDeclaredClasses().length == 0 ? null : Class.forName(task.getName() + "$ResolvedTarget")
                    .getDeclaredConstructor(ServerLevel.class, port, ServerPlayer.class);
            resolvedTarget.setAccessible(true);
            deliveryResult = Class.forName(delivery.getName() + "$DeliveryResult").getConstructor(boolean.class, boolean.class, boolean.class);
            launchMode = mode.getField("DEFAULT_FOR_PLAYER_LAUNCH").get(null);
            returnMission = Arrays.stream(mission.getEnumConstants()).filter(e -> e.toString().equals("CARRIER_RETURN_TO_PLAYER")).findFirst().orElseThrow();
            bind("resolve", dispatch, "resolvePackageTarget", ServerLevel.class, ItemStack.class, Vec3.class, ResourceKey.class, BlockPos.class);
            bind("findPlayer", dispatch, "findPlayer", ServerLevel.class, String.class, ItemStack.class, Vec3.class);
            bind("distance", dispatch, "withinDeliveryDistance", ServerLevel.class, ServerLevel.class, Vec3.class, Vec3.class);
            bind("canTarget", Class.forName(root + "logistics.courier.AllayCourierDimensionRules"), "canTarget", ServerLevel.class, ResourceKey.class);
            bind("portTask", task, "forPackageToAllayPort", UUID.class, ItemStack.class, ServerLevel.class, ResourceKey.class,
                    BlockPos.class, UUID.class, Vec3.class, Vec3.class, ResourceKey.class, BlockPos.class, UUID.class, UUID.class, mode);
            bind("playerTask", task, "forPackageToPlayer", UUID.class, ItemStack.class, ServerLevel.class, UUID.class,
                    ResourceKey.class, Vec3.class, Vec3.class, ResourceKey.class, BlockPos.class, UUID.class, UUID.class, mode);
            bind("add", manager, "addTask", MinecraftServer.class, task);
            bind("hasTask", saved, "hasTask", UUID.class);
            bind("plain", Class.forName(root + "item.allaycourier.AllayCourierItem"), "isPlainCarrier", ItemStack.class);
            bind("targetPort", task, "resolveTargetAllayPort", ServerLevel.class);
            bind("reset", task, "resetForReturn", mission);
            bind("remove", task, "markRemoved");
            bind("depart", task, "beginPortDeparture", port, boolean.class);
            bind("dropCarrier", delivery, "dropCarrierOnly", ServerLevel.class, Vec3.class);
            bind("fail", delivery, "failAndDrop", ItemStack.class, mission, ServerLevel.class, Vec3.class);
            for (String name : List.of("dimension", "pos", "subLevelId")) bind("port." + name, portTarget, name);
            for (String name : List.of("dimension", "playerId")) bind("player." + name, player, name);
            for (String name : List.of("box", "mission", "returnMode", "targetDimension", "targetAllayPortPos", "targetAllayPortSubLevelId",
                    "targetPlayerId", "currentDimension", "position")) {
                var field = task.getDeclaredField(name); field.setAccessible(true); FIELDS.put(name, field);
            }
            savedData = manager.getDeclaredField("savedData"); savedData.setAccessible(true);
            ready = true;
            FeedMePackages.LOGGER.info("FMP Biotech native player/maid courier ports ready: {}", root);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            ready = false;
            FeedMePackages.LOGGER.warn("FMP Biotech courier adapter disabled: native ports changed", incompatible);
        }
    }
    private static void bind(String key, Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        var method = type.getDeclaredMethod(name, args); method.setAccessible(true); METHODS.put(key, method);
    }
    static Object call(String name, Object target, Object... args) {
        try { return METHODS.get(name).invoke(target, args); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Error error) throw error;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Native Biotech port failed: " + name, failed.getCause());
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native Biotech port unavailable: " + name, failed); }
    }
    static Object get(Object task, String name) {
        try { return FIELDS.get(name).get(task); }
        catch (IllegalAccessException failed) { throw new IllegalStateException(failed); }
    }
    static void set(Object task, String name, Object value) {
        try { FIELDS.get(name).set(task, value); }
        catch (IllegalAccessException failed) { throw new IllegalStateException(failed); }
    }
    static Object create(Constructor<?> constructor, Object... args) {
        try { return constructor.newInstance(args); }
        catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native Biotech value unavailable", failed); }
    }
    public static ItemStack carrier() { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_biotech:allay_courier"))); }
    public static boolean carrierUsable(ItemStack stack) { return ready && (boolean)call("plain", null, stack); }
    public static boolean reservedAddress(ItemStack box) {
        var address = PackageItem.getAddress(box);
        return ready && (address.startsWith("FMP@") || address.startsWith(MaidAccess.ADDRESS_PREFIX));
    }
    public static Object resolve(ServerLevel level, ItemStack box, Vec3 origin) {
        String address = PackageItem.getAddress(box);
        if (address.startsWith("FMP@")) return call("findPlayer", null, level, address.substring(4), box, origin);
        var maid = destination(level.getServer(), box);
        if (maid == null || !(boolean)call("canTarget", null, level, maid.level().dimension())
                || !(boolean)call("distance", null, level, (ServerLevel)maid.level(), maid.position(), origin)) return null;
        return create(playerTarget, maid.getUUID(), maid.level().dimension());
    }
    static LivingEntity destination(MinecraftServer server, ItemStack box) {
        var seal = box.has(FmpRegistries.FLUID_PARCEL_SEAL.get()) ? box.get(FmpRegistries.FLUID_PARCEL_SEAL.get()) : box.get(FmpRegistries.PARCEL_SEAL.get());
        if (seal == null || !seal.address().equals(PackageItem.getAddress(box))) return null;
        var maid = MaidAccess.find(server, seal.address());
        return maid != null && seal.playerId().equals(maid.getUUID()) && ReceiveService.canReceive(MaidAccess.endpoint(maid), box) ? maid : null;
    }
    public static void markTarget(Object task, ServerLevel level, ItemStack box) {
        if (!ready || !(task instanceof BiotechTaskLink link)) return;
        var maid = destination(level.getServer(), box);
        if (!(maid instanceof TamableAnimal pet)) return;
        link.fmp$target(maid.getUUID()); link.fmp$owner(pet.getOwnerUUID());
        // The native player field is not repurposed as an entity UUID. Our own field survives save/load.
        set(task, "targetPlayerId", null);
    }
    public static boolean canDispatch(LivingEntity bearer, ItemStack box) {
        return ready && bearer.level() instanceof ServerLevel level && call("resolve", null, level, box, bearer.position(), null, null) != null;
    }
    public static TransportDispatch.Result send(LivingEntity bearer, ItemStack box) {
        if (!canDispatch(bearer, box)) return result(TransportDispatch.Outcome.NOT_SENT, null);
        var level = (ServerLevel)bearer.level();
        UUID owner = bearer instanceof ServerPlayer ? bearer.getUUID() : bearer instanceof TamableAnimal pet ? pet.getOwnerUUID() : null;
        if (owner == null) return result(TransportDispatch.Outcome.NOT_SENT, null);
        UUID id = UUID.randomUUID(); boolean entered = false;
        try {
            var target = call("resolve", null, level, box, bearer.position(), null, null);
            if (target == null) return result(TransportDispatch.Outcome.NOT_SENT, null);
            // A maid's real owner is the native quota/HUD owner, never a fabricated delivery recipient.
            var task = portTarget.isInstance(target)
                    ? call("portTask", null, id, box, level, call("port.dimension", target), call("port.pos", target), call("port.subLevelId", target),
                            bearer.getEyePosition(), bearer.getLookAngle(), null, null, null, owner, launchMode)
                    : call("playerTask", null, id, box, level, call("player.playerId", target), call("player.dimension", target),
                            bearer.getEyePosition(), bearer.getLookAngle(), null, null, null, owner, launchMode);
            if (MaidCompatibility.isMaid(bearer)) {
                var link = (BiotechTaskLink)task; link.fmp$source(bearer.getUUID()); link.fmp$owner(owner);
            }
            entered = true;
            return result((boolean)call("add", null, level.getServer(), task) ? TransportDispatch.Outcome.SENT : TransportDispatch.Outcome.NOT_SENT, null);
        } catch (RuntimeException | LinkageError failed) {
            if (entered) try {
                var data = savedData.get(null);
                if (data != null && (boolean)call("hasTask", data, id)) {
                    FeedMePackages.LOGGER.error("Biotech task {} registered before a later native error; no refund or second launch", id, failed);
                    return result(TransportDispatch.Outcome.SENT, failed);
                }
            } catch (ReflectiveOperationException | RuntimeException inspection) { failed.addSuppressed(inspection); }
            return result(entered ? TransportDispatch.Outcome.UNCERTAIN : TransportDispatch.Outcome.NOT_SENT, failed);
        }
    }
    private static TransportDispatch.Result result(TransportDispatch.Outcome outcome, Throwable failure) {
        return new TransportDispatch.Result(outcome, "biotech", failure);
    }
    static Object resolved(ServerLevel level) { return create(resolvedTarget, level, null, null); }
    static Object delivered(boolean returning, boolean packageDelivered) { return create(deliveryResult, true, returning, packageDelivered); }
    static Object returnMission() { return returnMission; }
}
