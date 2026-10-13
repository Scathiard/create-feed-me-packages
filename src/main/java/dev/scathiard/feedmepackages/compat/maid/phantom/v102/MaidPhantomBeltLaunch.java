package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

/**
 * Launch a Create Phantom courier for a belt item addressed to one of our maids (0.3.0 T3b).
 *
 * <p>Why we must do this ourselves: the native belt gate re-checks {@code PlayerList.getPlayer(playerId)} at
 * offset 266 of {@code launchPackageCourier} and gives up (task = null, {@code return false}) whenever that player
 * is offline. A maid never has a player entry, so the carrier stayed on the belt and was dropped at its end. The
 * looked-up player is used <b>only</b> for that online check - the task is built from {@code playerId()},
 * {@code dimension()}, {@code LaunchGeometry} and the source slots, so <b>no fake player is needed or created</b>.
 *
 * <p>Every phantom symbol is reflective and fail-closed. The carrier on the belt is <b>never</b> touched here: the
 * native happy path does not consume it either (its tail is only {@code onCourierTaskStarted} + {@code return
 * true}), so returning true lets the outer belt flow consume it exactly as it does after a native launch.
 *
 * <p>Every refusal is reported once with a stable, greppable line ({@code FMP phantom belt: ...}). A silent refusal
 * is exactly what made the first real-machine attempt undiagnosable, so there are no silent paths left.
 */
public final class MaidPhantomBeltLaunch {
    private MaidPhantomBeltLaunch() {}

    private static final String P = "com.yision.phantom.";
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Object MINI_PHANTOM_ITEM;
    private static Method copyCargoPackage;
    private static Method getReturnTarget;
    private static Method getHudEntryId;
    private static Method findSourcePhantomPortPos;
    private static Method resolvePackageTarget;
    private static Method getLaunchGeometry;
    private static Method launchSpawnPos;
    private static Method launchDirection;
    private static Method launchMotion;
    private static Method forPackageToPlayer;
    private static Method addTask;
    private static Method onCourierTaskStarted;
    private static Class<?> playerTargetType;
    private static volatile boolean wired;
    private static volatile boolean wiringFailed;

    /** True when a carrier was launched; false means the caller must leave the belt item alone. */
    public static boolean launchIfMaid(BeltBlockEntity belt, TransportedItemStack transported) {
        if (!wired()) return refuse("reflective wiring unavailable");
        if (belt == null || transported == null) return refuse("no belt item");
        if (!Phantom102Gate.usable(true)) return refuse("capability gate disabled");
        if (!dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102BeltCompatible()) return refuse("shape gate refused");
        if (!(belt.getLevel() instanceof ServerLevel level)) return refuse("not a server level");
        MinecraftServer server = level.getServer();
        if (server == null) return refuse("no server");
        try {
            ItemStack onBelt = transported.stack;
            if (onBelt == null || onBelt.isEmpty()) return refuse("empty belt stack");
            ItemStack box = (ItemStack) copyCargoPackage.invoke(null, onBelt);
            if (box == null || box.isEmpty()) return refuse("no cargo package on the carrier");
            // Our own launch bridge answers "is this addressed to one of our maids, reachable and able to receive".
            Object target = MaidPhantomLaunch.nativeTargetFor(server, box);
            if (target == null || !playerTargetType.isInstance(target))
                return refuse("not our maid address (or she cannot receive)");
            UUID maidId = (UUID) playerTargetType.getMethod("playerId").invoke(target);
            LivingEntity maid = maidOf(server, maidId);
            if (maid == null) return refuse("maid not reachable with an active pendant");
            // Source slots: the carrier's own return target, else the port found at the belt; never invented.
            Optional<?> returnTarget = optional(getReturnTarget.invoke(null, onBelt));
            ResourceKey<Level> sourceDimension = returnTarget.map(MaidPhantomBeltLaunch::dimensionOf).orElse(level.dimension());
            BlockPos sourcePos = returnTarget.map(MaidPhantomBeltLaunch::posOf).orElse((BlockPos) findSourcePhantomPortPos.invoke(null, belt));
            // Diagnostic only: ask the native resolver the same question and report a disagreement, but never refuse on
            // it - we already hold a validated native target for her, which is what the task is built from.
            try {
                Object resolved = resolvePackageTarget.invoke(null, level, box, Vec3.atCenterOf(belt.getBlockPos()),
                        sourceDimension, sourcePos);
                if (resolved == null || !playerTargetType.isInstance(resolved))
                    note("native resolver returned no player target; building the task from our own maid target");
                else if (!maidId.equals(playerTargetType.getMethod("playerId").invoke(resolved)))
                    note("native resolver disagreed about the recipient; using our own maid target");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                note("native resolver threw; building the task from our own maid target");
            }
            Object geometry = getLaunchGeometry.invoke(null, level, belt, transported);
            if (geometry == null) return refuse("no native launch geometry");
            Vec3 spawn = (Vec3) launchSpawnPos.invoke(geometry);
            Vec3 heading = (Vec3) launchDirection.invoke(geometry);
            Vec3 velocity = (Vec3) launchMotion.invoke(geometry);
            if (spawn == null || heading == null || velocity == null) return refuse("incomplete native launch geometry");
            // Slot order as measured: taskId, box, level, maidId, maidDimension, spawnPos, direction, motion,
            // sourceDimension, sourcePos, null, hudEntryId, null - the two nulls are the measured native values.
            Object task = forPackageToPlayer.invoke(null, UUID.randomUUID(), box, level, maidId,
                    playerTargetType.getMethod("dimension").invoke(target), spawn, heading, velocity,
                    sourceDimension, sourcePos, null, getHudEntryId.invoke(null, onBelt), null);
            if (task == null) return refuse("native task factory returned null");
            addTask.invoke(null, server, task);
            onCourierTaskStarted.invoke(null, server, task);
            // Without this the return leg cannot tell our maid task from a player's.
            MaidPhantomTasks.remember(task, maid);
            note("launched for maid " + maidId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            return refuse("launch threw: " + incompatible.getClass().getSimpleName() + ": " + incompatible.getMessage());
        }
    }

    private static LivingEntity maidOf(MinecraftServer server, UUID maidId) {
        if (maidId == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(maidId) instanceof LivingEntity maid && MaidAccess.resolve(maid).active()) return maid;
        }
        return null;
    }

