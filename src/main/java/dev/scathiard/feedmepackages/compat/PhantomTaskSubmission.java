package dev.scathiard.feedmepackages.compat;

import dev.scathiard.feedmepackages.FeedMePackages;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/** Create Phantom 1.0.2 appends to its live task list before creating a visual entity.
 * The list is the admission receipt; a later visual/HUD exception cannot refund a live flight. */
public final class PhantomTaskSubmission {
    private PhantomTaskSubmission() {}
    private record Api(Field data, Method tasks, Method add, Method hud) {}
    private static Api api;

    public static Prepared prepare(MinecraftServer server, Object task) throws ReflectiveOperationException {
        if (server == null || !server.isSameThread() || task == null) return null;
        if (api == null) {
            String root = "com.yision.phantom.logistics.courier.";
            var manager = Class.forName(root + "AirCourierTaskManager");
            var type = Class.forName(root + "AirCourierTask");
            Field data = manager.getDeclaredField("savedData"); data.setAccessible(true);
            api = new Api(data, Class.forName(root + "AirCourierTaskSavedData").getMethod("getTasks"),
                    manager.getMethod("addTask", MinecraftServer.class, type),
                    Class.forName(root + "hud.AirCourierHudSync").getMethod("onCourierTaskStarted", MinecraftServer.class, type));
        }
        Object data = api.data.get(null);
        // The native server-start handler initializes this. Never initialize/replace its store ourselves.
        if (data == null || !(api.tasks.invoke(data) instanceof List<?> live)) return null;
        return new Prepared(server, task, live, api.add, api.hud);
    }

    public record Prepared(MinecraftServer server, Object task, List<?> live, Method add, Method hud) {
        public boolean submit() {
            if (!server.isSameThread()) throw new IllegalStateException("Phantom dispatch off server thread");
            if (containsIdentity()) return true;
            try { add.invoke(null, server, task); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                rethrowFatal(failure);
                if (!containsIdentity()) {
                    FeedMePackages.LOGGER.warn("FMP Phantom task was not admitted; source remains owned by sender", failure);
                    return false;
                }
                FeedMePackages.LOGGER.warn("FMP Phantom task admitted before visual failure; flight remains owned by native transport", failure);
            }
            if (!containsIdentity()) return false;
            try { hud.invoke(null, server, task); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                rethrowFatal(failure);
                FeedMePackages.LOGGER.warn("FMP Phantom HUD failed after admission; flight is unaffected", failure);
            }
            return true;
        }
        private boolean containsIdentity() {
            for (Object registered : live) if (registered == task) return true;
            return false;
        }
    }

    private static void rethrowFatal(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        if (cause instanceof ThreadDeath fatal) throw fatal;
    }
}
