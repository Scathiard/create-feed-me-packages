package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
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
 * B1: launch a Create Phantom courier for a belt item addressed to one of our maids (0.3.0).
 *
 * <p>Why we must do this ourselves: the native belt gate re-checks {@code PlayerList.getPlayer(playerId)} at
 * offset 266 of {@code launchPackageCourier} and gives up (task = null, {@code return false}) whenever that player
 * is offline. A maid never has a player entry, so the carrier stayed on the belt. The looked-up player is used
 * <b>only</b> for that online check - the task is built from {@code playerId()}, {@code dimension()},
 * {@code LaunchGeometry} and the source slots, so <b>no fake player is needed or created</b>.
 *
 * <p>Every phantom symbol is reflective and fail-closed. The carrier on the belt is <b>never</b> touched here: the
 * native happy path does not consume it either (its tail is only {@code onCourierTaskStarted} + {@code return
 * true}), so returning true lets the outer belt flow consume it exactly as it does after a native launch.
 */
public final class MaidPhantomBeltLaunch {
    private MaidPhantomBeltLaunch() {}

    private static final String P = "com.yision.phantom.";
    private static Object MINI_PHANTOM_ITEM;
    private static Method copyCargoPackage;
    private static Method getReturnTarget;
    private static Method getHudEntryId;
    private static Method findSourcePhantomPortPos;
    private static Method resolvePackageTarget;
    private static Method getLaunchGeometry;
    private static Method forPackageToPlayer;
    private static Method addTask;
    private static Method onCourierTaskStarted;
    private static Class<?> playerTargetType;
    private static volatile boolean wired;
    private static volatile boolean broken;

    /** True when a carrier was launched; false means the caller must leave the belt item alone. */
    public static boolean launchIfMaid(BeltBlockEntity belt, TransportedItemStack transported) {
        if (!wired() || belt == null || transported == null) return false;
        if (!Phantom102Gate.usable(true)) return false;
        if (!dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102BeltCompatible()) return false;
        if (!(belt.getLevel() instanceof ServerLevel level)) return false;
        MinecraftServer server = level.getServer();
        if (server == null) return false;
        try {
            ItemStack onBelt = transported.stack;
            if (onBelt == null || onBelt.isEmpty()) return false;
            ItemStack box = (ItemStack) copyCargoPackage.invoke(null, onBelt);
            if (box == null || box.isEmpty()) return false;
            // Our own launch bridge already answers "is this addressed to one of our maids, reachable and able to
            // receive" - a null there means this is not our case, so the belt keeps its normal behaviour.
            Object target = MaidPhantomLaunch.nativeTargetFor(server, box);
            if (target == null || !playerTargetType.isInstance(target)) return false;
            UUID maidId = (UUID) playerTargetType.getMethod("playerId").invoke(target);
            LivingEntity maid = maidOf(server, maidId);
            if (maid == null) return false;
            // Source slots: the carrier's own return target, else the port found at the belt; never invented.
            Optional<?> returnTarget = optional(getReturnTarget.invoke(null, onBelt));
            ResourceKey<Level> sourceDimension = returnTarget.map(MaidPhantomBeltLaunch::dimensionOf).orElse(level.dimension());
            BlockPos sourcePos = returnTarget.map(MaidPhantomBeltLaunch::posOf).orElse((BlockPos) findSourcePhantomPortPos.invoke(null, belt));
            // Re-ask the native dispatch with the same arguments the native gate uses; it must agree on the maid.
            Object resolved = resolvePackageTarget.invoke(null, level, box, Vec3.atCenterOf(belt.getBlockPos()), sourceDimension, sourcePos);
            if (resolved == null || !playerTargetType.isInstance(resolved)) return false;
            if (!maidId.equals(playerTargetType.getMethod("playerId").invoke(resolved))) return false;
            Object geometry = getLaunchGeometry.invoke(null, level, belt, transported);
            if (geometry == null) return false;
            Class<?> geometryType = geometry.getClass();
            Vec3 spawnPos = (Vec3) geometryType.getMethod("spawnPos").invoke(geometry);
            Vec3 direction = (Vec3) geometryType.getMethod("direction").invoke(geometry);
            Vec3 motion = (Vec3) geometryType.getMethod("motion").invoke(geometry);
            Class<?> taskType = Class.forName(P + "logistics.courier.AirCourierTask");
            // Slot order as measured: taskId, box, level, maidId, maidDimension, spawnPos, direction, motion,
            // sourceDimension, sourcePos, null, hudEntryId, null - the two nulls are the measured native values.
            Object task = forPackageToPlayer.invoke(null, UUID.randomUUID(), box, level, maidId,
                    playerTargetType.getMethod("dimension").invoke(resolved), spawnPos, direction, motion,
                    sourceDimension, sourcePos, null, getHudEntryId.invoke(null, onBelt), null);
            if (task == null) return false;
            addTask.invoke(null, server, task);
            onCourierTaskStarted.invoke(null, server, task);
            // P2 (folded in here): without this the return leg cannot tell our maid task from a player's.
            MaidPhantomTasks.remember(task, maid);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            return false;
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

    private static boolean wired() {
        if (broken) return false;
        if (wired) return true;
        synchronized (MaidPhantomBeltLaunch.class) {
            if (broken) return false;
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
                broken = true;
                return false;
            }
        }
    }
}
