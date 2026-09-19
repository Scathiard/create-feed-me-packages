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

    // -------------------------------------------------------------------------------------------------
    // The confirmed rule for a drop that landed ON a cell: that cell is the first choice, and only when it
    // cannot take the item at all does the aim-free rule above decide. One rule, asked in one place.
    // -------------------------------------------------------------------------------------------------

    @Test void aDropOntoTheItemsOwnCellKeepsThatCellAsTheTarget() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, "", STONE, ""), 2, STONE);
        assertEquals(2, target.slot(), "the player aimed at this cell and it is this item's cell");
        assertEquals(CacheDropTarget.Outcome.AIMED_MATCH, target.outcome());
        assertTrue(target.hasTarget());
    }

    @Test void aDropOntoTheItemsOwnCellStaysThereEvenWhenItIsFull() {
        var target = CacheDropTarget.resolveDrop(full(3, 6, STONE), 3, STONE);
        assertEquals(3, target.slot(), "a full aimed cell is still the aimed cell; the server answers NO_SPACE");
        assertEquals(CacheDropTarget.Outcome.AIMED_MATCH, target.outcome());
    }

    @Test void aDropOntoAnEmptyCellKeepsItWhenNoOtherCellClaimsTheItem() {
        var target = CacheDropTarget.resolveDrop(cells(STONE, "", "", ""), 2, IRON);
        assertEquals(2, target.slot(), "aiming at a free cell must not be pushed to another free one");
        assertEquals(CacheDropTarget.Outcome.AIMED_EMPTY, target.outcome());
    }

    @Test void aDropOntoAnEmptyCellMovesToTheCellThatClaimsTheItemInsteadOfDuplicatingIt() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, "", STONE, ""), 1, STONE);
        assertEquals(2, target.slot(), "an empty aimed cell must not become a DUPLICATE_FILTER refusal");
        assertEquals(CacheDropTarget.Outcome.EXISTING_CELL, target.outcome());
    }

    @Test void aDropOntoAnotherItemsCellMovesToTheItemsOwnCell() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, STONE, ""), 0, STONE);
        assertEquals(1, target.slot(), "the aimed cell holds another item, so the item names its own cell");
        assertEquals(CacheDropTarget.Outcome.EXISTING_CELL, target.outcome());
    }

    @Test void aDropOntoAnotherItemsCellTakesTheFirstEmptyCellWhenTheItemIsNew() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, STONE, "", ""), 0, IRON);
        assertEquals(2, target.slot(), "a brand new item still takes the first empty cell");
        assertEquals(CacheDropTarget.Outcome.FIRST_EMPTY_CELL, target.outcome());
    }

    @Test void aDropOntoAnotherItemsCellWithNoRoomAtAllReportsNoTarget() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, STONE, IRON), 0, "minecraft:gold_ingot");
        assertFalse(target.hasTarget(), "nowhere to put it, so the caller must speak up");
        assertEquals(CacheDropTarget.Outcome.NO_TARGET, target.outcome());
    }

    @Test void aDropOntoNoCellIsExactlyTheAimFreeResolution() {
        for (var cells : List.of(cells(DIRT, "", STONE), cells(DIRT, STONE, IRON), cells("", "", ""))) {
            var free = CacheDropTarget.resolve(cells, STONE);
            var aimed = CacheDropTarget.resolveDrop(cells, -1, STONE);
            assertEquals(free.slot(), aimed.slot(), "-1 means the drop landed on no cell at all");
            assertEquals(free.outcome(), aimed.outcome());
        }
    }

    @Test void anAimedCellOutsideTheRangeFallsBackToTheAimFreeResolution() {
        var target = CacheDropTarget.resolveDrop(cells(DIRT, ""), 7, STONE);
        assertEquals(1, target.slot(), "an impossible aim is not a target: the aim-free rule decides");
        assertEquals(CacheDropTarget.Outcome.FIRST_EMPTY_CELL, target.outcome());
    }

    @Test void theAimedCellWinsLiterallyEvenIfHistoricalDataRepeatsAFilter() {
        var repeated = cells(STONE, STONE);
        assertEquals(1, CacheDropTarget.resolveDrop(repeated, 1, STONE).slot(),
                "the aimed cell wins; the server's DUPLICATE_FILTER stays the net");
        assertEquals(0, CacheDropTarget.resolveDrop(repeated, 0, STONE).slot());
        assertEquals(0, CacheDropTarget.resolve(repeated, STONE).slot(), "the aim-free rule still takes the lowest");
    }

    @Test void nothingCarriedIsNeverAnAimedDepositEither() {
        for (String carried : new String[]{null, ""})
            assertEquals(CacheDropTarget.Outcome.NO_TARGET,
                    CacheDropTarget.resolveDrop(cells(STONE, "", ""), 0, carried).outcome());
    }
}
