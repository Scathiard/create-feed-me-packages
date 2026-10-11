package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.CacheGrid;

import java.util.ArrayList;
import java.util.List;

/** Geometry in GUI pixels; shared by rendering, mouse routing and JEI. */
public record PanelLayout(Rect bounds, List<CellBox> cells, Rect slider, Rect returnBar,
        int firstRow, int visibleRows, int totalRows, int footerY, boolean compact, boolean hidden) {
    // panel.png: two 22-pixel end caps surround the 18-pixel inventory cells.
    public static final int ROW = 18, SIDE = 22, WIDTH = 2 * ROW + 2 * SIDE;
    public static final int HEADER = 18, BAR = 18, FOOTER = 24;
    public static final int MARGIN = 4, GAP = 4, BOOK_WIDTH = 177, MAX_ROWS = 6;
    /**
     * The buttons are <b>13x13</b> (user: "换个思路，把按钮都放到底部边框，然后做大一点…依旧贴边框"). The user drew them
     * himself ({@code 参考/Button_13X13 - 转移.png}, {@code - 收纳.png} and {@code - 展开.png}) with the icon baked
     * in, so all three are drawn 1:1 and the direction comes from which sheet is used.
     */
    public static final int BUTTON = 13;
    /**
     * How much of the {@link #SIDE}-wide end cap is actually painted. Measured in {@code panel.png}: the cap
     * blits read texture columns {@code u=53..66} (14 columns) for the repeat strip and the footer, i.e. the
     * <b>last 8 of the 22 cap columns are transparent</b> - that transparent margin is exactly what made the
     * old 10x10 square look like it stuck out past the frame. The last painted column ({@code u=66}) is the
     * right inner black line, and {@code u=58} is the left one.
     */
    public static final int CAP_ART = 14;
    /**
     * Edge of a button sheet in pixels - the same as {@link #BUTTON}, because every sheet is drawn 1:1. There is no
     * mirroring and no scaling anywhere: each of the three buttons picks one of the three ready-made sheets.
     */
    public static final int BUTTON_SHEET = BUTTON;

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }
    public record CellBox(int slot, Rect bounds) {
        public Rect dot() { return new Rect(bounds.x() + 13, bounds.y() + 1, 4, 5); }
    }
    public PanelLayout { cells = List.copyOf(cells); }
    public PanelLayout clampControls(int screenWidth, int screenHeight) {
        if (slider == null) return this;
        Rect clamped = new Rect(clamp(slider.x(), MARGIN, screenWidth-MARGIN-slider.width()),
                clamp(slider.y(), MARGIN, screenHeight-MARGIN-slider.height()), slider.width(), slider.height());
        return new PanelLayout(bounds,cells,clamped,returnBar,firstRow,visibleRows,totalRows,footerY,compact,hidden);
    }
    public Rect address() {
        return new Rect(bounds.x() + 11, bounds.y() + 1, bounds.width() - 22, 13);
    }
    public boolean returnAddressContains(double x, double y) {
        return returnBar != null && returnBar.contains(x, y)
                && (slider == null || !slider.contains(x, y));
    }
    public Rect scrollbar() {
        return new Rect(bounds.x() + bounds.width() - 13, bounds.y() + HEADER,
                2, visibleRows * ROW);
    }
    /** The 1 px black line on the inside-right of the right-hand cap (texture u=66) - the buttons sit 2 px left of it. */
    public int rightBorderStrokeX() { return bounds.x() + bounds.width() - SIDE + CAP_ART - 1; }
    /** The 1 px black line along the very bottom of the panel (texture v=139) - the buttons hug it. */
    public int bottomBorderStrokeY() { return bounds.y() + bounds.height() - 1; }
    /**
     * The row both buttons start on: two rows above {@link #bottomBorderStrokeY()} (user 09-20, pixel nudge:
     * "两个按钮一起上移 1px、左移 2px") - i.e. the 13 px band {@code v=125..137}, leaving {@code v=138} free.
     */
    private int buttonY() { return bottomBorderStrokeY() - BUTTON - 1; }

    /**
     * The one-key collect ("transfer") button: the <b>left</b> of the two 13x13 squares in the panel's bottom
     * border band, sitting one pixel left of the fold button and two rows above the bottom black line (user:
     * "把按钮都放到底部边框…依旧贴边框", "右下角并排两个"). It is answered before the scrollbar in {@code press}, so the
     * thin rail stays clickable above it; the mouse wheel is unaffected.
     *
     * <p>While the panel is hidden there is no transfer button at all: the only thing on screen is the entry, so
     * this returns an empty rect that no point can fall into.
     */
    public Rect transferButton() {
        if (hidden) return new Rect(bounds.x(), bounds.y(), 0, 0);
        return new Rect(foldButton().x() - BUTTON - 1, buttonY(), BUTTON, BUTTON);
    }

    /**
     * The fold-away button: the <b>right</b> of the two 13x13 squares, its right edge two pixels left of the
     * panel's right black line and its bottom edge two rows above the bottom black line (user 09-20: "上移 1px、
     * 左移 2px") - i.e. the bottom-right corner inside the border band.
     * Pressing it folds the panel into {@link #hidden()} / unfolds it again.
     *
     * <p>When the panel is hidden this is the <b>entry</b>: the one square left on screen, at the same place the
     * fold button had (see {@link #hidden}), so the panel never appears to jump.
     */
    public Rect foldButton() {
        if (hidden) return new Rect(bounds.x(), bounds.y(), BUTTON, BUTTON);
        return new Rect(rightBorderStrokeX() - BUTTON - 1, buttonY(), BUTTON, BUTTON);
    }

    /** Width of the panel for a level's own arrangement (used before a layout exists). */
    public static int preferredWidth(CacheGrid grid) { return grid.columns() * ROW + 2 * SIDE; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }

    /** Layout for a cell count, using that count's own arrangement. */
    public static PanelLayout compute(int screenHeight, int left, int top, int count,
            int firstRow, int expanded, boolean bookOpen) {
        return compute(screenHeight, left, top, CacheGrid.forCount(count), firstRow, expanded, bookOpen);
    }

    /**
     * Layout for a level's own coordinate table. Cells are drawn at the (row, column) the table assigns them, so
     * an index that already existed never moves when the level grows (see {@link CacheGrid}).
     *
     * <p><b>The one fallback, and when it can happen:</b> 0.2.2 wrapped the cells into fewer columns when the
     * panel did not fit the window, and that wrap is kept here for exactly the same situation — it triggers
     * only when {@code left < columns * ROW + GAP + MARGIN + 2 * SIDE (+ BOOK_WIDTH when the recipe book is
     * open)}, i.e. when the panel's own left edge would fall outside the screen. The panel is then drawn
     * {@code fittingColumns} wide and scrolls its extra rows, precisely as 0.2.2 did. At the vanilla minimum
     * window (854x480, GUI scale 1, no book) the inventory screen's left edge is 339 px and level 5 needs
     * 160 px, so the wrap is not reachable there; the test pins the boundary at every level.
     */
    public static PanelLayout compute(int screenHeight, int left, int top, CacheGrid grid,
            int firstRow, int expanded, boolean bookOpen) {
        int count = grid.count();
        int book = bookOpen ? BOOK_WIDTH : 0;
        int fittingColumns = (left - book - GAP - MARGIN - 2 * SIDE) / ROW;
        int fittingRows = (screenHeight - 2 * MARGIN - HEADER - BAR - FOOTER) / ROW;
        if (fittingColumns < 2 || fittingRows < 1) {
            int y = Math.max(MARGIN, Math.min(top, screenHeight - MARGIN - ROW));
            return new PanelLayout(new Rect(MARGIN, y, ROW, ROW), List.of(), null, null,
                    0, 0, Math.max(1, (count + 1) / 2), y, true, false);
        }
        boolean canonical = grid.columns() <= fittingColumns;
        int columns = canonical ? grid.columns() : fittingColumns;
        int width = columns * ROW + 2 * SIDE;
        int x = left - book - GAP - width;
        int total = canonical ? grid.rows() : Math.max(1, (count + columns - 1) / columns);
        int rows = Math.min(Math.min(total, MAX_ROWS), fittingRows);
        int height = HEADER + rows * ROW + BAR + FOOTER;
        int y = clamp(top, MARGIN, screenHeight - MARGIN - height);
        int first = clamp(firstRow, 0, total - rows);
        boolean hasSelection = expanded >= 0 && expanded < count;
        int selectedRow = hasSelection ? (canonical ? grid.row(expanded) : expanded / columns) : -1;
        int selectedColumn = hasSelection ? (canonical ? grid.column(expanded) : expanded % columns) : -1;
        if (hasSelection)
            first = clamp(first, Math.max(0, selectedRow - rows + 1), Math.min(selectedRow, total - rows));
        List<CellBox> cells = new ArrayList<>();
        Rect slider = null;
        int gridBottom = y + HEADER + rows * ROW;
        for (int row = first; row < first + rows; row++) {
            int cy = y + HEADER + (row - first) * ROW;
            for (int column = 0; column < columns; column++) {
                int slot = canonical ? grid.slot(row, column) : row * columns + column;
                if (slot < 0 || slot >= count) continue;
                cells.add(new CellBox(slot, new Rect(x + SIDE + column * ROW, cy, ROW, ROW)));
            }
            if (hasSelection && row == selectedRow) {
                // Keep the popup attached to its cell, including the last row/column.
                // The footer has room for its bottom-row overhang; input goes to the popup first.
                int popupY = cy + ROW + 8;
                if (popupY + 57 > screenHeight - MARGIN) popupY = cy - 57 - 8;
                slider = new Rect(Math.max(MARGIN, x + SIDE + selectedColumn * ROW - 63),
                        clamp(popupY, MARGIN, screenHeight - MARGIN - 57), 144, 57);
            }
        }
        // The label is already part of the bottom end caps (source rows 99..116).
        Rect returnBar = new Rect(x + 15, gridBottom + 1, width - 32, BAR);
        // Retain the record ABI; footerY now marks the end of the scrollable grid.
        return new PanelLayout(new Rect(x, y, width, height), cells, slider, returnBar,
                first, rows, total, gridBottom, false, false);
    }

    /**
     * The hidden panel (user picked "完全隐藏，只留一个小入口"): the whole panel is <b>not drawn at all</b> and only
     * one small entry square is left, at exactly the place the fold button had - computed from the same box the
     * panel would have for this level, so it is anchored to the inventory screen and never jumps somewhere else.
     *
     * <p><b>Click-through is structural:</b> {@code bounds} is the entry square itself, so the layout no longer
     * covers the area the panel used to occupy. {@code LogisticsPanel.press} returns {@code false} for any point
     * outside {@code bounds} while hidden, and {@code exclusions} only ever reports {@code bounds} - so a click
     * over the old panel area passes through to whatever is underneath (JEI's bookmark column included).
     *
     * <p>No cell, coordinate, ledger or screen position is touched: the grid comes back cell for cell.
     */
    public static PanelLayout hidden(int screenHeight, int left, int top, CacheGrid grid, boolean bookOpen) {
        Rect entry = compute(screenHeight, left, top, grid, 0, -1, bookOpen).foldButton();
        return new PanelLayout(entry, List.of(), null, null, 0, 0, 0, entry.y() + entry.height(), false, true);
    }
}
