package dev.scathiard.feedmepackages.compat.maid.phantom.v103;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.UUID;

/** 1.0.3 recipient bridge. Native task ticking, delivery transition, failure/drop and return scheduling remain native. */
public final class Phantom103Flight {
    private Phantom103Flight() {}
    private static final String ROOT = "com.yision.phantom.logistics.courier.";
    private static Boolean enabled;
    public static synchronized boolean enabled() {
        if (enabled != null) return enabled;
        enabled = false;
        if (!dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom103FlightCompatible()) return false;
        enabled = Holder.API != null;
        FeedMePackages.LOGGER.info("FMP phantom103 interfaces: {}", enabled ? "enabled" : "unavailable");
        return enabled;
    }

    private record Api(Method id, Method mission, Method box, Method targetId, Method targetPort,
                       Field dimension, Field sourceId, Field sourcePort, Constructor<?> flightTarget,
                       Object available, Object unavailable) {}
    private static final class Holder { static final Api API = discover(); }
    private static Api discover() {
        try {
            var task = Class.forName(ROOT + "AirCourierTask");
            var flight = Class.forName(ROOT + "AirCourierTask$FlightTarget").getDeclaredConstructor(
                    ServerLevel.class, ServerPlayer.class, Vec3.class, Vec3.class, double.class);
            flight.setAccessible(true);
            var state = Class.forName(ROOT + "AirCourierDeliveryService$TargetState");
            return new Api(task.getMethod("id"), task.getMethod("mission"), task.getMethod("box"),
                    task.getMethod("targetPlayerId"), task.getMethod("targetPhantomPortPos"),
                    field(task,"targetDimension"), field(task,"sourcePlayerId"), field(task,"sourcePhantomPortPos"),
                    flight, state.getField("AVAILABLE").get(null), state.getField("UNAVAILABLE").get(null));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            FeedMePackages.LOGGER.warn("FMP Phantom recipient interface unavailable", unavailable);
            return null;
        }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object call(Method method, Object task) {
        try { return method.invoke(task); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Phantom task access failed", error); }
    }
    private static PhantomMaidTaskMarker.Decoded marker(Object task) {
        return ((PhantomMaidTaskAccess) task).fmp$maidState().marker((UUID) call(Holder.API.id(),task));
    }
    private static boolean targetsMaid(Object task) {
        var mark = marker(task);
        if (!mark.marked() || call(Holder.API.targetPort(),task) != null) return false;
        String mission = ((Enum<?>) call(Holder.API.mission(),task)).name();
        if (mission.equals("PACKAGE_TO_PLAYER")) {
            var box = (ItemStack) call(Holder.API.box(),task);
            UUID recipient=MaidAccess.recipient(PackageItem.getAddress(box));
            return recipient!=null && recipient.equals(call(Holder.API.targetId(),task))
                    && (!mark.outbound() || recipient.equals(mark.maidId()));
        }
        return !mark.outbound() && mission.equals("CARRIER_RETURN_TO_PLAYER");
    }
    private static LivingEntity recipient(Object task, MinecraftServer server) {
        return targetsMaid(task) ? MaidPhantomTasks.maidByEntity(server, isPackage(task)
                ? (UUID) call(Holder.API.targetId(),task) : marker(task).maidId()) : null;
    }
    private static boolean isPackage(Object task) {
        return ((Enum<?>) call(Holder.API.mission(),task)).name().equals("PACKAGE_TO_PLAYER");
    }
    public static Object carrierAvailability(MinecraftServer server, Object mission, net.minecraft.core.BlockPos port, UUID id) {
        if (!enabled() || port != null || !(mission instanceof Enum<?> value)
                || !value.name().equals("CARRIER_RETURN_TO_PLAYER") || MaidPhantomTasks.maidByEntity(server,id) == null) return null;
        return Holder.API.available();
    }
    /** Null delegates to the unmodified native verdict. Unknown/broken ownership never becomes a player delivery. */
    public static Object availability(Object task, MinecraftServer server) {
        if (!enabled()) return null;
        var mark = marker(task);
        if (mark.state() == PhantomMaidTaskMarker.State.CORRUPT) return Holder.API.unavailable();
        if (!targetsMaid(task)) {
            // A saved outbound maid delivery with inconsistent identity is not a native player task.
            return mark.outbound() && ((Enum<?>) call(Holder.API.mission(),task)).name().equals("PACKAGE_TO_PLAYER")
                    ? Holder.API.unavailable() : null;
        }
        var maid = recipient(task, server);
        if (maid == null || isPackage(task) && !MaidCompatibility.canReceive(maid, (ItemStack) call(Holder.API.box(),task)))
            return Holder.API.unavailable();
        try { Holder.API.dimension().set(task, maid.level().dimension()); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        return Holder.API.available();
    }
    public static Object flightTarget(Object task, MinecraftServer server) {
        if (!enabled()) return null;
        var maid = recipient(task, server);
        if (maid == null || !(maid.level() instanceof ServerLevel level)) return null;
        var landing = maid.position().add(0, maid.getBbHeight() * .55, 0);
        try { return Holder.API.flightTarget().newInstance(level, null, landing.add(0, 4, 0), landing, .8); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    /** Null is a native destination. Never consume the task's own box: native success uses it to start the return leg. */
    public static Boolean deliver(Object task, MinecraftServer server) {
        if (!enabled() || !targetsMaid(task)) return null;
        var maid = recipient(task, server);
        if (maid == null) return false;
        if (isPackage(task)) return dev.scathiard.feedmepackages.logistics.ReceiveService.receive(
                MaidAccess.endpoint(maid), ((ItemStack) call(Holder.API.box(),task)).copy());
        return MaidCompatibility.returnCarrier(maid, MaidPhantomTasks.nativeCarrier());
    }
    /** Persisted dispatcher identity wins only on a no-port carrier return; the original station always stays native. */
    public static void prepareReturn(Object task) {
        if (!enabled()) return;
        var mark = marker(task);
        if (!mark.marked() || mark.outbound()) return;
        try {
            if (Holder.API.sourcePort().get(task) == null) Holder.API.sourceId().set(task,mark.maidId());
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
}
