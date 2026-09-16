package dev.scathiard.feedmepackages.compat.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The preview releases everything to the server except the one provable case. That boundary is the fix for
 * "a stocked cache looked empty and the button was greyed", so it is pinned here.
 */
class ProvablyUnsolvableTest {
    @Test void onlyAnEmptyCacheViewWithAnAgreeingMissIsProvable() {
        assertTrue(PreviewPolicy.provablyUnsolvable(true, true, true));
        // No cache view: this client cannot speak for the cache.
        assertFalse(PreviewPolicy.provablyUnsolvable(false, true, true));
        // A cache view that still holds something is never "provable".
        assertFalse(PreviewPolicy.provablyUnsolvable(true, true, false));
    }

    @Test void everyOtherCombinationIsReleased() {
        for (boolean hasCacheView : new boolean[]{true, false}) {
            for (boolean estimateMissed : new boolean[]{true, false}) {
                for (boolean cacheViewEmpty : new boolean[]{true, false}) {
                    boolean provable = hasCacheView && estimateMissed && cacheViewEmpty;
                    assertEquals(provable, PreviewPolicy.provablyUnsolvable(hasCacheView, estimateMissed, cacheViewEmpty));
                }
            }
            // An estimate that did not miss is never provable, even with an empty cache view.
            assertFalse(PreviewPolicy.provablyUnsolvable(hasCacheView, false, true));
        }
    }
}
