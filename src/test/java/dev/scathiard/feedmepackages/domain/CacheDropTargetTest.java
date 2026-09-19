package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Drop it anywhere in the panel" must be exact: a cache holds one cell per exact item, so the item names it. */
class CacheDropTargetTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String IRON = "minecraft:iron_ingot";

    private static List<CacheDropTarget.Cell> cells(String... templates) {
        List<CacheDropTarget.Cell> cells = new ArrayList<>();
        for (String template : templates) cells.add(new CacheDropTarget.Cell(template, false));
        return cells;
    }
    private static List<CacheDropTarget.Cell> full(int index, int size, String template) {
        List<CacheDropTarget.Cell> cells = cells(new String[size]);
        cells.set(index, new CacheDropTarget.Cell(template, true));
        return cells;
    }

    @Test void aCellThatAlreadyFiltersTheItemWins() {
        var target = CacheDropTarget.resolve(cells(DIRT, "", STONE, ""), STONE);
        assertEquals(2, target.slot());
        assertEquals(CacheDropTarget.Outcome.EXISTING_CELL, target.outcome());
        assertTrue(target.hasTarget());
    }

    @Test void otherwiseTheFirstEmptyCellTakesIt() {
        var target = CacheDropTarget.resolve(cells(DIRT, STONE, "", "", ""), IRON);
        assertEquals(2, target.slot(), "the first empty cell in index order must win");
        assertEquals(CacheDropTarget.Outcome.FIRST_EMPTY_CELL, target.outcome());
    }

    @Test void aFullMatchingCellIsStillTheTargetAndIsReportedAsFull() {
        var target = CacheDropTarget.resolve(full(1, 4, STONE), STONE);
        assertEquals(1, target.slot(), "a full cell must not send the stack to a different cell");
        assertEquals(CacheDropTarget.Outcome.EXISTING_CELL_FULL, target.outcome());
    }

    @Test void withNoRoomAndNoMatchThereIsNoTargetSoTheCallerMustSpeak() {
        var target = CacheDropTarget.resolve(cells(DIRT, STONE, IRON), "minecraft:gold_ingot");
        assertEquals(-1, target.slot());
        assertEquals(CacheDropTarget.Outcome.NO_TARGET, target.outcome());
        assertFalse(target.hasTarget());
    }

    @Test void nothingCarriedIsNeverADeposit() {
        for (String carried : new String[]{null, ""}) {
            var target = CacheDropTarget.resolve(cells(STONE, ""), carried);
            assertEquals(CacheDropTarget.Outcome.NO_TARGET, target.outcome());
        }
    }

    @Test void theResolutionIsDeterministicEvenIfHistoricalDataSomehowRepeatsAFilter() {
        var repeated = cells(STONE, STONE, "");
        assertEquals(0, CacheDropTarget.resolve(repeated, STONE).slot(), "the lowest index must always win");
        assertEquals(0, CacheDropTarget.resolve(repeated, STONE).slot());
        var empty = cells("", "", DIRT);
        assertEquals(0, CacheDropTarget.resolve(empty, IRON).slot(), "the lowest empty index must always win");
        assertEquals(CacheDropTarget.Outcome.FIRST_EMPTY_CELL, CacheDropTarget.resolve(empty, IRON).outcome());
    }
}
