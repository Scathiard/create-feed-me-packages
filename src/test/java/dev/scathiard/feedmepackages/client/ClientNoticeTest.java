package dev.scathiard.feedmepackages.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The action-bar path is the only place a JEI-initiated failure becomes visible, so the decisions behind
 * {@link ClientNotice} are plain (client-free) functions and are asserted here: a failure always produces a
 * sentence of ours, an unknown code still produces one, and an empty missing list adds nothing.
 */
class ClientNoticeTest {
    @Test void aFailureIsNeverSilentAndAlwaysUsesOurOwnSentence() {
        for (String code : new String[]{"missing_material", "panel_not_ready", "unbound", "inactive", "not_active", "no_space", "stale"}) {
            assertEquals("gui.create_feed_me_packages.result." + code, NoticeText.reasonKey(code));
        }
        // An unknown or absent code must still produce something visible rather than nothing.
        assertEquals("gui.create_feed_me_packages.result.invalid_request", NoticeText.reasonKey(null));
        assertEquals("gui.create_feed_me_packages.result.invalid_request", NoticeText.reasonKey("  "));
        assertFalse(NoticeText.reasonKey(null).isBlank());
    }

    @Test void theDetailAndEntrySentencesLiveInOurNamespace() {
        assertEquals("gui.create_feed_me_packages.result.missing_entry", NoticeText.entryKey());
        assertEquals("gui.create_feed_me_packages.result.missing_detail", NoticeText.detailKey());
        // The timeout sentence is the one legitimate exception and is spelled out, not derived.
        assertEquals("gui.create_feed_me_packages.timeout", NoticeText.TIMEOUT_KEY);
    }

    @Test void nothingToNameAddsNothing() {
        assertEquals("", NoticeText.join(List.of()));
        assertEquals("", NoticeText.join(null));
        assertEquals("", NoticeText.join(List.of("", "  ")));
    }

    @Test void entriesAreNamedWithTheCountAndStayReadable() {
        assertEquals("Iron Plate ×2", NoticeText.entryText("Iron Plate", 2));
        assertEquals("Iron Plate ×2, Iron Ingot ×1", NoticeText.join(List.of(NoticeText.entryText("Iron Plate", 2), NoticeText.entryText("Iron Ingot", 1))));
        // A nameless stack is still counted, and a negative count can never promise material.
        assertEquals("? ×1", NoticeText.entryText("  ", 1));
        assertEquals("Iron Plate ×0", NoticeText.entryText("Iron Plate", -3));
    }
}
