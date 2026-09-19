package dev.scathiard.feedmepackages.domain;

import java.util.List;

/**
 * Where a stack ends up when it is dropped anywhere on the panel instead of aimed at a cell.
 *
 * <p>A cache keeps at most one cell per exact item (the server's {@code DUPLICATE_FILTER} rule), so the
 * item itself decides the target: the cell that already filters it, else the first empty cell. That makes
 * "drop it in the panel" exact without any aiming, and it needs no new action, no new packet and no change
 * to what is stored — the panel still sends its ordinary {@code DEPOSIT} to the resolved slot, and the
 * server keeps validating every deposit.
 */
public final class CacheDropTarget {
    /** One cell as the resolver sees it: the encoded filter it holds, and whether that cell is already full. */
    public record Cell(String template, boolean full) {
        public boolean empty() { return template == null || template.isEmpty(); }
    }

    public enum Outcome {
        /** The cell that already filters this exact item; the deposit tops it up. */
        EXISTING_CELL,
        /** Same cell, but it reports no room left: the deposit still goes there and the server answers. */
        EXISTING_CELL_FULL,
        /** No cell filters the item yet: the first empty cell takes it (the deposit sets that filter). */
        FIRST_EMPTY_CELL,
        /** Every cell is taken by another item: nothing to aim at, so the caller must say so out loud. */
        NO_TARGET
    }

    public record Target(int slot, Outcome outcome) {
        public boolean hasTarget() { return slot >= 0; }
    }

    private CacheDropTarget() {}

    /**
     * @param cells   the cache's cells in index order
     * @param carried the encoded exact item on the cursor (empty/null means nothing is carried)
     */
    public static Target resolve(List<Cell> cells, String carried) {
        if (carried == null || carried.isEmpty()) return new Target(-1, Outcome.NO_TARGET);
        for (int slot = 0; slot < cells.size(); slot++) {
            Cell cell = cells.get(slot);
            if (carried.equals(cell.template()))
                return new Target(slot, cell.full() ? Outcome.EXISTING_CELL_FULL : Outcome.EXISTING_CELL);
        }
        for (int slot = 0; slot < cells.size(); slot++)
            if (cells.get(slot).empty()) return new Target(slot, Outcome.FIRST_EMPTY_CELL);
        return new Target(-1, Outcome.NO_TARGET);
    }
}
