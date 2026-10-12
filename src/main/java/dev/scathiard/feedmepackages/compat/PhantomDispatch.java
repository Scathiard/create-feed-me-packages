package dev.scathiard.feedmepackages.compat;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.UUID;

/** Native Create Phantom 1.0.2 tasks. No fake player, entity clone, alternate routing or replacement carrier. */
public final class PhantomDispatch {
    private PhantomDispatch() {}
    private record Api(Class<?> port, Class<?> player, Method resolve, Method accepts, Method dimension,
                       Method portPos, Method playerId, Method airportTask, Method playerTask,
                       Method addTask, Method notifyHud, Method direction, double takeoffSpeed) {}
    private static Api api;
    private static boolean attempted;
    public static synchronized boolean ready() {
        if (!ModList.get().isLoaded("createphantom")) return false;
        if (!attempted) {
            attempted = true;
            try {
                String root = "com.yision.phantom.logistics.courier.";
                var target = Class.forName(root + "AirCourierTarget");
                var port = Class.forName(root + "AirCourierTarget$PhantomPortTarget");
                var player = Class.forName(root + "AirCourierTarget$PlayerTarget");
                var service = Class.forName(root + "AirCourierDispatchService");
                var task = Class.forName(root + "AirCourierTask");
                var manager = Class.forName(root + "AirCourierTaskManager");
                var profile = Class.forName(root + "flight.AirCourierFlightProfile");
                var resolve = service.getMethod("resolvePackageTarget", ServerLevel.class, ItemStack.class, Vec3.class, ResourceKey.class, BlockPos.class);
                var accepts = service.getMethod("canReceivePackageTarget", ServerLevel.class, target, ItemStack.class);
                var airport = task.getMethod("forPackageToAirport", UUID.class, ItemStack.class, ServerLevel.class, ResourceKey.class, BlockPos.class,
                        Vec3.class, Vec3.class, Vec3.class, ResourceKey.class, BlockPos.class, UUID.class, UUID.class, UUID.class);
                var toPlayer = task.getMethod("forPackageToPlayer", UUID.class, ItemStack.class, ServerLevel.class, UUID.class, ResourceKey.class,
                        Vec3.class, Vec3.class, Vec3.class, ResourceKey.class, BlockPos.class, UUID.class, UUID.class, UUID.class);
                var add = manager.getMethod("addTask", MinecraftServer.class, task);
                if (resolve.getReturnType() != target || accepts.getReturnType() != boolean.class || airport.getReturnType() != task
                        || toPlayer.getReturnType() != task || add.getReturnType() != void.class)
                    throw new NoSuchMethodException("Phantom task contract changed");
                double speed = (double) profile.getMethod("takeoffSpeed").invoke(profile.getField("DEFAULT").get(null));
                if (!Double.isFinite(speed) || speed <= 0) throw new IllegalStateException("Invalid native takeoff speed");
                api = new Api(port, player, resolve, accepts, target.getMethod("dimension"), port.getMethod("pos"), player.getMethod("playerId"),
                        airport, toPlayer, add, Class.forName(root + "hud.AirCourierHudSync").getMethod("onCourierTaskStarted", MinecraftServer.class, task),
                        Class.forName(root + "flight.AirCourierFlightMath").getMethod("sanitizeNonNegativeDirection", Vec3.class), speed);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                FeedMePackages.LOGGER.warn("FMP Phantom returns disabled: native task API unavailable", incompatible);
            }
        }
        return api != null;
    }
    /** Native recovery recreates an ordinary carrier. Never discard an occupied or customized carrier's data. */
    public static boolean carrierUsable(ItemStack stack) {
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty() && ready();
    }
    public static TransportDispatch.Result send(ServerPlayer sender, ItemStack box) {
        if (!sender.getServer().isSameThread() || !ready()) return TransportDispatch.Result.known(false, "phantom");
        boolean entered = false;
        try {
            var level = sender.serverLevel(); var origin = sender.position().add(0, 0.01, 0);
            Object target = api.resolve.invoke(null, level, box, origin, null, null);
            if (target == null || !Boolean.TRUE.equals(api.accepts.invoke(null, level, target, box)))
                return TransportDispatch.Result.known(false, "phantom");
            var direction = (Vec3) api.direction.invoke(null, new Vec3(sender.getLookAngle().x, 0, sender.getLookAngle().z));
            // Actual 1.0.2 bytecode uses direction * native speed + 0.15F vertical lift.
            // Newer source snapshots expose a different helper; do not assume that method exists here.
            var motion = direction.scale(api.takeoffSpeed).add(0, (double) 0.15F, 0);
            Object dimension = api.dimension.invoke(target), task;
            UUID id = UUID.randomUUID(), owner = sender.getUUID();
            if (api.port.isInstance(target)) {
                task = api.airportTask.invoke(null, id, box, level, dimension, api.portPos.invoke(target), origin, direction, motion,
                        null, null, owner, id, owner);
            } else if (api.player.isInstance(target)) {
                UUID receiver = (UUID) api.playerId.invoke(target);
                if (sender.getServer().getPlayerList().getPlayer(receiver) == null) return TransportDispatch.Result.known(false, "phantom");
                task = api.playerTask.invoke(null, id, box, level, receiver, dimension, origin, direction, motion,
                        null, null, owner, id, owner);
            } else return TransportDispatch.Result.known(false, "phantom");
            if (task == null) return TransportDispatch.Result.known(false, "phantom");
            // Source stock is held by ReturnEscrow before this first externally visible handoff.
            entered = true;
            api.addTask.invoke(null, sender.getServer(), task);
            api.notifyHud.invoke(null, sender.getServer(), task);
            return TransportDispatch.Result.known(true, "phantom");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            return new TransportDispatch.Result(entered ? TransportDispatch.Outcome.UNCERTAIN : TransportDispatch.Outcome.NOT_SENT, "phantom", cause);
        }
    }
}
