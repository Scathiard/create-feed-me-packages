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
 *
 * <p><b>The drop that lands on a cell.</b> The panel's body is a gapless 18x18 grid, so a player dropping a
 * stack "on the panel" almost always lands on <i>some</i> cell — and that cell's own filter used to decide
 * (silently refused with {@code FILTER_OCCUPIED} or {@code DUPLICATE_FILTER} when it was the wrong one).
 * {@link #resolveDrop} keeps the aimed cell as the first choice and only then falls back to the aim-free
 * resolution above: one rule, one place.
 */
public final class CacheDropTarget {
    /** One cell as the resolver sees it: the encoded filter it holds, and whether that cell is already full. */
    public record Cell(String template, boolean full) {
        public boolean empty() { return template == null || template.isEmpty(); }
    }

    /**
     * The four aim-free outcomes keep their order; the two aimed ones are <b>appended</b> so no existing
     * ordinal can move.
     */
    public enum Outcome {
        /** The cell that already filters this exact item; the deposit tops it up. */
        EXISTING_CELL,
        /** Same cell, but it reports no room left: the deposit still goes there and the server answers. */
        EXISTING_CELL_FULL,
        /** No cell filters the item yet: the first empty cell takes it (the deposit sets that filter). */
        FIRST_EMPTY_CELL,
        /** Every cell is taken by another item: nothing to aim at, so the caller must say so out loud. */
        NO_TARGET,
        /**
         * The drop landed on the cell that already filters this exact item — where the player aimed — so the
         * deposit goes there and the aimed feel is kept. Whether that cell still has room is the server's own
         * call ({@code NO_SPACE}), exactly as it was before this rule existed.
         */
        AIMED_MATCH,
        /** The drop landed on an empty cell and no other cell claims the item: that cell takes it and is filtered. */
        AIMED_EMPTY
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

    /**
     * The drop that landed on a cell. That cell is the first choice; only when it cannot take the item at all
     * does the aim-free {@link #resolve} decide. This is the <b>only</b> place the rule lives — both click
     * paths call it, so they can never drift apart.
     *
     * <p>Rule: <b>(a)</b> the aimed cell already filters this exact item — deposit there, the "I meant this
     * cell" feel is unchanged; <b>(b)</b> the aimed cell is empty and no other cell claims the item — deposit
     * there and that cell takes the filter; <b>(c)</b> otherwise (the aimed cell filters something else, or it
     * is empty while another cell already claims the item, which is the {@code DUPLICATE_FILTER} case) the
     * aim-free resolution picks the cell, or reports {@link Outcome#NO_TARGET} so the caller speaks up.
     *
     * <p>Nothing new is decided here that the server does not already validate: the client just aims the one
     * ordinary {@code DEPOSIT} at the cell the item names, and the server's {@code DUPLICATE_FILTER} /
     * {@code FILTER_OCCUPIED} / {@code NO_SPACE} nets stay exactly where they were.
     *
     * @param cells   the cache's cells in index order
     * @param aimed   the cell the drop landed on, or -1 when it landed on no cell (the panel's blank space)
     * @param carried the encoded exact item on the cursor (empty/null means nothing is carried)
     */
    public static Target resolveDrop(List<Cell> cells, int aimed, String carried) {
        if (carried == null || carried.isEmpty()) return new Target(-1, Outcome.NO_TARGET);
        if (aimed >= 0 && aimed < cells.size()) {
            Cell cell = cells.get(aimed);
            if (carried.equals(cell.template())) return new Target(aimed, Outcome.AIMED_MATCH);
            if (cell.empty() && !anyCellFilters(cells, carried)) return new Target(aimed, Outcome.AIMED_EMPTY);
        }
        return resolve(cells, carried);
    }

    /** True when some cell already filters this exact item (the served rule, asked once, in one place). */
    private static boolean anyCellFilters(List<Cell> cells, String carried) {
        for (Cell cell : cells) if (carried.equals(cell.template())) return true;
        return false;
    }
}
