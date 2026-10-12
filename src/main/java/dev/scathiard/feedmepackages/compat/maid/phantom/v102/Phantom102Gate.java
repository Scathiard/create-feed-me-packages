package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.neoforged.fml.ModList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only capability gate for Create Phantom 1.0.2 (T2-b). It <b>gathers</b> what is actually present, asks
 * {@link Phantom102Shape} for the verdict and logs <b>exactly one</b> stable line. It injects nothing, registers
 * nothing and touches no game state; when anything is off, the whole feature stays disabled.
 *
 * <p>It is deliberately conservative: a member that cannot be resolved counts as missing, so an unexpected shape
 * always fails <i>closed</i>. The dev-time counterpart of this check is the {@code javap -p -s} table in the plan
 * (section 14); this class is what the running game consults.
 */
public final class Phantom102Gate {
    private Phantom102Gate() {}
    private static final String PACKAGE = "com.yision.phantom.logistics.courier.";
    private static volatile String verdict;
    private static volatile boolean logged;

    /** The cached verdict, running the check once. Never throws: any failure means "disabled". */
    public static String verdict() {
        String current = verdict;
        if (current != null) return current;
        synchronized (Phantom102Gate.class) {
            if (verdict == null) verdict = compute();
            return verdict;
        }
    }

    public static boolean enabled() { return verdict().equals("enabled"); }

    /** True when the loaded artifact has the required shape <b>and</b> the injected flags reported success. */
    public static boolean usable(boolean mixinsApplied) { return enabled() && mixinsApplied; }

    private static String compute() {
        try {
            boolean loaded = ModList.get().isLoaded(Phantom102Shape.MOD_ID);
            if (!loaded) return "disabled reason=mod-not-loaded";
            String version = ModList.get().getModContainerById(Phantom102Shape.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString()).orElse(null);
            String sha256 = artifactSha256();
            return Phantom102Shape.verdict(true, version, sha256, actualMembers());
        } catch (Throwable failure) {
            FeedMePackages.LOGGER.warn("FMP phantom102 gate probe failed; feature stays disabled", failure);
            return "disabled reason=probe-failed";
        }
    }

    /** Logs the gate line once per session, whatever the verdict. */
    public static void logOnce() {
        if (logged) return;
        synchronized (Phantom102Gate.class) {
            if (logged) return;
            logged = true;
            FeedMePackages.LOGGER.info(Phantom102Shape.logLine(verdict()));
        }
    }

    private static String artifactSha256() throws Exception {
        var file = ModList.get().getModFileById(Phantom102Shape.MOD_ID);
        if (file == null) return null;
        Path path = file.getFile().getFilePath();
        if (path == null || !Files.isRegularFile(path)) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path); DigestInputStream stream = new DigestInputStream(in, digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /** Descriptors actually found, keyed exactly like {@link Phantom102Shape#members()}. Unresolvable = absent. */
    private static Map<String, String> actualMembers() {
        Map<String, String> actual = new HashMap<>();
        for (String key : Phantom102Shape.members().keySet()) {
            String descriptor = resolve(key);
            if (descriptor != null) actual.put(key, descriptor);
        }
        return actual;
    }

    private static String resolve(String key) {
        try {
            boolean field = key.startsWith("field ");
            String plain = field ? key.substring("field ".length()) : key;
            int hash = plain.indexOf('#');
            if (hash < 0) return null;
            String simpleClass = plain.substring(0, hash);
            String member = plain.substring(hash + 1);
            Class<?> type = Class.forName(PACKAGE + simpleClass, false, Phantom102Gate.class.getClassLoader());
            if (field) {
                Field declared = type.getDeclaredField(member);
                return descriptor(declared.getType());
            }
            int arity = -1;
            if (member.endsWith("3") && !member.equals("<init>")) {
                arity = 3;
                member = member.substring(0, member.length() - 1);
            }
            if (member.equals("<init>")) {
                for (Constructor<?> constructor : type.getDeclaredConstructors())
                    if (constructor.getParameterCount() == 5) return methodDescriptor(constructor.getParameterTypes(), void.class);
                return null;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(member)) continue;
                if (arity >= 0 && method.getParameterCount() != arity) continue;
                if (arity < 0 && method.getParameterCount() != 2) continue;
                return methodDescriptor(method.getParameterTypes(), method.getReturnType());
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static String methodDescriptor(Class<?>[] parameters, Class<?> result) {
        StringBuilder text = new StringBuilder("(");
        for (Class<?> parameter : parameters) text.append(descriptor(parameter));
        return text.append(')').append(descriptor(result)).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
