package dev.scathiard.feedmepackages.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared mapping for drawing, hit testing and actions. The default five levels retain the original
 * cell coordinates when growing. A configured viewport may scroll or narrow the visible columns;
 * its local positions always map back to immutable ledger indexes through {@link #storageSlot(int)}.
 * Neither layout nor scrolling reorders the saved inventory. {@link #window(int,int,int)} is the
 * bounded display entry point; the canonical helpers retain the established default geometry.
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
    private int[] storageSlots;

    private CacheGrid(int count, int[] cellOfSlot, int columns, int rows) {
        this.count = count;
        this.storageSlots = java.util.stream.IntStream.range(0,count).toArray();
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

    /** Box of an arrangement built from explicit coordinates. */
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

    /** Canonical fallback for a non-default count; live configurable panels use window(). */
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

    /** Local row in this arrangement; convert local indexes with storageSlot for ledger actions. */
    public int row(int slot) {
        require(slot);
        return rowOf(cellOfSlot[slot]);
    }

    /** Local column in this arrangement; it is not a ledger index. */
    public int column(int slot) {
        require(slot);
        return columnOf(cellOfSlot[slot]);
    }

    /** Cell index drawn at (row, column), or -1 when that position carries no cell. */
    public int slot(int row, int column) {
        if (row < 0 || column < 0 || row >= rows || column >= columns) return -1;
        return slotOfCell[packed(column, row)];
    }

    /** Append canonical columns without moving existing coordinates; separate from viewport paging. */
    public CacheGrid withExtraSlots(int extra) {
        if (extra <= 0) return this;
        int[] grown = Arrays.copyOf(cellOfSlot, count + extra);
        int next = count;
        for (int column = columns; next < grown.length; column++)
            for (int row = 0; row < MAX_ROWS && next < grown.length; row++)
                grown[next++] = packed(column, row);
        return ofCoordinates(grown.length, grown);
    }

    /** Page mapping reused from the reference scroll repair; storage IDs never depend on the visible row. */
    private static int naturalColumns(int total) {
        if(total>36)return CacheViewport.COLUMNS;
        int columns=2;var base=forLevel(5);
        for(int slot=0;slot<Math.max(0,total);slot++)columns=Math.max(columns,base.column(slot)+1);
        return columns;
    }
    public static int columnsFor(int total,int limit){return Math.min(naturalColumns(total),Math.clamp(limit,2,6));}
    public static int rowsFor(int total,int limit){
        int columns=columnsFor(total,limit);
        if(total>36 || columns<naturalColumns(total))return Math.max(1,Math.ceilDiv(Math.max(0,total),columns));
        int rows=1;var base=forLevel(5);for(int slot=0;slot<Math.max(0,total);slot++)rows=Math.max(rows,base.row(slot)+1);return rows;
    }
    public static CacheGrid window(int total,int requestedFirst,int limit){
        int columns=columnsFor(total,limit), first=CacheViewport.first(total,requestedFirst,limit)/columns;
        int rows=Math.min(MAX_ROWS,rowsFor(total,limit)-first);var slots=new ArrayList<Integer>();var coordinates=new ArrayList<Integer>();
        var base=forLevel(5);
        for(int slot=0;slot<total;slot++){
            int row,column;
            if(columns<naturalColumns(total)){row=slot/columns;column=slot%columns;}
            else if(slot<36){row=base.row(slot);column=base.column(slot);}
            else{row=MAX_ROWS+(slot-36)/6;column=(slot-36)%6;}
            if(row<first || row>=first+rows)continue;
            slots.add(slot);coordinates.add(packed(column,row-first));
        }
        var grid=new CacheGrid(slots.size(),coordinates.stream().mapToInt(Integer::intValue).toArray(),columns,Math.max(1,rows));
        grid.storageSlots=slots.stream().mapToInt(Integer::intValue).toArray();return grid;
    }
    public int storageSlot(int local){require(local);return storageSlots[local];}
    public int localSlot(int storage){for(int i=0;i<storageSlots.length;i++)if(storageSlots[i]==storage)return i;return -1;}
    public List<Integer> storageSlots(){return java.util.Arrays.stream(storageSlots).boxed().toList();}

    private void require(int slot) {
        if (slot < 0 || slot >= count) throw new IndexOutOfBoundsException("slot " + slot + " of " + count);
    }
}
