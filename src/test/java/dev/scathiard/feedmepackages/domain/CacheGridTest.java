package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arrangement of cache cells. Two user requirements are pinned here: growing a level must never move a
 * cell that already existed ("升级的时候，原本缓存里放号的物品位置会错位"), and the panel must keep the 0.2.2
 * layout ("面板排列变了那不行，要遵从我原来的面板设计").
 *
 * <p>0.2.2's algorithm is copied in below as the referee (from {@code main}, {@code client/PanelLayout.compute}):
 * {@code columns = max(2, ceil(count / 6))}, at most 6 rows, filled row-major ({@code slot = row * columns + column}).
 */
class CacheGridTest {
    private static final int[] COUNTS = {9, 16, 24, 30, 36};

    /** 0.2.2's own arrangement: {@code [slot][0] = row}, {@code [slot][1] = column}. */
    private static int[][] zeroTwoTwo(int count) {
        int columns = zeroTwoTwoColumns(count);
        int[][] cells = new int[count][2];
        for (int slot = 0; slot < count; slot++) {
            cells[slot][0] = slot / columns;
            cells[slot][1] = slot % columns;
        }
        return cells;
    }
    private static int zeroTwoTwoColumns(int count) { return Math.max(2, (Math.max(0, count) + 5) / 6); }
    private static int zeroTwoTwoRows(int count) {
        int columns = zeroTwoTwoColumns(count);
        return Math.max(1, (Math.max(0, count) + columns - 1) / columns);
    }
    private static String at(int row, int column) { return row + "," + column; }

    @Test void everyLevelKeepsTheZeroTwoTwoPanelBox() {
        for (int index = 0; index < COUNTS.length; index++) {
            int level = index + 1;
            int count = COUNTS[index];
            CacheGrid grid = CacheGrid.forLevel(level);
            assertEquals(count, grid.count(), "count of level " + level);
            assertEquals(CacheLevel.of(level).slots(), grid.count(), "level " + level + " disagrees with the level table");
            assertEquals(zeroTwoTwoRows(count), grid.rows(), "rows of level " + level + " (0.2.2 box)");
            assertEquals(zeroTwoTwoColumns(count), grid.columns(), "columns of level " + level + " (0.2.2 box)");
        }
    }

    @Test void everyLevelOccupiesExactlyThePositionsZeroTwoTwoUsed() {
        for (int index = 0; index < COUNTS.length; index++) {
            int level = index + 1;
            int count = COUNTS[index];
            CacheGrid grid = CacheGrid.forLevel(level);
            Set<String> actual = new TreeSet<>();
            for (int slot = 0; slot < count; slot++) {
                assertTrue(actual.add(at(grid.row(slot), grid.column(slot))),
                        "two cells share a position at level " + level + " slot " + slot);
                assertEquals(slot, grid.slot(grid.row(slot), grid.column(slot)), "position/slot round trip");
            }
            Set<String> expected = new TreeSet<>();
            for (int[] cell : zeroTwoTwo(count)) expected.add(at(cell[0], cell[1]));
            assertEquals(expected, actual, "level " + level + " does not occupy 0.2.2's positions");
        }
    }

    @Test void levelOneMatchesZeroTwoTwoCellForCell() {
        CacheGrid grid = CacheGrid.forLevel(1);
        int[][] design = zeroTwoTwo(9);
        for (int slot = 0; slot < design.length; slot++) {
            assertEquals(design[slot][0], grid.row(slot), "level 1 row of slot " + slot);
            assertEquals(design[slot][1], grid.column(slot), "level 1 column of slot " + slot);
        }
    }

    @Test void anUpgradeNeverMovesACellThatAlreadyExisted() {
        for (int level = 1; level < COUNTS.length; level++) {
            CacheGrid before = CacheGrid.forLevel(level);
            CacheGrid after = CacheGrid.forLevel(level + 1);
            for (int slot = 0; slot < before.count(); slot++) {
                assertEquals(before.row(slot), after.row(slot), "row moved on " + level + " -> " + (level + 1) + " slot " + slot);
                assertEquals(before.column(slot), after.column(slot), "column moved on " + level + " -> " + (level + 1) + " slot " + slot);
            }
        }
    }

    @Test void anUpgradeOnlyAddsPositionsTheSmallerLevelDidNotUse() {
        for (int level = 1; level < COUNTS.length; level++) {
            int beforeCount = COUNTS[level - 1];
            int afterCount = COUNTS[level];
            CacheGrid before = CacheGrid.forLevel(level);
            CacheGrid after = CacheGrid.forLevel(level + 1);
            Set<String> old = new HashSet<>();
            for (int slot = 0; slot < before.count(); slot++) old.add(at(before.row(slot), before.column(slot)));
            Set<String> added = new TreeSet<>();
            for (int slot = beforeCount; slot < afterCount; slot++) {
                added.add(at(after.row(slot), after.column(slot)));
            }
            assertEquals(afterCount - beforeCount, added.size(), "new cells are not distinct");
            for (String cell : added) assertFalse(old.contains(cell), "new cell overlaps an old one: " + cell);
            Set<String> union = new TreeSet<>(old);
            union.addAll(added);
            Set<String> expected = new TreeSet<>();
            for (int[] cell : zeroTwoTwo(afterCount)) expected.add(at(cell[0], cell[1]));
            assertEquals(expected, union, "the union of both levels is not the bigger 0.2.2 arrangement");
        }
    }

    @Test void futurePluginSlotsGrowTheTableWithoutMovingAnything() {
        CacheGrid base = CacheGrid.forLevel(5);
        CacheGrid grown = base.withExtraSlots(10);
        assertEquals(46, grown.count(), "36 shipped cells + 10 future plugin cells");
        for (int slot = 0; slot < base.count(); slot++) {
            assertEquals(base.row(slot), grown.row(slot), "corner row of slot " + slot);
            assertEquals(base.column(slot), grown.column(slot), "corner column of slot " + slot);
        }
        Set<String> used = new HashSet<>();
        for (int slot = 0; slot < grown.count(); slot++) {
            assertTrue(used.add(at(grown.row(slot), grown.column(slot))), "duplicate position for slot " + slot);
            assertEquals(slot, grown.slot(grown.row(slot), grown.column(slot)));
        }
        assertEquals(8, grown.columns(), "10 new cells = one full column plus one of four");
        assertEquals(6, grown.rows(), "a full column is 6 rows tall");
        assertSame(base, base.withExtraSlots(0), "no extra slots must not copy the table");
    }

    @Test void aCountOutsideTheShippedLevelsFallsBackToTheZeroTwoTwoArrangement() {
        for (int count : new int[]{0, 1, 13, 40}) {
            CacheGrid odd = CacheGrid.forCount(count);
            assertEquals(count, odd.count());
            Set<Integer> cells = new HashSet<>();
            for (int slot = 0; slot < odd.count(); slot++) {
                assertTrue(cells.add(odd.row(slot) * odd.columns() + odd.column(slot)));
                assertEquals(slot, odd.slot(odd.row(slot), odd.column(slot)));
            }
            assertEquals(zeroTwoTwoColumns(count), odd.columns(), "fallback columns for count " + count);
            assertEquals(zeroTwoTwoRows(count), odd.rows(), "fallback rows for count " + count);
        }
        assertSame(CacheGrid.forCount(36), CacheGrid.forCount(36), "the shipped levels must be cached");
        assertEquals(0, CacheGrid.forCount(0).count());
    }
}
