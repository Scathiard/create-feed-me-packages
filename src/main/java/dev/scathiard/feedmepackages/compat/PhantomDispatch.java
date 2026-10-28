package dev.scathiard.feedmepackages.compat;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.core.BlockPos;
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

/** Native Create Phantom 1.0.2 tasks. No fake player, entity clone, alternate routing or replacement carrier. */
public final class PhantomDispatch {
    private PhantomDispatch() {}
    private record Api(Class<?> port, Class<?> player, Method resolve, Method accepts, Method dimension,
                       Method portPos, Method playerId, Constructor<?> playerCtor, Method airportTask, Method playerTask,
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
                // Build the native player target when a package is addressed
                // to one of our maids, whom the native resolver cannot know about.
                var playerCtor = player.getDeclaredConstructor(UUID.class, ResourceKey.class);
                // The native target is a package-private record in another package: its constructor needs opening.
                playerCtor.setAccessible(true);
                if (resolve.getReturnType() != target || accepts.getReturnType() != boolean.class || airport.getReturnType() != task
                        || toPlayer.getReturnType() != task || add.getReturnType() != void.class
                        || playerCtor.getParameterCount() != 2)
                    throw new NoSuchMethodException("Phantom task contract changed");
                double speed = (double) profile.getMethod("takeoffSpeed").invoke(profile.getField("DEFAULT").get(null));
                if (!Double.isFinite(speed) || speed <= 0) throw new IllegalStateException("Invalid native takeoff speed");
                api = new Api(port, player, resolve, accepts, target.getMethod("dimension"), port.getMethod("pos"), player.getMethod("playerId"), playerCtor,
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
    /** Read-only route check before any cache/carrier is reserved. Ordinary player routes stay native. */
    public static boolean canDispatch(LivingEntity bearer, ItemStack box) {
        if (!(bearer.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !ready()) return false;
        try {
            Object target=api.resolve.invoke(null,level,box,bearer.position().add(0,0.01,0),null,null);
            LivingEntity recipient=maidRecipient(level.getServer(),box);
            if(target==null && recipient!=null)target=maidTarget(recipient);
            return target!=null && maidRouteReady(bearer,recipient,api.port.isInstance(target))
                    && Boolean.TRUE.equals(api.accepts.invoke(null,level,target,box));
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    private static boolean maidRouteReady(LivingEntity bearer, LivingEntity recipient, boolean port) {
        boolean source=dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(bearer);
        if(!source && recipient==null)return true;
        if(!dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.enabled()
                || !dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate.enabled()
                || !dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.available()
                || !dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102FlightCompatible())return false;
        if(recipient!=null && !dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102Compatible())return false;
        // Load/inspect the return queue only for a route which actually needs it.
        return !source || !port || dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomPortReturn.available()
                && dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102PortReturnCompatible();
    }

    /** A player dispatching: the player is his own HUD/source identity. */
    public static TransportDispatch.Result send(ServerPlayer sender, ItemStack box) {
        return launch(sender, sender.getUUID(), box);
    }

    /**
     * One of our maids dispatching (she may launch a Phantom like any player can). She is the loader -
     * level, position and look angle - while her owner supplies the player identity the native task
     * expects for HUD attribution. The port queue receives the actual source maid UUID.
     * No fake player is created and no native gate is bypassed.
     */
    public static TransportDispatch.Result sendForMaid(net.minecraft.world.entity.LivingEntity maid, ItemStack box) {
        if (!(maid instanceof net.minecraft.world.entity.TamableAnimal pet) || pet.getOwnerUUID() == null)
            return TransportDispatch.Result.known(false, "phantom");
        return launch(maid, pet.getOwnerUUID(), box);
    }

    private static TransportDispatch.Result launch(net.minecraft.world.entity.LivingEntity bearer, UUID playerIdentity,
            ItemStack box) {
        var server = bearer.getServer();
        if (server == null || !server.isSameThread() || !ready()) return TransportDispatch.Result.known(false, "phantom");
        if (!(bearer.level() instanceof ServerLevel level)) return TransportDispatch.Result.known(false, "phantom");
        boolean entered = false;
        Object task = null;
        try {
            var origin = bearer.position().add(0, 0.01, 0);
            Object target = api.resolve.invoke(null, level, box, origin, null, null);
            // The native resolver only knows players and ports, so a package addressed to one of our maids
            // resolves to nothing. We build the native player target for her instead, and the native
            // acceptance check below decides - our settlement bridge is what answers it for a maid.
            // Ask for her first: with the native launch bridge in place the resolver now answers for a maid too, so
            // "the resolver returned null" is no longer a reliable signal that this package is addressed to her.
            LivingEntity maid = maidRecipient(server, box);
            if (target == null && maid != null) target = maidTarget(maid);
            if (target == null || !maidRouteReady(bearer, maid, api.port.isInstance(target))
                    || !Boolean.TRUE.equals(api.accepts.invoke(null, level, target, box)))
                return TransportDispatch.Result.known(false, "phantom");
            var look = bearer.getLookAngle();
            var direction = (Vec3) api.direction.invoke(null, new Vec3(look.x, 0, look.z));
            // Actual 1.0.2 bytecode uses direction * native speed + 0.15F vertical lift.
            // Newer source snapshots expose a different helper; do not assume that method exists here.
            var motion = direction.scale(api.takeoffSpeed).add(0, (double) 0.15F, 0);
            Object dimension = api.dimension.invoke(target);
            UUID id = UUID.randomUUID(), owner = playerIdentity;
            // Native UUID order: HUD player, HUD entry, source. A port passes source into a new
            // return task; a player destination keeps its native source slot, with FMP's saved
            // dispatcher marker supplying the maid-only carrier-return transition.
            LivingEntity dispatcher = bearer instanceof net.minecraft.world.entity.TamableAnimal pet
                    && dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(pet) ? pet : null;
            UUID sourceIdentity = dispatcher != null && api.port.isInstance(target) ? dispatcher.getUUID() : owner;
            if (api.port.isInstance(target)) {
                task = api.airportTask.invoke(null, id, box, level, dimension, api.portPos.invoke(target), origin, direction, motion,
                        null, null, owner, id, sourceIdentity);
            } else if (api.player.isInstance(target)) {
                UUID receiver = (UUID) api.playerId.invoke(target);
                // A maid has no entry in the player list: her own reachability check is what made her a target.
                if (maid == null && server.getPlayerList().getPlayer(receiver) == null) return TransportDispatch.Result.known(false, "phantom");
                task = api.playerTask.invoke(null, id, box, level, receiver, dimension, origin, direction, motion,
                        null, null, owner, id, sourceIdentity);
            } else return TransportDispatch.Result.known(false, "phantom");
            if (task == null) return TransportDispatch.Result.known(false, "phantom");
            // Identity must exist before addTask can publish, save or trigger an external callback.
            // A source maid owns the carrier's return leg; the native target UUID still identifies the recipient.
            boolean marked = dispatcher != null
                    ? dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.rememberReturn(task, dispatcher)
                    : maid == null || dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.remember(task, maid);
            if (!marked) {
                dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.forget(task);
                return TransportDispatch.Result.known(false, "phantom");
            }
            // Source stock is held by ReturnEscrow before this first externally visible handoff.
            entered = true;
            api.addTask.invoke(null, server, task);
            // A HUD notification is cosmetic: it must never be able to turn a live task into a failed dispatch.
            try {
                api.notifyHud.invoke(null, server, task);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError hudFailure) {
                FeedMePackages.LOGGER.warn("FMP phantom: HUD notification failed; the flight is unaffected", hudFailure);
            }
            // Diagnostic only, isolated in its own catch: a logging problem must never turn a dispatch into a failure.
            // It exists because a silently failed registration is invisible otherwise, which cost several rounds.
            try {
                dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.note("dispatch: kind="
                        + (api.port.isInstance(target) ? "port" : "player")
                        + " sourceIdentity=" + sourceIdentity
                        + " dispatcher=" + (dispatcher == null ? "none" : dispatcher.getUUID().toString())
                        + " registry=" + dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.registryState(task)
                        + " taskSourcePlayer=" + dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.sourcePlayer(task));
            } catch (RuntimeException | LinkageError ignored) {
                // diagnostics never affect the dispatch
            }
            return TransportDispatch.Result.known(true, "phantom");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            // InvocationTargetException means native code was entered; it may already have published the task.
            boolean refusedEntry = failure instanceof IllegalAccessException || failure instanceof IllegalArgumentException;
            if (!entered || refusedEntry) {
                if (task != null) dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.forget(task);
                return new TransportDispatch.Result(TransportDispatch.Outcome.NOT_SENT, "phantom", cause);
            }
            return new TransportDispatch.Result(TransportDispatch.Outcome.UNCERTAIN, "phantom", cause);
        }
    }

    /**
     * The native player target for a package addressed to one of our maids, or null when it is not one of ours,
     * she is unreachable with an active pendant, or she could not take the stack. Her UUID goes into the native
     * player slot, so the task, its persistence and its return legs stay entirely native - only the identity is
     * unusual, and the settlement bridge is the single place that understands it.
     */
    private static LivingEntity maidRecipient(MinecraftServer server, ItemStack box) {
        try {
            String address = com.simibubi.create.content.logistics.box.PackageItem.getAddress(box);
            if (dev.scathiard.feedmepackages.compat.maid.MaidAccess.recipient(address) == null) return null;
            var maid = dev.scathiard.feedmepackages.compat.maid.MaidAccess.find(server, address);
            if (maid == null) return null;
            if (!dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.canReceive(maid, box)) return null;
            return maid;
        } catch (RuntimeException | LinkageError notOurs) {
            return null;
        }
    }

    /** The native player target carrying her UUID, or null when it cannot be built. */
    private static Object maidTarget(LivingEntity maid) {
        try {
            return api.playerCtor.newInstance(maid.getUUID(), maid.level().dimension());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notOurs) {
            return null;
        }
    }
}
