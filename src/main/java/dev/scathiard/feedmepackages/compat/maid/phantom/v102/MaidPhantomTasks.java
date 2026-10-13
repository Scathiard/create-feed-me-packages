package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Read/write access to the parts of a native 1.0.2 courier task this feature needs (0.3.0 T4). All reflective and
 * fail-closed: anything that cannot be resolved disables the feature instead of guessing.
 *
 * <p>The identity test is deliberately narrow. A task counts as ours only when its native player slot holds a UUID
 * that is one of <b>our</b> maid addresses, the package on board is addressed to that same maid, no port target is
 * set, the mission is a package-to-player one, and the task is not already returning an undelivered package. The
 * player-shaped UUID slot is a coincidence we read, never a fake player we create.
 */
public final class MaidPhantomTasks {
    private MaidPhantomTasks() {}

    private static boolean attempted;
    private static Method targetPlayerId;
    private static Method mission;
    private static Method box;
    private static Method position;
    private static Field returningUndeliveredPackage;
    private static Field targetDimension;
    private static Field targetPhantomPortPos;
    private static Method doFinishDelivery;
    private static Method tryReturnUndeliveredPackage;
    private static Method startCarrierReturn;

    /** True once every member this class touches was resolved. Never throws. */
    public static synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                Class<?> task = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask");
                targetPlayerId = task.getMethod("targetPlayerId");
                id = task.getMethod("id");
                mission = task.getMethod("mission");
                box = task.getMethod("box");
                position = task.getMethod("position");
                returningUndeliveredPackage = task.getDeclaredField("returningUndeliveredPackage");
                returningUndeliveredPackage.setAccessible(true);
                targetDimension = task.getDeclaredField("targetDimension");
                targetDimension.setAccessible(true);
                targetPhantomPortPos = task.getDeclaredField("targetPhantomPortPos");
                targetPhantomPortPos.setAccessible(true);
                doFinishDelivery = task.getDeclaredMethod("doFinishDelivery", MinecraftServer.class, ServerLevel.class);
                doFinishDelivery.setAccessible(true);
                tryReturnUndeliveredPackage = task.getDeclaredMethod("tryReturnUndeliveredPackage", MinecraftServer.class);
                tryReturnUndeliveredPackage.setAccessible(true);
                startCarrierReturn = task.getDeclaredMethod("startCarrierReturn", MinecraftServer.class);
                startCarrierReturn.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                targetPlayerId = null;
                mission = null;
                box = null;
                position = null;
                returningUndeliveredPackage = null;
                targetDimension = null;
                targetPhantomPortPos = null;
                doFinishDelivery = null;
                tryReturnUndeliveredPackage = null;
                startCarrierReturn = null;
            }
        }
        return targetPlayerId != null && mission != null && box != null && position != null
                && returningUndeliveredPackage != null && targetDimension != null && targetPhantomPortPos != null
                && doFinishDelivery != null && tryReturnUndeliveredPackage != null && startCarrierReturn != null;
    }

    /** The maid this task is currently delivering to, or null when the task is not one of ours. */
    public static LivingEntity maidRecipient(MinecraftServer server, Object task) {
        if (!available() || server == null || task == null) return null;
        try {
            if (!(targetPlayerId.invoke(task) instanceof UUID uuid)) return null;
            if (Boolean.TRUE.equals(returningUndeliveredPackage.get(task))) return null;
            if (targetPhantomPortPos.get(task) != null) return null;
            if (!(mission.invoke(task) instanceof Enum<?> current) || !current.name().equals("PACKAGE_TO_PLAYER")) return null;
            if (!(box.invoke(task) instanceof ItemStack item) || item.isEmpty()) return null;
            String address = PackageItem.getAddress(item);
            if (!uuid.equals(MaidAccess.recipient(address))) return null;
            return MaidAccess.find(server, address);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** The task's current world position, or null when it cannot be read. */
    public static Vec3 position(Object task) {
        if (!available() || task == null) return null;
        try {
            return position.invoke(task) instanceof Vec3 at ? at : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Keep the task's own idea of its destination dimension in step with hers (cross-dimension following). */
    public static void followDimension(Object task, ServerLevel level) {
        if (!available() || task == null || level == null) return;
        try {
            targetDimension.set(task, level.dimension());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A failed bookkeeping write never cancels the flight; the native code re-resolves next tick.
        }
    }

    /** Hand the arrival to the native finish path, which then runs the native return legs. */
    public static boolean finishDelivery(Object task, MinecraftServer server, ServerLevel level) {
        if (!available() || task == null || server == null || level == null) return false;
        try {
            doFinishDelivery.invoke(task, server, level);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Hand the package to her own cache, or let the native path take it back. True when the outcome is owned here -
     * delivered (a successful receive consumes the package, so the native carrier return is launched explicitly,
     * because the native post-check skips it for an empty box) or handed to the native undelivered-return path,
     * which is exactly what a player with no room gets. Nothing is dropped, duplicated, re-sent or charged twice.
     */
    public static boolean handOver(Object task, LivingEntity maid, MinecraftServer server) {
        if (task == null || maid == null || server == null || !available()) return false;
        ItemStack box = packageOf(task);
        if (box == null || box.isEmpty()) return false;
        if (!dev.scathiard.feedmepackages.logistics.ReceiveService.receive(MaidAccess.endpoint(maid), box))
            return returnUndelivered(task, server);
        startCarrierReturn(task, server);
        return true;
    }

    /** The package this task is carrying, or null when it cannot be read. */
    public static ItemStack packageOf(Object task) {
        if (!available() || task == null) return null;
        try {
            return box.invoke(task) instanceof ItemStack item ? item : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Native "return the undelivered package by its original address" - source port first, then its real source
     *  player. True when the native method took the package over; false leaves the decision to the caller. */
    public static boolean returnUndelivered(Object task, MinecraftServer server) {
        if (!available() || task == null || server == null) return false;
        try {
            return Boolean.TRUE.equals(tryReturnUndeliveredPackage.invoke(task, server));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /** Launch the native carrier return. The native post-check skips it once the box is empty, so a delivery that
     *  consumed the package must call it explicitly - otherwise the carrier of the finished task is stranded. */
    public static boolean startCarrierReturn(Object task, MinecraftServer server) {
        if (!available() || task == null || server == null) return false;
        try {
            startCarrierReturn.invoke(task, server);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // R1: which native task belongs to one of our maids.
    //
    // The native task cannot tell us this: a maid dispatch leaves the player slot holding the maid's *owner* (a real
    // player UUID), which is byte-identical to a player's own carrier return. So the only discriminator is our own
    // marker. This period keeps it in-process, keyed by the native task's own id(); persistence is deliberately out
    // of scope (documented), and after a restart a flying maid task simply falls back to native "return to owner".
    // ---------------------------------------------------------------------------------------------------------------

    /** Registry values reuse the marker codec's three strings, so NATIVE/CORRUPT rules are literally the same. */
    private static final java.util.Map<UUID, String[]> MAID_TASKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static Method id;

    private static UUID idOf(Object task) {
        if (task == null) return null;
        try {
            if (id == null) id = task.getClass().getMethod("id");
            Object value = id.invoke(task);
            return value instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException | RuntimeException unresolved) {
            return null;
        }
    }

    /** Registers a task we just dispatched for one of our maids. Only ours: a non-maid or an unwired maid is refused. */
    public static boolean remember(Object task, LivingEntity maid) {
        UUID taskId = idOf(task);
        if (taskId == null || maid == null) return false;
        if (!MaidAccess.resolve(maid).active()) return false;
        MAID_TASKS.put(taskId, PhantomMaidTaskMarker.encode(taskId, maid.getUUID(), PhantomMaidTaskMarker.Direction.OUTBOUND));
        return true;
    }

    public static boolean forget(Object task) {
        UUID taskId = idOf(task);
        return taskId != null && MAID_TASKS.remove(taskId) != null;
    }

    /** The maid recorded for this task, or null. Absent markers and corrupt ones both return null and are dropped. */
    public static LivingEntity maidFor(Object task, MinecraftServer server) {
        UUID taskId = idOf(task);
        if (taskId == null || server == null) return null;
        String[] stored = MAID_TASKS.get(taskId);
        if (stored == null) return null;
        PhantomMaidTaskMarker.Decoded decoded = PhantomMaidTaskMarker.decode(stored[0], stored[1], stored[2]);
        if (!decoded.marked()) { MAID_TASKS.remove(taskId); return null; }
        if (!Phantom102Gate.usable(true)) return null;
        if (!dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102FlightCompatible()) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(decoded.maidId()) instanceof LivingEntity maid && MaidAccess.resolve(maid).active()) return maid;
        }
        return null;
    }

    /**
     * Hand a returned carrier to her: <b>inventory first, then her cache</b>. Simulated on copies - the caller keeps
     * the carrier whenever this returns false, so nothing is consumed or swallowed on a failure.
     */
    public static boolean handCarrierTo(LivingEntity maid, ItemStack carrier) {
        if (maid == null || carrier == null || carrier.isEmpty()) return false;
        ItemStack rest = carrier.copy();
        var inventory = dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.inventory(maid);
        if (inventory != null) {
            for (int slot = 0; slot < inventory.getSlots() && !rest.isEmpty(); slot++) rest = inventory.insertItem(slot, rest, true);
            if (rest.isEmpty()) return dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.insertInto(maid, carrier.copy());
        }
        return dev.scathiard.feedmepackages.logistics.ReceiveService.receive(MaidAccess.endpoint(maid), carrier.copy());
    }
}
