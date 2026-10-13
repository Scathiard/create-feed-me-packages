package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Create Phantom 1.0.2, the wing station's own carrier departure (0.3.0 T3c): a carrier that a port has to send back
 * to someone.
 *
 * <p>Measured in bytecode: {@code PhantomPortReturnQueue.tryQueueStoredReturnCarrierToPlayer(UUID)} resolves that UUID
 * through {@code PlayerList.getPlayer} and gives up when there is no living player - which is exactly what a maid is.
 * The port then ends up ejecting the stored carrier as a dropped item, which the user saw after the naive attempt of
 * simply addressing the return to her.
 *
 * <p>So we take over at that very point, and only for one of our maids: consume the port's stored carrier (one per
 * flight, as the native path does) and create the native carrier-return task addressed to her ourselves, registering it
 * so the flight layer knows whose it is. Everything after that - flying to her, taking her live position, handing the
 * carrier into her inventory - is the machinery that already exists. Fail-closed throughout: any doubt, any mismatch or
 * any reflection failure returns false, which leaves the native behaviour completely untouched.
 */
public final class MaidPhantomPortReturn {
    private MaidPhantomPortReturn() {}

    private static boolean attempted;
    private static Field portField;
    private static Field inventoryField;
    private static Field carrierInventoryField;
    private static Method hasUsableCarrier;
    private static Method forCarrierReturnToPlayer;
    private static Method addTask;
    private static Method onCourierTaskStarted;

    /** True once every member this takeover touches was resolved. Never throws. */
    public static synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                String root = "com.yision.phantom.";
                Class<?> queue = Class.forName(root + "block.phantomport.PhantomPortReturnQueue");
                portField = queue.getDeclaredField("port");
                portField.setAccessible(true);
                inventoryField = queue.getDeclaredField("inventory");
                inventoryField.setAccessible(true);
                Class<?> inventory = Class.forName(root + "block.phantomport.PhantomPortInventory");
                hasUsableCarrier = inventory.getMethod("hasUsableCarrier");
                carrierInventoryField = inventory.getDeclaredField("carrierInventory");
                carrierInventoryField.setAccessible(true);
                Class<?> task = Class.forName(root + "logistics.courier.AirCourierTask");
                forCarrierReturnToPlayer = task.getMethod("forCarrierReturnToPlayer", UUID.class, ServerLevel.class,
                        UUID.class, net.minecraft.resources.ResourceKey.class, Vec3.class, Vec3.class, Vec3.class);
                addTask = Class.forName(root + "logistics.courier.AirCourierTaskManager")
                        .getMethod("addTask", MinecraftServer.class, task);
                onCourierTaskStarted = Class.forName(root + "logistics.courier.hud.AirCourierHudSync")
                        .getMethod("onCourierTaskStarted", MinecraftServer.class, task);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                MaidPhantomTasks.note("port return takeover is unavailable: " + incompatible.getClass().getSimpleName()
                        + ": " + incompatible.getMessage());
                portField = null;
            }
        }
        return portField != null;
    }

    /**
     * Takes over one pending carrier return addressed to {@code playerId} when that id is one of our maids. Returns true
     * only when a real task was created for her, so the caller's native path is skipped; otherwise the caller keeps its
     * own behaviour, including its own handling of an unresolvable target.
     */
    public static boolean takeOver(Object queue, UUID playerId) {
        if (!available() || queue == null || playerId == null) return false;
        try {
            if (!(portField.get(queue) instanceof net.minecraft.world.level.block.entity.BlockEntity port)) return false;
            if (!(port.getLevel() instanceof ServerLevel level)) return false;
            MinecraftServer server = level.getServer();
            if (server == null) return false;
            LivingEntity maid = MaidPhantomTasks.maidByEntity(server, playerId);
            if (maid == null) return false;
            Object inventory = inventoryField.get(queue);
            if (inventory == null || !Boolean.TRUE.equals(hasUsableCarrier.invoke(inventory))) return false;
            if (!(carrierInventoryField.get(inventory) instanceof net.neoforged.neoforge.items.ItemStackHandler carriers))
                return false;
            ItemStack taken = carriers.extractItem(0, 1, false);
            if (taken == null || taken.isEmpty()) return false;
            Object task = forCarrierReturnToPlayer.invoke(null, UUID.randomUUID(), level, maid.getUUID(),
                    maid.level().dimension(), Vec3.atCenterOf(port.getBlockPos()), new Vec3(0, 0, 1), new Vec3(0, 0, .3));
            if (task == null || !MaidPhantomTasks.rememberReturn(task, maid)) {
                carriers.insertItem(0, taken, false); // give the port its carrier back: nothing may be lost
                return false;
            }
            addTask.invoke(null, server, task);
            try {
                onCourierTaskStarted.invoke(null, server, task);
            } catch (ReflectiveOperationException | RuntimeException hudFailure) {
                // cosmetic only: a HUD problem must never undo a task that is already live
            }
            MaidPhantomTasks.note("port carrier return taken over for maid " + maid.getUUID());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            MaidPhantomTasks.note("port carrier return takeover failed: " + incompatible.getClass().getSimpleName()
                    + ": " + incompatible.getMessage());
            return false;
        }
    }
}
