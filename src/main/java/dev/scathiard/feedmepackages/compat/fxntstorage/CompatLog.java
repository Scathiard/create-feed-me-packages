package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.FeedMePackages;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One production log line per reason: enough evidence to explain the compat layer without spamming. */
public final class CompatLog {
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();
    private CompatLog() {}

    /** Log once per distinct reason (a shape mismatch must not repeat itself every frame). */
    public static void once(String reason, String format, Object... arguments) {
        if (ONCE.add(reason)) FeedMePackages.LOGGER.info(format, arguments);
    }
    /** Whether a one-shot line was ever emitted - the injection probe test asserts on this. */
    public static boolean hasLogged(String reason) { return ONCE.contains(reason); }
    /** Log one line for an event that is inherently rare (a transfer click). */
    public static void compat(String message) {
        FeedMePackages.LOGGER.info(message);
    }
}
