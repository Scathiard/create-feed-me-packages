package dev.scathiard.feedmepackages.client;

import java.util.List;

/**
 * The plain, client-free part of {@link ClientNotice}: which sentences are spoken and how the missing list
 * is written. Kept free of Minecraft types on purpose, so the "a failure is never silent" rule is
 * unit-testable (the test source set has no game classes).
 */
public final class NoticeText {
    /** Shared prefix of every result sentence; the logistics panel uses the same namespace. */
    public static final String RESULT_PREFIX = "gui.create_feed_me_packages.result.";
    /** The ack-timeout sentence lives outside the {@code result.*} namespace. */
    public static final String TIMEOUT_KEY = "gui.create_feed_me_packages.timeout";
    private NoticeText() {}

    /** Translation key for a result code; never null, so a failure can never be silent. */
    public static String reasonKey(String code) {
        return RESULT_PREFIX + (code == null || code.isBlank() ? "invalid_request" : code);
    }
    public static String entryKey() { return RESULT_PREFIX + "missing_entry"; }
    public static String detailKey() { return RESULT_PREFIX + "missing_detail"; }

    /** One log/plain-text entry: "Iron Plate ×2". A nameless stack is still counted, never dropped. */
    public static String entryText(String name, int count) {
        return (name == null || name.isBlank() ? "?" : name) + " ×" + Math.max(0, count);
    }

    /** "Iron Plate ×2, Iron Ingot ×1"; "" when there is nothing to name. */
    public static String join(List<String> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder joined = new StringBuilder();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            if (joined.length() > 0) joined.append(", ");
            joined.append(entry);
        }
        return joined.toString();
    }
}
