package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.CacheGrid;

import java.util.ArrayList;
import java.util.List;

/** Geometry in GUI pixels; shared by rendering, mouse routing and JEI. */
public record PanelLayout(Rect bounds, List<CellBox> cells, Rect slider, Rect returnBar,
        int firstRow, int visibleRows, int totalRows, int footerY, boolean compact, boolean hidden) {
    // panel.png: two 22-pixel end caps surround the 18-pixel inventory cells.
    public static final int ROW = 18, SIDE = 22, WIDTH = 2 * ROW + 2 * SIDE;
    public static final int HEADER = 18, BAR = 18, FOOTER = 24, SLIDER = 19;
    // Geometry of the visible artwork, not only the surrounding popup rectangle.
    public static final int TRACK_INSET = 5, TRACK_Y = 4, MIN_THUMB_Y = 8, MAX_THUMB_Y = 0, LABEL_Y = 11;
    public static final int MARGIN = 4, GAP = 4, BOOK_WIDTH = 177, MAX_ROWS = 6;
    /**
     * The two buttons (collect / collapse) are <b>7x7</b> and hug the inside of the right-hand cap's two black
     * lines: the user drew a 7x7 chevron sheet ({@code 参考/Button_7x7.png}) and asked for it 1:1, with its left
     * edge against the inner black line at texture {@code x=58} and its right edge against the one at {@code x=66}.
     */
    public static final int BUTTON = 7;
    /**
     * How much of the {@link #SIDE}-wide end cap is actually painted. Measured in {@code panel.png}: the cap
     * blits read texture columns {@code u=53..66} (14 columns) for the repeat strip and the footer, i.e. the
     * <b>last 8 of the 22 cap columns are transparent</b> - that transparent margin is exactly what made the
     * old 10x10 square look like it stuck out past the frame. The last painted column ({@code u=66}) is the
     * right inner black line, and {@code u=58} is the left one.
     */
    public static final int CAP_ART = 14;
    /** Texture column of the cap's LEFT inner black line, relative to the first painted cap column (u=53+5=58). */
    public static final int CAP_LEFT_STROKE = 5;
    /**
     * Edge of the button sheet in pixels (the user's 7x7 chevron). Rendering and the direction proof share this
     * and the two mapping functions below, so "which way does the arrow point" is decided in ONE place.
     */
    public static final int BUTTON_SHEET = 7;
    /**
     * Screen x of the sheet's source column {@code u} when the sheet is drawn into {@code r}. {@code flipped}
     * mirrors the sheet <b>by moving whole pixel columns</b> - never by a negative pose scale, which renders
     * nothing down this GUI path. The user wants the collect button and the hidden entry to read as pointing left
     * ({@code flipped = true}) and the expanded fold button exactly as drawn ({@code flipped = false}).
     */
    public static int buttonPixelX(Rect r, int u, boolean flipped) {
        return flipped ? r.x() + (BUTTON_SHEET - 1 - u) : r.x() + u;
    }
    /** Screen y of the sheet's source row {@code v}; the sheet is never flipped vertically. */
    public static int buttonPixelY(Rect r, int v) { return r.y() + v; }

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }
    public record CellBox(int slot, Rect bounds) {
        public Rect dot() { return new Rect(bounds.x() + 13, bounds.y() + 1, 4, 5); }
    }
    public PanelLayout { cells = List.copyOf(cells); }
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
    /**
     * The column the two buttons share: the 7x7 square is <b>wedged between the cap's two black lines</b> - left
     * edge one pixel right of the left line, right edge one pixel left of the right line (user: "正好贴住边框的
     * 左右边线"). Derived from {@link #leftBorderStrokeX()} / {@link #rightBorderStrokeX()} - no pixel is written
     * down here. The entry when hidden.
     */
    private int buttonX() {
        if (hidden) return bounds.x();   // while hidden the box IS the entry square
        return leftBorderStrokeX() + 1;
    }

    /** The 1 px black line on the INSIDE-left of the right-hand cap (texture u=58). */
    public int leftBorderStrokeX() { return bounds.x() + bounds.width() - SIDE + CAP_LEFT_STROKE; }
    /** The 1 px black line on the INSIDE-right of the right-hand cap (texture u=66). */
    public int rightBorderStrokeX() { return bounds.x() + bounds.width() - SIDE + CAP_ART - 1; }

    /**
     * The one-key collect button (user: "放在面板右边的中间"): a small square inside the right-hand end-cap
     * band, vertically centred. Derived from the panel box plus the existing unit constants - no pixel is
     * written down here. It is tested before the scrollbar in {@code press}, so the thin rail stays clickable
     * above and below the button; the mouse wheel is unaffected.
     *
     * <p>While the panel is hidden there is no collect button at all: the only thing on screen is the entry,
     * so this returns an empty rect that no point can fall into.
     */
    public Rect collectButton() {
        if (hidden) return new Rect(bounds.x(), bounds.y(), 0, 0);
        int y = bounds.y() + (bounds.height() - BUTTON) / 2;
        return new Rect(buttonX(), y, BUTTON, BUTTON);
    }

    /**
     * The fold-away button (user: "位置放在转移按钮同列，放右下角"): same column as the collect button, at the
     * <b>bottom-right corner</b> of the panel - inside the footer band when expanded, at the bottom of the strip
     * when already folded. Pressing it folds the panel into {@link #hidden()} / unfolds it again.
     *
     * <p>When the panel is hidden this is the <b>entry</b>: the one small square left on screen, at the same
     * place the fold button had (see {@link #hidden}), so the panel never appears to jump.
     */
    public Rect collapseButton() {
        int y = hidden ? bounds.y()
                : bounds.y() + bounds.height() - FOOTER + (FOOTER - BUTTON) / 2;
        return new Rect(buttonX(), y, BUTTON, BUTTON);
    }
    /**
     * The icon itself is one 16x16 texture drawn at half scale (see {@code LogisticsPanel.drawArrow}); the
     * geometry here only decides <b>where</b> the 8x8 square goes, so drawing and hit testing can never disagree:
     * both use {@link #collectButton()} / {@link #collapseButton()}.
     */

    /** Width of the panel for a level's own arrangement (used before a layout exists). */
    public static int preferredWidth(CacheGrid grid) { return grid.columns() * ROW + 2 * SIDE; }
    /** Offset of the LAST painted track pixel from the track's first pixel. The track is
     *  {@code width - 2*TRACK_INSET} pixels wide, so this is that width minus one. */
    public static int sliderTrackSpan(int width) { return Math.max(1, width - 2 * TRACK_INSET - 1); }

    /** Endpoint pixel for a group value. Zero sits on the track's first pixel, the maximum is clamped
     *  to the LAST painted pixel (never one past it), and -1 (no return) renders at the maximum
     *  position. Rendering and hit-testing share this function. */
    public static int sliderThumbPx(int sliderX, int width, int value, int groupCap) {
        int cap = Math.max(1, groupCap);
        int trackW = Math.max(1, width - 2 * TRACK_INSET);
        int first = sliderX + TRACK_INSET;
        int last = first + trackW - 1;
        int clamped = Math.clamp((long)(value < 0 ? cap : value), 0, cap);
        int raw = first + clamped * trackW / cap;
        return Math.max(first, Math.min(last, raw));
    }

    /** Group value under the mouse: rounds to the NEAREST step so the endpoint follows the cursor.
     *  The last painted pixel always reads as the maximum, so dragging fully right cannot come back
     *  one group short. Flooring here made the endpoint lag behind the cursor and then jump a whole
     *  step (worst at low levels), which is the "not following the mouse" regression from test.57. */
    public static int sliderValue(int sliderX, int width, double x, int groupCap) {
        int cap = Math.max(1, groupCap);
        int trackW = Math.max(1, width - 2 * TRACK_INSET);
        double relative = x - (double)sliderX - TRACK_INSET;
        if (relative >= trackW - 1) return cap;
        return Math.clamp((long)Math.round(relative * (double)cap / trackW), 0, cap);
    }
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
                slider = new Rect(x + SIDE + selectedColumn * ROW - ROW / 2, cy + ROW + 2, 2 * ROW, SLIDER);
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
        Rect entry = compute(screenHeight, left, top, grid, 0, -1, bookOpen).collapseButton();
        return new PanelLayout(entry, List.of(), null, null, 0, 0, 0, entry.y() + entry.height(), false, true);
    }
}
