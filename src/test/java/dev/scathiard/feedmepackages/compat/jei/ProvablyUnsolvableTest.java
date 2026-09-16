package dev.scathiard.feedmepackages.compat.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static dev.scathiard.feedmepackages.compat.jei.PreviewPolicy.Decision.*;
import static dev.scathiard.feedmepackages.compat.jei.PreviewPolicy.Estimate.*;

/**
 * The successor of the v2 "provably unsolvable" boundary. v3 narrows it to a rule the player can read off the
 * screen: a miss that can be named refuses, a miss that cannot be named is released, and everything we can
 * prove here and now refuses. Kept as its own file because the safety valve ("cannot name it, cannot refuse
 * it") must survive any future re-tuning of the preview.
 */
class ProvablyUnsolvableTest {
    @Test void aMissThatCannotBeNamedIsNeverRefused() {
        assertEquals(ALLOW_UNPROVABLE, PreviewPolicy.decide(false, MISSING, false));
        assertNull(PreviewPolicy.reason(ALLOW_UNPROVABLE, MISSING));
    }

    @Test void aMissThatCanBeNamedIsRefusedWithASentence() {
        assertEquals(REFUSE, PreviewPolicy.decide(false, MISSING, true));
        assertEquals("missing_material", PreviewPolicy.reason(REFUSE, MISSING));
    }
}
