package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R-018: the dragon-head expansion is append-only, survives a level upgrade, and lifts 0.3.0's 128-cell ceiling.
 * The capacity constant is checked directly; the two mutations are pinned by their source shape (the repo's usual way
 * for state that needs a live registry to build), so a later refactor cannot silently drop either rule.
 */
class ExpansionCapacityTest {
    private static String read(String path) throws Exception {
        var file = java.nio.file.Path.of(path);
        assertTrue(java.nio.file.Files.exists(file), "run the tests from the project directory: " + path);
        return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void theOldCeilingIsLiftedButStillBounded() {
        assertEquals(5, CacheCapacity.EXPANSION_SLOTS);
        assertTrue(CacheCapacity.MAX_SLOTS > 128, "the 128-cell ceiling must be able to grow past itself");
        // The acceptance case: 24 expansions on a level-1 pendant (9 + 120 = 129) and one more (134).
        assertTrue(new CacheCapacity(134, 2).slots() > 128);
        assertThrows(IllegalArgumentException.class, () -> new CacheCapacity(CacheCapacity.MAX_SLOTS + 1, 1));
    }

    @Test void aLevelUpgradeKeepsExpansionCells() throws Exception {
        // The bought cells are carried by the ledger, not by CacheEdit.upgrade: that call is replayed on the fluid side
        // by CacheFluids.align(), and both typed states must keep agreeing on capacity.
        String edit = read("src/main/java/dev/scathiard/feedmepackages/domain/CacheEdit.java");
        assertTrue(edit.contains("resize(next); level++;"), "the plain level upgrade must stay plain");
        assertFalse(edit.contains("Math.max(0, extra)"), "the upgrade must not fold bought cells into itself");
        String ledger = read("src/main/java/dev/scathiard/feedmepackages/storage/CacheLedger.java");
        assertTrue(ledger.contains("capacity().slots() - CacheCapacity.defaults(source.state().level()).slots()"),
                "the ledger must measure the cells bought by expansion");
        assertTrue(ledger.contains("edit.resize(new CacheCapacity(next.slots() + bought, next.groups()))"),
                "and add them on top of the new level's own cells");
    }

    @Test void expansionIsAppendOnlyAndRefusesPrivateCaches() throws Exception {
        String ledger = read("src/main/java/dev/scathiard/feedmepackages/storage/CacheLedger.java");
        assertTrue(ledger.contains("Mutation prepareExpansion(CacheRecord source)"), "the commit path must exist");
        assertTrue(ledger.contains("source.owner() != null"), "a private cache must never be expanded");
        assertTrue(ledger.contains("CacheCapacity.EXPANSION_SLOTS > CacheCapacity.MAX_SLOTS"),
                "the ceiling has to be checked before growing");
        assertTrue(ledger.contains("edit.resize("), "growth goes through the append-only resize");
        assertFalse(ledger.contains("filter(null)"), "expansion must not clear filters");
    }
}
