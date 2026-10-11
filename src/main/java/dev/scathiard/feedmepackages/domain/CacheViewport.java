package dev.scathiard.feedmepackages.domain;

/** Bounded visual window. Offsets describe screen rows, never contiguous storage identities. */
public final class CacheViewport {
    private CacheViewport() {}
    public static final int COLUMNS = 6;
    public static final int SIZE = 36;
    public static int lastFirst(int totalSlots) { return lastFirst(totalSlots, COLUMNS); }
    public static int lastFirst(int totalSlots, int columnLimit) {
        return Math.max(0, CacheGrid.rowsFor(totalSlots, columnLimit) - CacheGrid.MAX_ROWS)
                * CacheGrid.columnsFor(totalSlots, columnLimit);
    }
    public static int first(int totalSlots, int requested) { return first(totalSlots, requested, COLUMNS); }
    public static int first(int totalSlots, int requested, int columnLimit) {
        int columns = CacheGrid.columnsFor(totalSlots, columnLimit);
        return Math.clamp(Math.max(0, requested) / columns * columns, 0, lastFirst(totalSlots, columnLimit));
    }
}
