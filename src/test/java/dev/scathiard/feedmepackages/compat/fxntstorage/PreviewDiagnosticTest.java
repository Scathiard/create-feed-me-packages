package dev.scathiard.feedmepackages.compat.fxntstorage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** F-7 gate 1: the preview diagnostic must actually SPEAK - on any change, and at most every two seconds. */
class PreviewDiagnosticTest {
    @Test void aChangedReadingAlwaysPrints() {
        assertTrue(PreviewDiagnostic.shouldPrint("inventory=3", 1000, null, 0), "the first reading must print");
        assertTrue(PreviewDiagnostic.shouldPrint("inventory=2", 1200, "inventory=3", 1000), "a change must print immediately");
    }

    @Test void anIdenticalReadingIsThrottledButNotSilenced() {
        assertFalse(PreviewDiagnostic.shouldPrint("inventory=3", 1500, "inventory=3", 1000), "unchanged within two seconds stays quiet");
        assertTrue(PreviewDiagnostic.shouldPrint("inventory=3", 3000, "inventory=3", 1000), "unchanged must still be repeated eventually");
    }
}