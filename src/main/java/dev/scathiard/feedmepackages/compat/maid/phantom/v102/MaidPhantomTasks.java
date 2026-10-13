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

    /**
     * True when this task is flying its carrier home. Measured 1.0.2 criteria: the mission is one of the two
     * carrier-return missions, the task is not returning an undelivered <i>package</i>, and the box is gone. Note the
     * two are cleanly separated - {@code resetForReturn} clears the undelivered flag, {@code tryReturnUndeliveredPackage}
     * sets it - so a carrier return can never be mistaken for an undelivered package return.
     */
    public static boolean onReturnLeg(Object task) {
        if (!available() || task == null) return false;
        try {
            if (!(mission.invoke(task) instanceof Enum<?> current)) return false;
            String name = current.name();
            if (!name.equals("CARRIER_RETURN") && !name.equals("CARRIER_RETURN_TO_PLAYER")) return false;
            if (Boolean.TRUE.equals(returningUndeliveredPackage.get(task))) return false;
            return box.invoke(task) instanceof ItemStack item && item.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * True only for the return leg that has <b>no source port</b> - the mission the target mod reserves for "carry the
     * carrier back to the real source player". Only this one is ours to redirect to the maid.
     *
     * <p>When a source port exists the mission is {@code CARRIER_RETURN} instead, and the native rule (as well as the
     * user's ruling, "a player carrier returns to its port") is to fly the carrier back to that port. The user verified
     * exactly that behaviour on the belt path, so it must stay untouched: redirecting it would steal a correct return.
     */
    public static boolean returnsToSourcePlayer(Object task) {
        if (!available() || task == null) return false;
        try {
            if (!(mission.invoke(task) instanceof Enum<?> current)
                    || !current.name().equals("CARRIER_RETURN_TO_PLAYER")) return false;
            if (Boolean.TRUE.equals(returningUndeliveredPackage.get(task))) return false;
            return box.invoke(task) instanceof ItemStack item && item.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * The maid a returning carrier belongs to, or null. Two measured shapes exist:
     * <ul>
     *   <li>a <b>port</b> schedules the carrier return with the source-player UUID it was handed at dispatch, and
     *       {@code PhantomPortReturnQueue.schedulePendingReturnToPlayer(UUID,int)} does not check that a player exists.
     *       For a maid dispatch we hand it hers, so the returning task's target slot simply holds a maid address - which
     *       we recognise here on our own, with no registry at all;</li>
     *   <li>a <b>player</b> delivery schedules the return through the native source-player path, where the slot holds her
     *       <i>owner</i> and only our own registry can identify her.</li>
     * </ul>
     */
    public static LivingEntity carrierRecipient(MinecraftServer server, Object task) {
        if (!available() || server == null || task == null) return null;
        if (!returnsToSourcePlayer(task)) return null;
        UUID slot = targetId(task);
        if (slot != null && MaidAccess.recipient(MaidAccess.ADDRESS_PREFIX + slot) != null) {
            LivingEntity bySlot = maidOfTargetSlot(server, slot);
            if (bySlot != null) return bySlot;
        }
        return maidForReturn(task, server);
    }

    /** The maid sitting in the native player slot of a carrier return, at entity level (see {@link #maidEntity}). */
    private static LivingEntity maidOfTargetSlot(MinecraftServer server, UUID id) {
        LivingEntity found = maidEntity(server, id);
        if (found != null) return found;
        // This single step decides whether the feature works, so it says why instead of silently no-oping. The entity
        // type matters: an address in maid form can still hold a player UUID, and then this is simply not ours.
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity other) {
                String owner = other instanceof net.minecraft.world.entity.TamableAnimal pet
                        ? String.valueOf(pet.getOwnerUUID()) : "n/a";
                note("carrier return addresses " + id + " which is " + other.getType().toShortString()
                        + " (tame-owner=" + owner + ") in " + level.dimension().location()
                        + "; not usable as a maid: " + MaidAccess.resolve(other).status());
                return null;
            }
        }
        note("carrier return addresses " + id + " but that entity is loaded in no level (chunk unloaded)");
        return null;
    }

    // --- native carrier item and task ending -------------------------------------------------------------------
    // Only the return leg needs these, and the native carrier does not exist during flight at all: the target mod
    // creates it from AllItems.MINI_PHANTOM.asStack() at the moment of delivery (measured). So we create it from the
    // same source instead of inventing an item, and a failure here is wired lazily so it can never disable the
    // package path.
    private static Method markRemoved;
    private static Method carrierAsStack;
    private static Object carrierEntry;
    private static boolean carrierReady;
    private static boolean carrierWiringFailed;

    private static synchronized boolean carrierWired() {
        if (carrierReady) return true;
        try {
            markRemoved = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask").getMethod("markRemoved");
            carrierEntry = Class.forName("com.yision.phantom.registry.AllItems").getField("MINI_PHANTOM").get(null);
            carrierAsStack = carrierEntry.getClass().getMethod("asStack");
            carrierReady = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            if (!carrierWiringFailed) {
                carrierWiringFailed = true;
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn(
                        "FMP phantom maid: carrier wiring unavailable; a returning carrier keeps native behaviour", incompatible);
            }
            return false;
        }
    }

    /** The carrier item exactly as the native delivery creates it, or null. Never throws. */
    public static ItemStack nativeCarrier() {
        if (!carrierWired()) return null;
        try {
            return carrierAsStack.invoke(carrierEntry) instanceof ItemStack stack ? stack : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Let the native task end after its carrier was delivered by us. Never throws. */
    public static boolean endTask(Object task) {
        if (!carrierWired() || task == null) return false;
        try {
            markRemoved.invoke(task);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
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

    /** True once the task has fallen into the native "bring the undelivered package back" path. */
    public static boolean undeliveredReturn(Object task) {
        if (!available() || task == null) return false;
        try {
            return Boolean.TRUE.equals(returningUndeliveredPackage.get(task));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static Field phaseField;
    private static boolean phaseReady;
    private static boolean phaseWiringFailed;

    private static synchronized boolean phaseWired() {
        if (phaseReady) return true;
        try {
            phaseField = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask").getDeclaredField("phase");
            phaseField.setAccessible(true);
            phaseReady = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            if (!phaseWiringFailed) {
                phaseWiringFailed = true;
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn("FMP phantom maid: phase not readable; diagnostics only", incompatible);
            }
            return false;
        }
    }

    /** The task's phase name, for diagnostics only ("?" when unreadable). Never throws. */
    public static String phaseName(Object task) {
        if (!phaseWired() || task == null) return "?";
        try {
            return phaseField.get(task) instanceof Enum<?> current ? current.name() : "?";
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return "?";
        }
    }

    /** The task's mission name, for diagnostics only ("?" when unreadable). Never throws. */
    public static String missionName(Object task) {
        if (!available() || task == null) return "?";
        try {
            return mission.invoke(task) instanceof Enum<?> current ? current.name() : "?";
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return "?";
        }
    }

    /** The native player slot's UUID, for diagnostics only. Never throws. */
    public static java.util.UUID targetId(Object task) {
        if (!available() || task == null) return null;
        try {
            return targetPlayerId.invoke(task) instanceof java.util.UUID id ? id : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** True when the task carries no package any more, for diagnostics only. Never throws. */
    public static boolean boxIsEmpty(Object task) {
        if (!available() || task == null) return true;
        try {
            return !(box.invoke(task) instanceof ItemStack item) || item.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }

    /** What our own registry holds for this task: NONE, OUTBOUND, RETURN or CORRUPT. Diagnostics and lookups. */
    public static String registryState(Object task) {
        UUID taskId = idOf(task);
        if (taskId == null) return "NONE";
        String[] stored = MAID_TASKS.get(taskId);
        if (stored == null) return "NONE";
        PhantomMaidTaskMarker.Decoded decoded = PhantomMaidTaskMarker.decode(stored[0], stored[1], stored[2]);
        if (!decoded.marked()) return "CORRUPT";
        return decoded.direction() == PhantomMaidTaskMarker.Direction.RETURN ? "RETURN" : "OUTBOUND";
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

    /** Registers a task we just dispatched for one of our maids as the <b>recipient</b> of its package. */
    public static boolean remember(Object task, LivingEntity maid) {
        return remember(task, maid, PhantomMaidTaskMarker.Direction.OUTBOUND);
    }

    /**
     * Registers a task a maid <b>dispatched herself</b> (her own cache returning goods). Its return leg has to come
     * back to her, and the native player slot holds her <i>owner</i> either way, so this entry is the only thing that
     * can tell her task from a player's own carrier return. Measured from the real machine: without it the carrier
     * simply follows the native rule and flies to the owner.
     */
    public static boolean rememberReturn(Object task, LivingEntity maid) {
        return remember(task, maid, PhantomMaidTaskMarker.Direction.RETURN);
    }

    private static boolean remember(Object task, LivingEntity maid, PhantomMaidTaskMarker.Direction direction) {
        UUID taskId = idOf(task);
        if (taskId == null || maid == null) return false;
        // Entity level only, deliberately: flying to her and putting a carrier into her inventory needs her entity,
        // not her cache. Requiring the full access path here made registration fail silently whenever her pendant was
        // not wearable at that moment (measured on the real machine: she dispatched, yet nothing was registered).
        if (!dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(maid)
                || !maid.isAlive() || maid.isRemoved()) return false;
        MAID_TASKS.put(taskId, PhantomMaidTaskMarker.encode(taskId, maid.getUUID(), direction));
        return true;
    }

    /** The maid entity with this UUID, at entity level: a maid, alive, not removed, somewhere loaded. */
    private static LivingEntity maidEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity maid
                    && dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.isMaid(maid)
                    && maid.isAlive() && !maid.isRemoved()) return maid;
        }
        return null;
    }

    public static boolean forget(Object task) {
        UUID taskId = idOf(task);
        return taskId != null && MAID_TASKS.remove(taskId) != null;
    }

    /** The maid recorded for this task, or null. Absent markers and corrupt ones both return null and are dropped. */
    public static LivingEntity maidFor(Object task, MinecraftServer server) {
        return maidFor(task, server, null);
    }

    /** The maid recorded as the dispatcher of this task (its return leg is hers), or null. */
    public static LivingEntity maidForReturn(Object task, MinecraftServer server) {
        return maidFor(task, server, PhantomMaidTaskMarker.Direction.RETURN);
    }

    private static LivingEntity maidFor(Object task, MinecraftServer server, PhantomMaidTaskMarker.Direction required) {
        UUID taskId = idOf(task);
        if (taskId == null || server == null) return null;
        String[] stored = MAID_TASKS.get(taskId);
        if (stored == null) return null;
        PhantomMaidTaskMarker.Decoded decoded = PhantomMaidTaskMarker.decode(stored[0], stored[1], stored[2]);
        if (!decoded.marked()) { MAID_TASKS.remove(taskId); return null; }
        if (required != null && decoded.direction() != required) return null;
        if (!Phantom102Gate.usable(true)) return null;
        if (!dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102FlightCompatible()) return null;
        // Entity level, matching how the entry was written: the cache is not needed to fly to her or to fill her bag.
        return maidEntity(server, decoded.maidId());
    }

    /** One stable line per distinct reason. The real machine is the only place this feature runs for real. */
    public static void note(String reason) {
        if (REPORTED.add(reason))
            dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP phantom maid: " + reason);
    }
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Hand a returned carrier to her: <b>inventory first, then her cache</b>. Simulated on copies - the caller keeps
     * the carrier whenever this returns false, so nothing is consumed or swallowed on a failure.
     */
    public static boolean handCarrierTo(LivingEntity maid, ItemStack carrier) {
        if (maid == null || carrier == null || carrier.isEmpty()) {
            note("carrier hand-over refused: nothing to hand");
            return false;
        }
        ItemStack rest = carrier.copy();
        var inventory = dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.inventory(maid);
        boolean fits = false;
        if (inventory != null) {
            for (int slot = 0; slot < inventory.getSlots() && !rest.isEmpty(); slot++) rest = inventory.insertItem(slot, rest, true);
            fits = rest.isEmpty();
            if (fits && dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.insertInto(maid, carrier.copy())) return true;
        }
        boolean cache = dev.scathiard.feedmepackages.logistics.ReceiveService.receive(
                MaidAccess.endpoint(maid), carrier.copy());
        if (!cache) note("carrier hand-over refused: inventoryFits=" + fits + " cache=" + cache);
        return cache;
    }
}
