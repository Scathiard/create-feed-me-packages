package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arrangement of cache cells. The user's report was "升级的时候，原本缓存里放号的物品位置会错位", so the
 * property that matters is: growing a level must never move a cell that already existed.
 */
class CacheGridTest {
    private static final int[] COUNTS = {9, 16, 24, 30, 36};
    private static final int[][] SHAPES = {{3, 3}, {4, 4}, {4, 6}, {5, 6}, {6, 6}};

    @Test void everyLevelsOwnRectangleIsFullyFilled() {
        for (int index = 0; index < COUNTS.length; index++) {
            int level = index + 1;
            CacheGrid grid = CacheGrid.forLevel(level);
            assertEquals(COUNTS[index], grid.count(), "count of level " + level);
            assertEquals(CacheLevel.of(level).slots(), grid.count(), "level " + level + " disagrees with the level table");
            assertEquals(SHAPES[index][0], grid.rows(), "rows of level " + level);
            assertEquals(SHAPES[index][1], grid.columns(), "columns of level " + level);
            Set<Integer> cells = new HashSet<>();
            for (int slot = 0; slot < grid.count(); slot++) {
                int cell = grid.row(slot) * grid.columns() + grid.column(slot);
                assertTrue(cells.add(cell), "two cells share a position at level " + level + " slot " + slot);
                assertEquals(slot, grid.slot(grid.row(slot), grid.column(slot)), "position/slot round trip");
            }
            assertEquals(grid.rows() * grid.columns(), cells.size(), "level " + level + " rectangle has holes");
        }
    }

    @Test void anUpgradeNeverMovesACellThatAlreadyExisted() {
        for (int level = 1; level < 5; level++) {
            CacheGrid before = CacheGrid.forLevel(level);
            CacheGrid after = CacheGrid.forLevel(level + 1);
            for (int slot = 0; slot < before.count(); slot++) {
                assertEquals(before.row(slot), after.row(slot), "row moved on " + level + " -> " + (level + 1) + " slot " + slot);
                assertEquals(before.column(slot), after.column(slot), "column moved on " + level + " -> " + (level + 1) + " slot " + slot);
            }
        }
    }

    @Test void anUpgradeOnlyAddsPositionsTheSmallerRectangleDidNotUse() {
        for (int level = 1; level < 5; level++) {
            CacheGrid before = CacheGrid.forLevel(level);
            CacheGrid after = CacheGrid.forLevel(level + 1);
            Set<String> old = new HashSet<>();
            for (int slot = 0; slot < before.count(); slot++) old.add(before.row(slot) + "," + before.column(slot));
            Set<String> added = new TreeSet<>();
            for (int slot = before.count(); slot < after.count(); slot++) added.add(after.row(slot) + "," + after.column(slot));
            assertEquals(after.count() - before.count(), added.size(), "new cells are not distinct");
            for (String cell : added) assertFalse(old.contains(cell), "new cell overlaps an old one: " + cell);
            Set<String> expected = new TreeSet<>();
            for (int row = 0; row < after.rows(); row++)
                for (int column = 0; column < after.columns(); column++)
                    if (!old.contains(row + "," + column)) expected.add(row + "," + column);
            assertEquals(expected, added, "growth was not the new row/column of the bigger rectangle");
        }
    }

    @Test void aCountOutsideTheShippedShapesStillArrangesEveryCell() {
        CacheGrid odd = CacheGrid.forCount(13);
        assertEquals(13, odd.count());
        Set<Integer> cells = new HashSet<>();
        for (int slot = 0; slot < odd.count(); slot++) {
            assertTrue(cells.add(odd.row(slot) * odd.columns() + odd.column(slot)));
            assertEquals(slot, odd.slot(odd.row(slot), odd.column(slot)));
        }
        assertSame(CacheGrid.forCount(36), CacheGrid.forCount(36), "the shipped shapes must be cached");
        assertEquals(0, CacheGrid.forCount(0).count());
    }
}
