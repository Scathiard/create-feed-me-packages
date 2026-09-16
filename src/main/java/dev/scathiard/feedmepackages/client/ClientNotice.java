package dev.scathiard.feedmepackages.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The one place a recipe-viewer transfer outcome is spoken: the ACTION BAR.
 *
 * <p>Why the action bar and not the panel: a JEI recipe page covers the container screen, and the logistics
 * panel is only rendered for its own screen ({@code LogisticsPanel.current(...)}), so a panel-only notice can
 * be invisible exactly when the player asked for a transfer from JEI. The action bar is screen-independent.
 *
 * <p>Silence rules: a success says nothing (the materials appearing is the message), a failure ALWAYS says
 * something - {@link #reasonKey(String)} is never blank - and the "what is missing" detail is explanatory
 * only: it is computed from what this client can see and never decides anything.
 *
 * <p>The decisions live in {@link NoticeText} (client-free, unit-tested); this class only renders them.
 */
public final class ClientNotice {
    /** @see NoticeText#RESULT_PREFIX */
    public static final String RESULT_PREFIX = NoticeText.RESULT_PREFIX;
    private ClientNotice() {}

    /** @see NoticeText#reasonKey(String) */
    public static String reasonKey(String code) { return NoticeText.reasonKey(code); }

    /** One "what is missing" entry; the name is already a client-rendered component. */
    public static Component entry(Component name, int count) {
        return Component.translatable(NoticeText.entryKey(), name, Math.max(0, count));
    }

    /** "missing: a x2, b x1"; null when this client has nothing concrete to name (then only the reason is said). */
    public static Component detail(List<Component> entries) {
        if (entries == null || entries.isEmpty()) return null;
        return Component.translatable(NoticeText.detailKey(), NoticeText.join(entries.stream().map(Component::getString).toList()));
    }

    /** The full action-bar sentence: our reason, plus the (best-effort) missing list when we can name it. */
    public static Component compose(String code, List<Component> entries) {
        return composeKey(NoticeText.reasonKey(code), entries);
    }

    /** Same, for the rare sentence that does not live in the {@code result.*} namespace (e.g. the ack timeout). */
    public static Component composeKey(String fullKey, List<Component> entries) {
        Component reason = Component.translatable(fullKey == null || fullKey.isBlank() ? NoticeText.reasonKey(null) : fullKey);
        Component detail = detail(entries);
        return detail == null ? reason : Component.empty().append(reason).append(detail);
    }

    /** Show a transfer outcome on the action bar - the only spot visible over JEI's recipe page. */
    public static void speak(String code, List<Component> entries) {
        speakKey(NoticeText.reasonKey(code), entries);
    }

    /** Show an explicit sentence key on the action bar. */
    public static void speakKey(String fullKey, List<Component> entries) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(composeKey(fullKey, entries), true);
    }
}
