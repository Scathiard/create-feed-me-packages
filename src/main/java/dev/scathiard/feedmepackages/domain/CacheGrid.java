package dev.scathiard.feedmepackages.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The panel arrangement of a cache level: the fixed (column, row) coordinate of every cell index
 * (origin (0, 0) at the panel's top-left corner).
 *
 * <p><b>Two requirements, both from the user.</b> (1) "升级的时候，原本缓存里放好的物品位置会错位" —
 * growing a level must never move a cell that already existed, so <b>a cell index keeps its (column, row)
 * for good</b>. (2) "面板排列变了那不行，要遵从我原来的面板设计" — the panel must keep the 0.2.2 layout, so
 * this table is built such that at every level the set of occupied coordinates is exactly the set 0.2.2's own
 * algorithm produced: {@code columns = max(2, ceil(count / 6))}, at most 6 rows per column, filled row-major.
 *
 * <p><b>A fixed table, not a formula.</b> A formula that re-derives the columns from the cell count re-numbers
 * the cells on every upgrade; that is exactly what used to move 7/9, 13/16, 20/24 and 25/30 of the old cells.
 * Here the coordinate of an index is decided once, when the index is first handed out, and never recomputed.
 * The levels' occupied sets are nested (9 ⊂ 16 ⊂ 24 ⊂ 30 ⊂ 36), so the first {@code slots(N)} coordinates of
 * the table are exactly level N's 0.2.2 layout — both requirements hold at the same time.
 *
 * <p>Level 1's index → position mapping is cell-for-cell identical to 0.2.2's. From level 2 on the <i>set</i>
 * of occupied positions is identical (same columns, same rows, same panel box) but the index → position order
 * differs: 0.2.2 renumbered the cells at every level, and a stable order cannot also be that per-level order.
 * The consequence for a save made under 0.2.2 is one single repaint in the new order; nothing moves afterwards.
 *
 * <p>The semantics are the fixed-coordinate semantics the 0.3 plugin grid already uses
 * ({@code docs/proposals/2026-09-12-0.3-D组插件框架实现方案.md} §2.2: "坐标一旦分配给某个索引就永不改变，扩容
 * 只是表里多出现几个位置"), so plugins can keep one convention instead of two.
 *
 * <p><b>Nothing about the stored data changes.</b> The ledger keeps a positional list whose length must equal
 * {@link CacheLevel#slots()} and {@code CacheEdit.upgrade()} still only appends empty cells at the end, so an
 * index still means exactly "the Nth cell": no migration, no re-ordering, {@code SCHEMA} untouched.
 *
 * <p>Shipped table (per level: cells, columns, rows = the 0.2.2 panel box):
 * <pre>
 *   L1  9 cells in 2 columns (5/4) x 5 rows    L2 16 cells in 3 columns (6/5/5) x 6 rows
 *   L3 24 cells in 4 columns (6/6/6/6) x 6     L4 30 cells in 5 columns (6x5)   x 6
 *   L5 36 cells in 6 columns (6x6) x 6
 * </pre>
 */
public final class CacheGrid {
    /** Most rows a column may hold; 0.2.2's panel bound ({@code PanelLayout.MAX_ROWS}). */
    public static final int MAX_ROWS = 6;
    /** 0.2.2 never draws fewer than two columns, even for a level that would fit into one. */
    public static final int MIN_COLUMNS = 2;

    private static final CacheGrid[] CANONICAL = build();

    private final int count;
    private final int rows;
    private final int columns;
    /** Packed coordinate ({@code column * MAX_ROWS + row}) per cell index. */
    private final int[] cellOfSlot;
    /** Cell index at a packed coordinate, or -1 when that position carries no cell. */
    private final int[] slotOfCell;

    private CacheGrid(int count, int[] cellOfSlot, int columns, int rows) {
        this.count = count;
        this.cellOfSlot = cellOfSlot;
        this.columns = Math.max(MIN_COLUMNS, columns);
        this.rows = Math.max(1, rows);
        this.slotOfCell = new int[this.columns * MAX_ROWS];
        Arrays.fill(this.slotOfCell, -1);
        for (int slot = 0; slot < count; slot++) this.slotOfCell[cellOfSlot[slot]] = slot;
    }

    /** The shipped arrangement for a level: the coordinates 0.2.2 used, the box 0.2.2 drew. */
    private static CacheGrid shipped(int count, int[] cellOfSlot) {
        return new CacheGrid(count, cellOfSlot, zeroTwoTwoColumns(count), zeroTwoTwoRows(count));
    }

    /** Box of an arrangement built from explicit coordinates (future plugin columns). */
    private static CacheGrid ofCoordinates(int count, int[] cellOfSlot) {
        int columns = MIN_COLUMNS;
        int rows = 1;
        for (int slot = 0; slot < count; slot++) {
            columns = Math.max(columns, columnOf(cellOfSlot[slot]) + 1);
            rows = Math.max(rows, rowOf(cellOfSlot[slot]) + 1);
        }
        return new CacheGrid(count, cellOfSlot, columns, rows);
    }

    private static int packed(int column, int row) { return column * MAX_ROWS + row; }
    private static int columnOf(int cell) { return cell / MAX_ROWS; }
    private static int rowOf(int cell) { return cell % MAX_ROWS; }

    /** How many columns 0.2.2's own algorithm gives a level of {@code count} cells. */
    private static int zeroTwoTwoColumns(int count) {
        return Math.max(MIN_COLUMNS, (Math.max(0, count) + MAX_ROWS - 1) / MAX_ROWS);
    }

    /** How many rows 0.2.2's own algorithm gives a level of {@code count} cells (it wrapped, we never do). */
    private static int zeroTwoTwoRows(int count) {
        int columns = zeroTwoTwoColumns(count);
        return Math.max(1, (Math.max(0, count) + columns - 1) / columns);
    }

    /** The level table's slot counts, in level order; the level table stays the single source. */
    private static int[] levelSlotCounts() {
        List<Integer> counts = new ArrayList<>();
        for (int level = 1; ; level++) {
            try {
                counts.add(CacheLevel.of(level).slots());
            } catch (IllegalArgumentException pastTheLevelTable) {
                break;
            }
        }
        int[] array = new int[counts.size()];
        for (int index = 0; index < array.length; index++) array[index] = counts.get(index);
        return array;
    }

    private static CacheGrid[] build() {
        int[] counts = levelSlotCounts();
        CacheGrid[] levels = new CacheGrid[counts.length];
        int[] cellOfSlot = new int[Math.max(1, counts[counts.length - 1])];
        boolean[] taken = new boolean[cellOfSlot.length];
        for (int index = 0; index < counts.length; index++) {
            int count = counts[index];
            int columns = zeroTwoTwoColumns(count);
            int slot = 0;
            if (index > 0) {   // every index that already existed keeps its coordinate, literally
                CacheGrid previous = levels[index - 1];
                System.arraycopy(previous.cellOfSlot, 0, cellOfSlot, 0, previous.count);
                slot = previous.count;
                for (int existing = 0; existing < previous.count; existing++) taken[cellOfSlot[existing]] = true;
            }
            // New indexes take the free coordinates of 0.2.2's own arrangement for this level, in that
            // arrangement's row-major order, so the occupied set stays exactly 0.2.2's.
            for (int row = 0; row < MAX_ROWS && slot < count; row++)
                for (int column = 0; column < columns && slot < count; column++) {
                    if (row * columns + column >= count) continue;
                    int cell = packed(column, row);
                    if (taken[cell]) continue;
                    cellOfSlot[slot++] = cell;
                    taken[cell] = true;
                }
            levels[index] = shipped(count, Arrays.copyOf(cellOfSlot, count));
        }
        return levels;
    }

    /** The arrangement for a cache holding {@code count} cells. */
    public static CacheGrid forCount(int count) {
        for (CacheGrid grid : CANONICAL) if (grid.count == count) return grid;
        return flowing(count);
    }

    /** The arrangement for a cache level; the level's own slot count is asserted to match. */
    public static CacheGrid forLevel(int level) {
        CacheLevel limits = CacheLevel.of(level);
        return forCount(limits.slots());
    }

    /**
     * Defensive fallback for a cell count that is not one of the shipped levels (no shipped state has one):
     * 0.2.2's flowing row-major arrangement, kept so an unexpected count still renders every cell.
     */
    private static CacheGrid flowing(int count) {
        count = Math.max(0, count);
        int columns = zeroTwoTwoColumns(count);
        int[] cellOfSlot = new int[count];
        for (int slot = 0; slot < count; slot++) cellOfSlot[slot] = packed(slot % columns, slot / columns);
        return shipped(count, cellOfSlot);
    }

    public int count() { return count; }
    public int rows() { return rows; }
    public int columns() { return columns; }

    /** Row of a cell index; every level that still contains the index answers the same row. */
    public int row(int slot) {
        require(slot);
        return rowOf(cellOfSlot[slot]);
    }

    /** Column of a cell index; every level that still contains the index answers the same column. */
    public int column(int slot) {
        require(slot);
        return columnOf(cellOfSlot[slot]);
    }

    /** Cell index drawn at (row, column), or -1 when that position carries no cell. */
    public int slot(int row, int column) {
        if (row < 0 || column < 0 || row >= rows || column >= columns) return -1;
        return slotOfCell[packed(column, row)];
    }

    /**
     * This arrangement plus {@code extra} slot coordinates for future plugin cells. Existing indexes are
     * copied verbatim (never moved); new coordinates complete the rightmost column's free rows first and then
     * append whole new columns to the right, top-down, each column holding at most {@link #MAX_ROWS} cells —
     * "未来插件格继续加新列". The panel box follows the coordinates, so it grows with the table.
     */
    public CacheGrid withExtraSlots(int extra) {
        if (extra <= 0) return this;
        int[] grown = Arrays.copyOf(cellOfSlot, count + extra);
        int next = count;
        for (int column = Math.max(0, columns - 1); next < grown.length; column++)
            for (int row = 0; row < MAX_ROWS && next < grown.length; row++) {
                int cell = packed(column, row);
                if (cell < slotOfCell.length && slotOfCell[cell] >= 0) continue;
                boolean alreadyAdded = false;
                for (int slot = count; slot < next; slot++) if (grown[slot] == cell) alreadyAdded = true;
                if (alreadyAdded) continue;
                grown[next++] = cell;
            }
        return ofCoordinates(grown.length, grown);
    }

    private void require(int slot) {
        if (slot < 0 || slot >= count) throw new IndexOutOfBoundsException("slot " + slot + " of " + count);
    }
}
