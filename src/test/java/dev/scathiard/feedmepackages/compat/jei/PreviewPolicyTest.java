package dev.scathiard.feedmepackages.compat.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static dev.scathiard.feedmepackages.compat.jei.PreviewPolicy.Decision.*;
import static dev.scathiard.feedmepackages.compat.jei.PreviewPolicy.Estimate.*;

/**
 * Preview rule v3: "clickable means it will work; if it cannot work, grey it and say what is missing."
 * The two cases that must never be swapped - MISSING that CAN be named refuses, MISSING that CANNOT be
 * named releases - are pinned here, together with the full truth table.
 */
class PreviewPolicyTest {
    @Test void aNamedMissRefusesSoTheButtonNeverLies() {
        assertEquals(REFUSE, PreviewPolicy.decide(false, MISSING, true));
        assertEquals("missing_material", PreviewPolicy.reason(REFUSE, MISSING));
        // ...and the refusal always carries a sentence key.
        assertNotNull(PreviewPolicy.reason(PreviewPolicy.decide(false, MISSING, true), MISSING));
    }

    @Test void aMissWeCannotNameIsReleased() {
        // The one safety valve: what cannot be named cannot honestly justify a grey button.
        assertEquals(ALLOW_UNPROVABLE, PreviewPolicy.decide(false, MISSING, false));
        assertNull(PreviewPolicy.reason(ALLOW_UNPROVABLE, MISSING));
        assertNull(PreviewPolicy.reason(ALLOW_NATIVE, MISSING));
        assertNull(PreviewPolicy.reason(ALLOW_ESTIMATE_OK, OK));
    }

    @Test void whatWeCanProveHereAndNowRefuses() {
        for (PreviewPolicy.Estimate estimate : new PreviewPolicy.Estimate[]{NO_SPACE, UNSUPPORTED, TOO_COMPLEX}) {
            assertEquals(REFUSE, PreviewPolicy.decide(false, estimate, false), estimate.name());
            assertNotNull(PreviewPolicy.reason(REFUSE, estimate), estimate.name());
        }
        assertEquals("no_space", PreviewPolicy.reason(REFUSE, NO_SPACE));
        assertEquals("unsupported_recipe", PreviewPolicy.reason(REFUSE, UNSUPPORTED));
        assertEquals("too_complex", PreviewPolicy.reason(REFUSE, TOO_COMPLEX));
    }

    @Test void aSuccessfulNativeOrClientEstimateIsReleased() {
        assertEquals(ALLOW_NATIVE, PreviewPolicy.decide(true, MISSING, true));
        assertEquals(ALLOW_NATIVE, PreviewPolicy.decide(true, null, false));
        assertEquals(ALLOW_ESTIMATE_OK, PreviewPolicy.decide(false, OK, true));
        assertEquals(ALLOW_ESTIMATE_OK, PreviewPolicy.decide(false, OK, false));
    }

    @Test void anEstimateWeNeverGotIsReleased() {
        assertEquals(ALLOW_UNPROVABLE, PreviewPolicy.decide(false, INACTIVE, false));
        assertEquals(ALLOW_UNPROVABLE, PreviewPolicy.decide(false, INACTIVE, true));
        assertEquals(ALLOW_UNPROVABLE, PreviewPolicy.decide(false, null, false));
        assertNull(PreviewPolicy.reason(ALLOW_UNPROVABLE, INACTIVE));
    }

    @Test void theWholeTableMatchesTheRule() {
        for (boolean nativePreviewOk : new boolean[]{true, false}) {
            for (PreviewPolicy.Estimate estimate : new PreviewPolicy.Estimate[]{null, OK, MISSING, NO_SPACE, UNSUPPORTED, TOO_COMPLEX, INACTIVE}) {
                for (boolean named : new boolean[]{true, false}) {
                    PreviewPolicy.Decision expected;
                    if (nativePreviewOk) expected = ALLOW_NATIVE;
                    else if (estimate == null || estimate == INACTIVE) expected = ALLOW_UNPROVABLE;
                    else if (estimate == OK) expected = ALLOW_ESTIMATE_OK;
                    else if (estimate == MISSING) expected = named ? REFUSE : ALLOW_UNPROVABLE;
                    else expected = REFUSE;
                    assertEquals(expected, PreviewPolicy.decide(nativePreviewOk, estimate, named), nativePreviewOk + "/" + estimate + "/" + named);
                    assertEquals(expected == REFUSE, PreviewPolicy.reason(expected, estimate) != null, nativePreviewOk + "/" + estimate + "/" + named);
                }
            }
        }
    }
}
