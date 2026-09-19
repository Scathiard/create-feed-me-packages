package dev.scathiard.feedmepackages.domain;

import java.util.Arrays;

/**
 * The visual arrangement of a cache level's cells: which (row, column) each cell index occupies.
 *
 * <p><b>Why this exists.</b> The panel used to lay cells out with {@code slot = row * columns + column}
 * where {@code columns} was derived from the cell count, so every level change moved most cells to a
 * different (row, column) — the user reported the contents "jumping" on upgrade: "升级的时候，原本缓存里
 * 放号的物品位置会错位". This type pins the arrangement to the level instead of to a formula: a level's
 * grid grows by rows and columns, and <b>every index that already existed keeps the very same
 * (row, column)</b>; only brand new positions are appended.
 *
 * <p><b>Nothing about the data changes.</b> The ledger keeps a positional list whose length must equal
 * {@link CacheLevel#slots()} and {@code CacheEdit.upgrade()} appends empty cells at the end, so a cell
 * index still means exactly "the Nth cell", before and after an upgrade: no migration, no re-ordering,
 * no schema change.
 *
 * <p>Canonical shapes (all rectangles exactly filled):
 * <pre>
 *   level 1: 3x3 =  9      level 2: 4x4 = 16
 *   level 3: 4x6 = 24      level 4: 5x6 = 30      level 5: 6x6 = 36
 * </pre>
 * Each step keeps the previous rectangle anchored at its top-left corner and fills the remaining cells of
 * the larger rectangle in row-major order, so the new cells always sit along the new bottom row and the
 * new right-hand column.
 */
public final class CacheGrid {
    private static final int[][] SHAPES = {{3, 3}, {4, 4}, {4, 6}, {5, 6}, {6, 6}};
    private static final CacheGrid[] CANONICAL = build();

    private final int count;
    private final int rows;
    private final int columns;
    private final int[] cellOfSlot;
    private final int[] slotOfCell;

    private CacheGrid(int count, int rows, int columns, int[] cellOfSlot) {
        this.count = count;
        this.rows = rows;
        this.columns = columns;
        this.cellOfSlot = cellOfSlot;
        this.slotOfCell = new int[rows * columns];
        Arrays.fill(this.slotOfCell, -1);
        for (int slot = 0; slot < cellOfSlot.length; slot++) this.slotOfCell[cellOfSlot[slot]] = slot;
    }

    private static CacheGrid[] build() {
        CacheGrid[] shapes = new CacheGrid[SHAPES.length];
        for (int index = 0; index < SHAPES.length; index++) {
            int rows = SHAPES[index][0];
            int columns = SHAPES[index][1];
            int count = rows * columns;
            int[] cellOfSlot = new int[count];
            boolean[] used = new boolean[count];
            int slot = 0;
            if (index > 0) {   // keep every cell of the previous (smaller) rectangle exactly where it was
                CacheGrid previous = shapes[index - 1];
                for (; slot < previous.count; slot++) {
                    // Re-pack through the previous shape's own geometry: the column count may have grown,
                    // and the packed value must be expressed in THIS rectangle's columns.
                    cellOfSlot[slot] = previous.row(slot) * columns + previous.column(slot);
                    used[cellOfSlot[slot]] = true;
                }
            }
            for (int cell = 0; cell < count && slot < count; cell++)
                if (!used[cell]) cellOfSlot[slot++] = cell;
            shapes[index] = new CacheGrid(count, rows, columns, cellOfSlot);
        }
        return shapes;
    }

    /** The arrangement for a cache whose ledger holds {@code count} cells. */
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
     * Defensive fallback for a cell count that is not one of the canonical shapes (no shipped state has
     * one): the old flowing row-major arrangement, kept so an unexpected count still renders.
     */
    private static CacheGrid flowing(int count) {
        count = Math.max(0, count);
        int columns = Math.max(2, (count + 5) / 6);
        int rows = Math.max(1, (count + columns - 1) / columns);
        int[] cellOfSlot = new int[count];
        for (int slot = 0; slot < count; slot++) cellOfSlot[slot] = slot;
        return new CacheGrid(count, rows, columns, cellOfSlot);
    }

    public int count() { return count; }
    public int rows() { return rows; }
    public int columns() { return columns; }

    /** Row of a cell index; every level that still contains the index answers the same row. */
    public int row(int slot) {
        require(slot);
        return cellOfSlot[slot] / columns;
    }

    /** Column of a cell index; every level that still contains the index answers the same column. */
    public int column(int slot) {
        require(slot);
        return cellOfSlot[slot] % columns;
    }

    /** Cell index drawn at (row, column), or -1 when that position carries no cell. */
    public int slot(int row, int column) {
        if (row < 0 || column < 0 || row >= rows || column >= columns) return -1;
        return slotOfCell[row * columns + column];
    }

    private void require(int slot) {
        if (slot < 0 || slot >= count) throw new IndexOutOfBoundsException("slot " + slot + " of " + count);
    }
}