    private static Optional<?> optional(Object value) {
        return value instanceof Optional<?> present ? present : Optional.empty();
    }

    private static ResourceKey<Level> dimensionOf(Object returnTarget) {
        try { return (ResourceKey<Level>) returnTarget.getClass().getMethod("dimension").invoke(returnTarget); }
        catch (ReflectiveOperationException | RuntimeException absent) { return null; }
    }

    private static BlockPos posOf(Object returnTarget) {
        try { return (BlockPos) returnTarget.getClass().getMethod("pos").invoke(returnTarget); }
        catch (ReflectiveOperationException | RuntimeException absent) { return null; }
    }

    /** One stable line per distinct reason: a support log must say why the belt was left to the native path. */
    private static void note(String reason) {
        if (REPORTED.add(reason)) FeedMePackages.LOGGER.info("FMP phantom belt: " + reason);
    }

    private static boolean refuse(String reason) {
        note("not taken over - " + reason);
        return false;
    }

    private static boolean wired() {
        if (wired) return true;
        synchronized (MaidPhantomBeltLaunch.class) {
            if (wired) return true;
            try {
                Class<?> item = Class.forName(P + "item.miniphantom.MiniPhantomItem");
                copyCargoPackage = item.getMethod("copyCargoPackage", ItemStack.class);
                getReturnTarget = item.getMethod("getReturnTarget", ItemStack.class);
                getHudEntryId = item.getMethod("getHudEntryId", ItemStack.class);
                findSourcePhantomPortPos = Class.forName(P + "logistics.courier.AirCourierHelper")
                        .getMethod("findSourcePhantomPortPos", BeltBlockEntity.class);
                Class<?> dispatch = Class.forName(P + "logistics.courier.AirCourierDispatchService");
                resolvePackageTarget = dispatch.getMethod("resolvePackageTarget", ServerLevel.class, ItemStack.class,
                        Vec3.class, ResourceKey.class, BlockPos.class);
                getLaunchGeometry = Class.forName(P + "logistics.courier.AirCourierBeltHooks")
                        .getDeclaredMethod("getLaunchGeometry", ServerLevel.class, BeltBlockEntity.class, TransportedItemStack.class);
                getLaunchGeometry.setAccessible(true);
                // LaunchGeometry is a package-private nested class in the target mod, so its public accessors are not
                // invocable from our package: measured in the user's log as IllegalAccessException naming that class.
                // They are opened here once; every other phantom type we touch is public.
                Class<?> geometryType = Class.forName(P + "logistics.courier.AirCourierBeltHooks$LaunchGeometry");
                launchSpawnPos = geometryType.getMethod("spawnPos");
                launchSpawnPos.setAccessible(true);
                launchDirection = geometryType.getMethod("direction");
                launchDirection.setAccessible(true);
                launchMotion = geometryType.getMethod("motion");
                launchMotion.setAccessible(true);
                Class<?> task = Class.forName(P + "logistics.courier.AirCourierTask");
                forPackageToPlayer = task.getMethod("forPackageToPlayer", UUID.class, ItemStack.class, ServerLevel.class,
                        UUID.class, ResourceKey.class, Vec3.class, Vec3.class, Vec3.class, ResourceKey.class,
                        BlockPos.class, UUID.class, UUID.class, UUID.class);
                addTask = Class.forName(P + "logistics.courier.AirCourierTaskManager")
                        .getMethod("addTask", MinecraftServer.class, task);
                onCourierTaskStarted = Class.forName(P + "logistics.courier.hud.AirCourierHudSync")
                        .getMethod("onCourierTaskStarted", MinecraftServer.class, task);
                playerTargetType = Class.forName(P + "logistics.courier.AirCourierTarget$PlayerTarget");
                wired = true;
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                // Retried on the next launch instead of latching a permanent failure: it is reported once, loudly.
                if (!wiringFailed) {
                    wiringFailed = true;
                    FeedMePackages.LOGGER.warn("FMP phantom belt: reflective wiring failed; the belt keeps native behaviour", incompatible);
                }
                wired = false;
                return false;
            }
        }
    }
}
