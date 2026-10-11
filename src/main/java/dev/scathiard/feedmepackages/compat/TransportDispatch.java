package dev.scathiard.feedmepackages.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Isolated, reflection-based dispatch of a return package to its address.
 *  The transport add-ons are optional and not on the compile classpath; every call is guarded by a
 *  class-presence probe so the core mod loads and behaves identically when they are absent. */
public final class TransportDispatch {
    private TransportDispatch() {}
    public enum Outcome { NOT_SENT, SENT, UNCERTAIN }
    /** A native call that throws after entry is not evidence of rejection. Never fallback on UNCERTAIN. */
    public record Result(Outcome outcome, String transport, Throwable failure) {
        public boolean dispatched() { return outcome == Outcome.SENT; }
        static Result known(boolean sent, String transport) { return new Result(sent ? Outcome.SENT : Outcome.NOT_SENT, transport, null); }
    }

    private static boolean present(String className) {
        try { Class.forName(className, false, TransportDispatch.class.getClassLoader()); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    /** Try to send a package with a paper plane. Returns dispatched only when addPlane accepted it. */
    public static Result paperPlane(ServerLevel level, ItemStack box, Vec3 pos) {
        boolean entered = false;
        try {
            if (!present("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager")) return Result.known(false, "paper");
            Class<?> manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            Method addPlane = manager.getMethod("addPlane", net.minecraft.world.level.Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class);
            entered = true;
            Object accepted = addPlane.invoke(null, level, pos, 0f, 0f, box, false);
            return accepted instanceof Boolean value ? Result.known(value, "paper")
                    : new Result(Outcome.UNCERTAIN, "paper", new IllegalStateException("Unexpected native paper receipt"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return failed(entered, "paper", failure);
        }
    }

    /** Try to send a package with a transport bee. dispatched=true means native transport accepted it.
     *  Supply the cache's actual network for native address lookup; fallback and later target reselection
     *  remain the transport mod's responsibility. */
    public static Result bee(ServerLevel level, ItemStack box, BlockPos spawnPos, java.util.UUID network) {
        boolean entered = false;
        try {
            if (!present("de.theidler.create_mobile_packages.robo.RoboManager")) return Result.known(false, "bee");
            // newRobo always returns a UUID, even when no destination can receive the cargo.
            // Ask an unregistered native probe to resolve its target before handing over any real stock.
            // This keeps native fallback/reselection semantics and creates no entity or transport record.
            Class<?> robo = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            Object probe = robo.getConstructor(ServerLevel.class, java.util.UUID.class, ItemStack.class, BlockPos.class, java.util.UUID.class)
                    .newInstance(level, java.util.UUID.randomUUID(), box.copy(), spawnPos, network);
            if (robo.getMethod("getTargetPosition").invoke(probe) == null) return Result.known(false, "bee");
            Object target = robo.getMethod("getTarget").invoke(probe);
            Class<?> targetType = Class.forName("de.theidler.create_mobile_packages.robo.RoboTarget");
            if (target == null || !Boolean.TRUE.equals(targetType.getMethod("isValid", robo).invoke(target, probe)))
                return Result.known(false, "bee");
            Class<?> roboManager = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            Object manager = roboManager.getMethod("get", ServerLevel.class).invoke(null, level);
            Method newRobo = roboManager.getMethod("newRobo", ServerLevel.class, ItemStack.class, BlockPos.class, java.util.UUID.class,
                    float.class, BlockPos.class, boolean.class);
            entered = true;
            Object uuid = newRobo.invoke(manager, level, box, spawnPos, network, 0f, null, false);
            return uuid instanceof java.util.UUID ? Result.known(true, "bee")
                    : new Result(Outcome.UNCERTAIN, "bee", new IllegalStateException("Unexpected native bee receipt"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return failed(entered, "bee", failure);
        }
    }
    private static Result failed(boolean entered, String transport, Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        if (cause instanceof ThreadDeath fatal) throw fatal;
        // Reflection itself refused entry: no native package could have been handed over.
        boolean refusedEntry = failure instanceof IllegalAccessException || failure instanceof IllegalArgumentException;
        return new Result(entered && !refusedEntry ? Outcome.UNCERTAIN : Outcome.NOT_SENT, transport, cause);
    }
}
