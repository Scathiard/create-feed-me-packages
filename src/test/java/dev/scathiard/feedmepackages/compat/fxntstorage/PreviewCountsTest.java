package dev.scathiard.feedmepackages.compat.fxntstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** F-5 gate 1: the three-source diagnostic must count each source correctly (fake numbers, no game). */
class PreviewCountsTest {
    private static final ToIntFunction<String> ONE_EACH = text -> 1;
    private static final List<String> INVENTORY = List.of("iron_plate", "iron_plate", "log");
    private static final List<String> BACKPACK = List.of("iron_plate");
    private static final List<String> LENT = List.of("iron_ingot", "iron_ingot");

    @Test void eachSourceIsCountedOnItsOwn() {
        assertEquals(2, PreviewCounts.sum(INVENTORY, "iron_plate"::equals, ONE_EACH));
        assertEquals(1, PreviewCounts.sum(BACKPACK, "iron_plate"::equals, ONE_EACH));
        assertEquals(0, PreviewCounts.sum(LENT, "iron_plate"::equals, ONE_EACH));
        assertEquals(2, PreviewCounts.sum(LENT, "iron_ingot"::equals, ONE_EACH));
    }

    @Test void anAmountFunctionIsHonoured() {
        assertEquals(20, PreviewCounts.sum(INVENTORY, "iron_plate"::equals, String::length));
    }

    @Test void theMaterialThatIsOnlyInTheCacheIsVisiblyOnlyInTheCache() {
        assertEquals(0, PreviewCounts.sum(INVENTORY, "iron_ingot"::equals, ONE_EACH));
        assertEquals(0, PreviewCounts.sum(BACKPACK, "iron_ingot"::equals, ONE_EACH));
        assertTrue(PreviewCounts.sum(LENT, "iron_ingot"::equals, ONE_EACH) > 0);
    }

    @Test void nothingIsCountedForAnEmptySource() {
        assertEquals(0, PreviewCounts.sum(null, text -> true, ONE_EACH));
        assertEquals(0, PreviewCounts.sum(List.of(), text -> true, ONE_EACH));
    }
}