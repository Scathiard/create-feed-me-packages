package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.CacheGrid;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {
    /** The panel shifts left as the grid widens, so "the same place" means the same offset inside the grid. */
    @Test void anUpgradeKeepsEveryExistingCellWhereThePlayerSawIt() {
        for (int level = 1; level < 5; level++) {
            CacheGrid before = CacheGrid.forLevel(level);
            CacheGrid after = CacheGrid.forLevel(level + 1);
            var small = PanelLayout.compute(480, 400, 20, before, 0, -1, false);
            var bigger = PanelLayout.compute(480, 400, 20, after, 0, -1, false);
            assertFalse(small.compact()); assertFalse(bigger.compact());
            assertEquals(before.count(), small.cells().size(), "level " + level + " must draw every cell");
            assertEquals(after.count(), bigger.cells().size(), "level " + (level + 1) + " must draw every cell");
            int smallX = small.cells().stream().mapToInt(cell -> cell.bounds().x()).min().orElseThrow();
            int smallY = small.cells().stream().mapToInt(cell -> cell.bounds().y()).min().orElseThrow();
            int bigX = bigger.cells().stream().mapToInt(cell -> cell.bounds().x()).min().orElseThrow();
            int bigY = bigger.cells().stream().mapToInt(cell -> cell.bounds().y()).min().orElseThrow();
            for (int slot = 0; slot < before.count(); slot++) {
                var was = cell(small, slot).bounds();
                var now = cell(bigger, slot).bounds();
                assertEquals(was.x() - smallX, now.x() - bigX, "column moved on " + level + " -> " + (level + 1) + " slot " + slot);
                assertEquals(was.y() - smallY, now.y() - bigY, "row moved on " + level + " -> " + (level + 1) + " slot " + slot);
            }
        }
    }
    private static PanelLayout.CellBox cell(PanelLayout layout, int slot) {
        return layout.cells().stream().filter(candidate -> candidate.slot() == slot).findFirst().orElseThrow();
    }
    @Test void everyUnlockedCellCanBeReachedWithoutShrinkingOrOverlap() {
        for (int count : new int[]{9, 16, 24, 30, 36}) for (int height : new int[]{208, 240, 360, 480, 1080}) {
            Set<Integer> reached = new HashSet<>();
            for (int row = 0; row < (count + 1) / 2; row++) {
                var layout = PanelLayout.compute(height, 88, 70, count, row, -1, false);
                assertFalse(layout.compact());
                assertTrue(layout.bounds().y() >= 4 && layout.bounds().y() + layout.bounds().height() <= height - 4);
                for (var box : layout.cells()) {
                    assertTrue(box.slot() < count); assertEquals(18, box.bounds().width()); assertEquals(18, box.bounds().height());
                    assertTrue(layout.bounds().contains(box.bounds().x(), box.bounds().y()));
                    assertTrue(box.bounds().contains(box.dot().x(), box.dot().y()));
                    assertTrue(box.bounds().contains(box.dot().x() + box.dot().width() - 1, box.dot().y() + box.dot().height() - 1));
                    assertTrue(box.bounds().y() + box.bounds().height() <= layout.footerY()); reached.add(box.slot());
                    for (var other : layout.cells()) if (box.slot() != other.slot()) assertFalse(intersects(box.bounds(), other.bounds()));
                }
            }
            assertEquals(count, reached.size());
        }
    }
    @Test void expandedSliderAndRecipeBookUseSeparateSpace() {
        for (int selected = 0; selected < 36; selected++) {
            var layout = PanelLayout.compute(240, 265, 60, 36, 0, selected, true);
            assertFalse(layout.compact()); assertNotNull(layout.slider());
            int selectedSlot = selected;
            assertTrue(layout.cells().stream().anyMatch(cell -> cell.slot() == selectedSlot));
            assertTrue(layout.bounds().x() + layout.bounds().width() < 265 - 177);
            // The slider is an overlay: it may visually cover cells below it (z-lift),
            // so we only verify it stays within screen bounds.
            assertTrue(layout.slider().y() + layout.slider().height() <= 240 - 4);
        }
        var narrow = PanelLayout.compute(240, 72, 60, 36, 0, -1, true);
        assertTrue(narrow.compact()); assertEquals(18, narrow.bounds().width());
        assertTrue(narrow.cells().isEmpty());
    }
    @Test void sliderStaysCenteredBelowItsCellAcrossRowsColumnsAndScrolling() {
        for (int count : new int[]{9, 16, 24, 30, 36})
            for (int height : new int[]{104, 208, 240, 480})
                for (int left : new int[]{88, 200, 400})
                    for (boolean book : new boolean[]{false, true})
                        for (int selected = 0; selected < count; selected++) {
                            var layout = PanelLayout.compute(height, left, 70, count, 2, selected, book);
                            if (layout.compact()) {
                                assertNull(layout.slider());
                                continue;
                            }
                            int selectedSlot = selected;
                            var cell = layout.cells().stream().filter(c -> c.slot() == selectedSlot)
                                    .findFirst().orElseThrow().bounds();
                            var slider = layout.slider();
                            assertNotNull(slider);
                            assertEquals(cell.y() + cell.height() + 2, slider.y());
                            assertEquals(cell.x() * 2 + cell.width(), slider.x() * 2 + slider.width());
                            assertTrue(layout.bounds().contains(slider.x(), slider.y()));
                            assertTrue(layout.bounds().contains(slider.x() + slider.width() - 1,
                                    slider.y() + slider.height() - 1));
                            assertTrue(slider.y() + slider.height() <= height - PanelLayout.MARGIN);
                        }
    }
    @Test void bottomRowPopupTakesInputBeforeTheReturnAddress() {
        var open = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var slider = open.slider();
        var address = open.returnBar();
        int x = Math.max(slider.x(), address.x());
        int y = Math.max(slider.y(), address.y());
        assertTrue(slider.contains(x, y));
        assertTrue(address.contains(x, y));
        assertFalse(open.returnAddressContains(x, y));
        assertTrue(open.returnAddressContains(address.x(), address.y()));
        var closed = PanelLayout.compute(240, 200, 40, 9, 0, -1, false);
        assertTrue(closed.returnAddressContains(x, y));
    }
    @Test void visibleSliderArtworkIsCenteredAndStartsJustBelowTheCell() {
        var layout = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var cell = layout.cells().stream().filter(c -> c.slot() == 8).findFirst().orElseThrow().bounds();
        var slider = layout.slider();
        int trackWidth = slider.width() - 2 * PanelLayout.TRACK_INSET;
        assertEquals(cell.x() * 2 + cell.width(),
                (slider.x() + PanelLayout.TRACK_INSET) * 2 + trackWidth);
        assertEquals(cell.y() + cell.height() + 2, slider.y() + PanelLayout.MAX_THUMB_Y);
        assertEquals(cell.y() + cell.height() + 6, slider.y() + PanelLayout.TRACK_Y);
        assertTrue(PanelLayout.MIN_THUMB_Y + 5 <= PanelLayout.SLIDER);
        assertTrue(PanelLayout.LABEL_Y + 8 <= PanelLayout.SLIDER);
    }
    @Test void sliderEndpointValuesMapToThePaintedTrackPixels() {
        var layout = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var slider = layout.slider();
        int tx = slider.x() + PanelLayout.TRACK_INSET;
        int last = tx + slider.width() - 2 * PanelLayout.TRACK_INSET - 1;
        int cap = 2;
        assertEquals(tx, PanelLayout.sliderThumbPx(slider.x(), slider.width(), 0, cap));
        assertEquals(last, PanelLayout.sliderThumbPx(slider.x(), slider.width(), cap, cap));
        assertEquals(cap, PanelLayout.sliderValue(slider.x(), slider.width(), last, cap));
        assertEquals(cap, PanelLayout.sliderValue(slider.x(), slider.width(), last + 1, cap));
    }
    @Test void slotSourceRetainsTheCompleteOpaqueCellEvenWithoutPanelReferenceArea() throws Exception {
        try (var source = getClass().getResourceAsStream("/assets/create_feed_me_packages/textures/gui/slot_source.png")) {
            assertNotNull(source);
            var image = javax.imageio.ImageIO.read(source);
            assertNotNull(image);
            // The sheet was cropped to the block that is actually drawn, so the drawn block now IS the whole sheet.
            assertEquals(18, image.getWidth());
            assertEquals(18, image.getHeight());
            Set<Integer> colors = new HashSet<>();
            for (int y = 0; y < 18; y++) for (int x = 0; x < 18; x++) {
                int rgba = image.getRGB(x, y);
                assertEquals(255, (rgba >>> 24), "Slot became transparent at " + x + "," + y);
                colors.add(rgba);
            }
            assertTrue(colors.size() > 1, "Slot border and interior were flattened");
        }
    }
    private static boolean intersects(PanelLayout.Rect a, PanelLayout.Rect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    @Test void sliderMidpointPixelMapsToMidpointValue() {
        int sliderX = 100;
        int width = 36;
        int groupCap = 32;
        int midpoint = PanelLayout.sliderThumbPx(sliderX, width, groupCap / 2, groupCap);

        assertEquals(16, PanelLayout.sliderValue(sliderX, width, midpoint, groupCap));
    }

    /** Ten future plugin coordinates (order: "为未来的的拓展性做考虑") must move nothing and stay coherent. */
    @Test void tenFuturePluginCoordinatesKeepThePanelCoherent() {
        CacheGrid base = CacheGrid.forLevel(5);
        CacheGrid grown = base.withExtraSlots(10);
        assertEquals(46, grown.count(), "36 shipped cells + 10 future plugin cells");
        var layout = PanelLayout.compute(1080, 400, 40, grown, 0, -1, false);
        assertFalse(layout.compact());
        assertEquals(46, layout.cells().size(), "every future cell must be drawn");
        assertEquals(grown.columns() * PanelLayout.ROW + 2 * PanelLayout.SIDE, layout.bounds().width(),
                "the panel box follows the coordinate table");
        for (var box : layout.cells()) {
            assertTrue(layout.bounds().contains(box.bounds().x(), box.bounds().y()));
            assertTrue(box.bounds().x() + box.bounds().width() <= layout.bounds().x() + layout.bounds().width());
            assertTrue(box.bounds().y() + box.bounds().height() <= layout.footerY(), "cell left the scroll area");
            for (var other : layout.cells())
                if (box.slot() != other.slot()) assertFalse(intersects(box.bounds(), other.bounds()));
        }
        var scrollbar = layout.scrollbar();
        assertTrue(layout.bounds().contains(scrollbar.x(), scrollbar.y()));
        assertTrue(scrollbar.y() + scrollbar.height() <= layout.bounds().y() + layout.bounds().height());
        var shipped = PanelLayout.compute(1080, 400, 40, base, 0, -1, false);
        for (int slot = 0; slot < base.count(); slot++) {
            var before = cell(shipped, slot).bounds();
            var after = cell(layout, slot).bounds();
            assertEquals(before.x() - shipped.bounds().x(), after.x() - layout.bounds().x(), "column moved for slot " + slot);
            assertEquals(before.y() - shipped.bounds().y(), after.y() - layout.bounds().y(), "row moved for slot " + slot);
        }
    }

    /**
     * The fixed table is used whenever the panel actually fits, and the position of every cell is exactly the
     * one the table gives. The 0.2.2 wrap survives only one pixel further left, which is where the panel's own
     * left edge would leave the screen — that is the documented trigger condition, not a normal size.
     */
    @Test void theFixedTableDecidesEveryCellWheneverThePanelFits() {
        for (int count : new int[]{9, 16, 24, 30, 36}) {
            CacheGrid grid = CacheGrid.forCount(count);
            int side = 2 * PanelLayout.SIDE;
            // left = width + GAP + MARGIN is the exact pixel where the panel's left edge reaches the screen edge.
            int left = grid.columns() * PanelLayout.ROW + side + PanelLayout.GAP + PanelLayout.MARGIN;
            for (int height : new int[]{208, 240, 480, 1080}) {
                var layout = PanelLayout.compute(height, left, 40, grid, 0, -1, false);
                assertFalse(layout.compact(), "count " + count + " must not collapse at the fitting width");
                assertEquals(grid.columns() * PanelLayout.ROW + side, layout.bounds().width(), "the panel follows the table");
                assertEquals(grid.rows(), layout.totalRows(), "the table's own row count");
                assertEquals(count, layout.cells().size(), "every cell must be drawn at the fitting width");
                for (var box : layout.cells()) {
                    assertEquals(layout.bounds().x() + PanelLayout.SIDE + grid.column(box.slot()) * PanelLayout.ROW,
                            box.bounds().x(), "slot " + box.slot() + " ignores the table's column");
                    assertEquals(layout.bounds().y() + PanelLayout.HEADER + grid.row(box.slot()) * PanelLayout.ROW,
                            box.bounds().y(), "slot " + box.slot() + " ignores the table's row");
                }
                var narrower = PanelLayout.compute(height, left - 1, 40, grid, 0, -1, false);
                assertTrue(narrower.bounds().width() < layout.bounds().width(),
                        "one pixel narrower is where the 0.2.2 wrap takes over at count " + count);
                if (narrower.totalRows() > narrower.visibleRows()) assertNotNull(narrower.scrollbar());
            }
        }
    }

    /**
     * Both buttons live in the panel's <b>bottom border band</b> (user: "把按钮都放到底部边框…依旧贴边框"): 13x13, flush
     * against the bottom black line, the fold button's right edge flush against the right black line, exactly one
     * clear pixel between them, inside the pixels the shipped panel art paints, and clear of every cell, the
     * address bar and the return bar - at every level.
     */
    @Test void theTwoButtonsSitInTheBottomBorderBandClearOfEverything() {
        assertEquals(13, PanelLayout.BUTTON, "the user asked for the bigger bottom-band buttons");
        assertEquals(13, PanelLayout.BUTTON_SHEET, "the sheets are drawn 1:1");
        assertEquals(14, PanelLayout.CAP_ART, "panel.png paints only 14 of the 22 cap columns");
        for (int count : new int[]{9, 16, 24, 30, 36}) {
            var grid = CacheGrid.forCount(count);
            var layout = PanelLayout.compute(480, 300, 40, grid, 0, -1, false);
            assertFalse(layout.hidden());
            var fold = layout.foldButton();
            var transfer = layout.transferButton();
            for (var button : List.of(fold, transfer)) {
                assertEquals(PanelLayout.BUTTON, button.width(), "the buttons are 13x13 squares");
                assertEquals(PanelLayout.BUTTON, button.height());
                assertTrue(layout.bounds().contains(button.x(), button.y()));
                // Inside what the panel art covers: the band between the caps is painted from x+8 to x+w-8, and
                // the 49-row footer art reaches the panel's last row.
                assertTrue(button.x() >= layout.bounds().x() + 8, "a button reaches into the transparent cap margin");
                assertTrue(button.x() + button.width() <= layout.bounds().x() + layout.bounds().width() - 8,
                        "a button reaches past the painted band");
                assertTrue(button.y() >= layout.bounds().y() + layout.bounds().height() - 49,
                        "a button is above the painted footer band");
                assertTrue(button.y() + button.height() <= layout.bottomBorderStrokeY(),
                        "a button covers the bottom black line");
                // Exactly the footer rows panel.png measures as art (v=124..138 - see
                // theBottomBorderBandIsPaintedWhereTheButtonsSit): the footer blit starts at y+h-49 on texture row
                // v=91, so v = y+h+v-140. The buttons occupy v=125..137 (user 09-20 pixel nudge: up 1 px), so they
                // still sit on painted art, clear of the inner edge v=123 by one row and of the outer black line
                // v=139 by two, with v=138 deliberately left free.
                assertEquals(layout.bounds().y() + layout.bounds().height() - 15, button.y(),
                        "the buttons must start on footer row v=125");
                assertEquals(layout.bounds().y() + layout.bounds().height() - 3, button.y() + button.height() - 1,
                        "the buttons must end on footer row v=137");
                assertTrue(button.y() > layout.bounds().y() + layout.bounds().height() - 17,
                        "the buttons must stay clear of the inner border line (v=123)");
                assertTrue(button.y() >= layout.bounds().y() + layout.bounds().height() - 16,
                        "the buttons must stay inside the painted footer rows (v>=124)");
            }
            // The user's 09-20 nudge, asserted as exact offsets from the two border lines: left 2 px, up 1 px
            // (i.e. the buttons' bottom row is two rows above the outer black line, one row above v=138).
            assertEquals(layout.rightBorderStrokeX() - 2, fold.x() + fold.width() - 1,
                    "the fold button's right edge must sit exactly 2 px left of the right black line");
            assertEquals(layout.bottomBorderStrokeY() - 2, fold.y() + fold.height() - 1,
                    "both buttons' bottom edge must sit exactly 2 px above the bottom black line");
            // The cap's painted columns (texture u=53..66) begin at x+w-22; with the user's 09-20 nudge (left 2 px)
            // the fold button's right edge now sits on u=64 and the transfer button moves to u=38..50, still
            // inside the centre band that frame() fills by repeating the one painted centre column u=44.
            assertEquals(layout.bounds().x() + layout.bounds().width() - 11, fold.x() + fold.width() - 1,
                    "the fold button's right edge must land on texture u=64 (2 px left of the inner line u=66)");
            assertTrue(transfer.x() >= layout.bounds().x() + 22,
                    "the transfer button must stay inside the centre band the repeated column u=44 fills");
            assertTrue(transfer.x() + transfer.width() - 1 <= layout.bounds().x() + layout.bounds().width() - 23,
                    "the transfer button must not run into the right cap");
            assertEquals(fold.x() - PanelLayout.BUTTON - 1, transfer.x(),
                    "exactly one clear pixel between the two buttons");
            assertEquals(fold.y(), transfer.y(), "the two buttons share the band");
            assertFalse(intersects(fold, transfer), "the two buttons overlap");
            for (var box : layout.cells()) {
                assertFalse(intersects(fold, box.bounds()), "the fold button covers a cell");
                assertFalse(intersects(transfer, box.bounds()), "the transfer button covers a cell");
            }
            assertFalse(intersects(fold, layout.address()) || intersects(transfer, layout.address()),
                    "a button covers the address bar");
            if (layout.returnBar() != null) {
                assertFalse(intersects(fold, layout.returnBar()) || intersects(transfer, layout.returnBar()),
                        "a button covers the return bar");
            }
            // The entry keeps the fold button's place, so one visible means the other is drawable.
            assertEquals(fold, PanelLayout.hidden(480, 300, 40, grid, false).bounds());
        }
    }
    /** Hiding the panel leaves only the entry, frees the whole old area, and unfolds cell-for-cell identical. */
    @Test void hidingThePanelLeavesOnlyItsEntryAndDoesNotCoverTheGridArea() {
        for (int count : new int[]{9, 16, 24, 30, 36}) {
            var grid = CacheGrid.forCount(count);
            var expanded = PanelLayout.compute(480, 300, 40, grid, 0, -1, false);
            var hidden = PanelLayout.hidden(480, 300, 40, grid, false);
            assertTrue(hidden.hidden());
            // The one entry left on screen is exactly the small square the fold button occupied.
            assertEquals(expanded.foldButton(), hidden.bounds(), "the entry must not jump somewhere else");
            assertEquals(PanelLayout.BUTTON, hidden.bounds().width());
            assertEquals(PanelLayout.BUTTON, hidden.bounds().height());
            assertEquals(hidden.bounds(), hidden.foldButton(), "the entry is the fold/entry button");
            assertEquals(0, hidden.transferButton().width(), "there is no collect button while hidden");
            assertTrue(hidden.cells().isEmpty(), "nothing of the grid is drawn while hidden");
            assertEquals(0, hidden.totalRows());
            // Click-through, structurally: the layout covers nothing of the area the panel used to occupy.
            for (var box : expanded.cells()) {
                int cx = box.bounds().x() + box.bounds().width() / 2;
                int cy = box.bounds().y() + box.bounds().height() / 2;
                assertFalse(hidden.bounds().contains(cx, cy),
                        "the hidden layout still covers the cell area at slot " + box.slot());
            }
            assertFalse(hidden.bounds().contains(expanded.bounds().x() + 2, expanded.bounds().y() + 2),
                    "the hidden layout still covers the panel body");
            assertFalse(hidden.bounds().contains(expanded.scrollbar().x(), expanded.scrollbar().y()),
                    "the hidden layout still covers the scrollbar rail");
            // Unfolding gives back exactly the same grid.
            var again = PanelLayout.compute(480, 300, 40, grid, 0, -1, false);
            assertEquals(expanded.bounds(), again.bounds());
            assertEquals(expanded.cells(), again.cells(), "unfolding must be cell for cell identical");
        }
    }

    /**
     * The three 13x13 button sheets the user drew ship byte for byte and each already carries its own icon, so the
     * direction needs no runtime transform at all. Read from the build output - the same bytes the client loads -
     * and pinned to the exact white pixels he painted, plus the frame and face counts he specified (black frame
     * 48 px, grey face 96 or 105 px, and no transparent pixel in any of the three).
     */
    @Test void theThreeButtonSheetsAreTheUsersThirteenPixelTrio() throws Exception {
        var transfer = readSheet("/assets/create_feed_me_packages/textures/gui/button.png");
        var fold = readSheet("/assets/create_feed_me_packages/textures/gui/button_fold.png");
        var unfold = readSheet("/assets/create_feed_me_packages/textures/gui/button_unfold.png");
        var transferWhite = whitePixels(transfer);
        var foldWhite = whitePixels(fold);
        var unfoldWhite = whitePixels(unfold);
        assertEquals(25, transferWhite.size(), "the transfer sheet's glyph changed");
        assertEquals(java.util.Set.of("6,2", "6,3", "6,4", "4,5", "5,5", "6,5", "7,5", "8,5", "3,6", "5,6",
                "6,6", "7,6", "9,6", "3,7", "6,7", "9,7", "3,8", "9,8", "3,9", "4,9", "5,9", "6,9", "7,9",
                "8,9", "9,9"), transferWhite,
                "the transfer sheet is not the drop-into-the-box glyph the user drew");
        assertEquals(16, foldWhite.size(), "the fold sheet's chevron changed");
        assertEquals(java.util.Set.of("5,3", "5,4", "6,4", "5,5", "6,5", "7,5", "5,6", "6,6", "7,6", "8,6",
                "5,7", "6,7", "7,7", "5,8", "6,8", "5,9"), foldWhite,
                "the fold sheet is not the right-pointing chevron the user drew");
        assertEquals(16, unfoldWhite.size(), "the unfold sheet's chevron changed");
        assertEquals(java.util.Set.of("7,3", "6,4", "7,4", "5,5", "6,5", "7,5", "4,6", "5,6", "6,6", "7,6",
                "5,7", "6,7", "7,7", "6,8", "7,8", "7,9"), unfoldWhite,
                "the unfold sheet is not the left-pointing chevron the user drew");
        // Transfer: a downward arrow (a centre column) dropping into a tray (a wide base).
        for (int y = 2; y <= 4; y++) {
            assertTrue(transferWhite.contains("6," + y), "the arrow shaft must be the centre column at row " + y);
        }
        assertEquals(7, rowWidth(transferWhite, 9), "the tray's base must be the widest white row (7 px)");
        assertTrue(transferWhite.contains("3,9") && transferWhite.contains("9,9"),
                "the tray's base must span the glyph's full width");
        assertEquals(7, maxRowWidth(transferWhite), "no other row may be wider than the tray's base");
        // Fold: a chevron whose tip is its right-most white pixel on the middle row. Unfold: the mirror image,
        // tip on the left - that is what makes the hidden entry read "open it back up again".
        assertEquals(8, maxColumn(foldWhite), "the fold chevron's tip must be its right-most white column");
        assertTrue(foldWhite.contains("8,6"), "the fold chevron's tip must sit on the middle row");
        assertEquals(4, minColumn(unfoldWhite), "the unfold chevron's tip must be its left-most white column");
        assertTrue(unfoldWhite.contains("4,6"), "the unfold chevron's tip must sit on the middle row");
        for (var chevron : List.of(foldWhite, unfoldWhite)) {
            assertEquals(1, rowWidth(chevron, 3), "the chevron must start as a single pixel");
            assertEquals(4, rowWidth(chevron, 6), "the chevron must be widest at its middle row");
        }
        assertEquals(mirrored(foldWhite), unfoldWhite, "the unfold sheet must be the fold sheet mirrored");
        // The frame and face the user specified, per sheet, and every pixel opaque.
        assertEquals(48, countBlack(transfer), "the transfer sheet's black frame changed");
        assertEquals(48, countBlack(fold), "the fold sheet's black frame changed");
        assertEquals(48, countBlack(unfold), "the unfold sheet's black frame changed");
        assertEquals(96, countGrey(transfer), "the transfer sheet's grey face changed");
        assertEquals(105, countGrey(fold), "the fold sheet's grey face changed");
        assertEquals(105, countGrey(unfold), "the unfold sheet's grey face changed");
        for (var sheet : List.of(transfer, fold, unfold)) {
            for (int y = 0; y < sheet.getHeight(); y++) {
                for (int x = 0; x < sheet.getWidth(); x++) {
                    assertEquals(255, sheet.getRGB(x, y) >>> 24,
                            "a button sheet pixel is not opaque at " + x + "," + y);
                }
            }
        }
    }

    /** The white pixels of one sheet, as {@code x,y} keys. */
    private static java.util.TreeSet<String> whitePixels(java.awt.image.BufferedImage sheet) {
        var white = new java.util.TreeSet<String>();
        for (int y = 0; y < sheet.getHeight(); y++) {
            for (int x = 0; x < sheet.getWidth(); x++) {
                int argb = sheet.getRGB(x, y);
                if ((argb >>> 24) != 0 && (argb & 0x00FFFFFF) == 0x00FFFFFF) white.add(x + "," + y);
            }
        }
        return white;
    }

    /** A shipped sheet, decoded and checked against the button size. */
    private static java.awt.image.BufferedImage readSheet(String resource) throws Exception {
        var url = PanelLayoutTest.class.getResource(resource);
        assertNotNull(url, resource + " is missing from the mod's own assets");
        var sheet = javax.imageio.ImageIO.read(url);
        assertNotNull(sheet, resource + " is not a readable PNG");
        assertEquals(PanelLayout.BUTTON, sheet.getWidth(), resource + " must be exactly the button size");
        assertEquals(PanelLayout.BUTTON, sheet.getHeight());
        return sheet;
    }

    private static int maxColumn(java.util.Set<String> pixels) {
        return pixels.stream().mapToInt(key -> Integer.parseInt(key.split(",")[0])).max().orElseThrow();
    }

    private static int minColumn(java.util.Set<String> pixels) {
        return pixels.stream().mapToInt(key -> Integer.parseInt(key.split(",")[0])).min().orElseThrow();
    }

    /** The same pixels mirrored inside the sheet - the user's unfold chevron is the fold chevron flipped. */
    private static java.util.Set<String> mirrored(java.util.Set<String> pixels) {
        var flipped = new java.util.TreeSet<String>();
        for (String key : pixels) {
            var parts = key.split(",");
            flipped.add((PanelLayout.BUTTON - 1 - Integer.parseInt(parts[0])) + "," + parts[1]);
        }
        return flipped;
    }

    /** Pixels of a sheet that are its black frame (neutral, darker than the grey face, fully opaque). */
    private static int countBlack(java.awt.image.BufferedImage sheet) { return countNeutral(sheet, 0, 40); }

    /** Pixels of a sheet that are its grey face (neutral, between the frame and the white glyph). */
    private static int countGrey(java.awt.image.BufferedImage sheet) { return countNeutral(sheet, 40, 200); }

    private static int countNeutral(java.awt.image.BufferedImage sheet, int from, int to) {
        int count = 0;
        for (int y = 0; y < sheet.getHeight(); y++) {
            for (int x = 0; x < sheet.getWidth(); x++) {
                int argb = sheet.getRGB(x, y);
                int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
                if ((argb >>> 24) == 255 && r == g && g == b && r >= from && r < to) count++;
            }
        }
        return count;
    }
    private static int rowWidth(java.util.Set<String> pixels, int row) {
        return (int) pixels.stream().filter(key -> Integer.parseInt(key.split(",")[1]) == row).count();
    }

    private static int maxRowWidth(java.util.Set<String> pixels) {
        return pixels.stream().mapToInt(key -> Integer.parseInt(key.split(",")[1]))
                .distinct().map(row -> rowWidth(pixels, row)).max().orElse(0);
    }

    /**
     * Exactly the three sheets the user drew ship in the mod: the whole texture folder is enumerated, so a leftover
     * duplicate (the intermediate {@code button_transfer.png}) or any filler sheet fails the build.
     */
    @Test void theModShipsExactlyTheThreeButtonSheets() throws Exception {
        var sheet = PanelLayoutTest.class.getResource("/assets/create_feed_me_packages/textures/gui/button.png");
        assertNotNull(sheet, "the transfer sheet is missing");
        java.nio.file.Path gui;
        try {
            gui = java.nio.file.Path.of(sheet.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new AssertionError("the shipped assets are not a plain folder: " + sheet, e);
        }
        var shipped = java.nio.file.Files.list(gui)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".png"))
                .sorted()
                .toList();
        assertEquals(List.of("button.png", "button_fold.png", "button_unfold.png", "panel.png", "slot_source.png"),
                shipped, "the texture folder must hold exactly the three button sheets plus panel and slot art");
    }
    /**
     * The three button tooltips are capped at FOUR Chinese characters (user: "按钮说明删减到四个字"), which is what
     * fits next to a 7x7 button. Read from the shipped lang files - the same bytes the client loads - and this
     * change only shortens three values: the mod keeps exactly the keys it had.
     */
    @Test void theThreeButtonTooltipsStayWithinFourChineseCharacters() throws Exception {
        var zh = langKeys("/assets/create_feed_me_packages/lang/zh_cn.json");
        var en = langKeys("/assets/create_feed_me_packages/lang/en_us.json");
        for (String key : new String[]{"gui.create_feed_me_packages.collect_button",
                "gui.create_feed_me_packages.fold_button", "gui.create_feed_me_packages.unfold_button"}) {
            String zhText = zh.get(key);
            String enText = en.get(key);
            assertNotNull(zhText, key + " missing in zh_cn");
            assertNotNull(enText, key + " missing in en_us");
            assertTrue(zhText.codePointCount(0, zhText.length()) <= 4,
                    key + " is longer than four characters: " + zhText);
            assertTrue(enText.trim().split("\\s+").length <= 2, key + " English text is not terse: " + enText);
        }
        assertEquals(110, zh.size(), "no new lang key may be added (raise this number deliberately)");
        assertEquals(zh.keySet(), en.keySet(), "en_us and zh_cn must stay key-for-key identical");
    }

    /** Minimal reader for our own flat lang files: one {@code "key": "value"} per line. */
    private static java.util.Map<String, String> langKeys(String resource) throws Exception {
        try (var in = PanelLayoutTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is missing from the mod's own assets");
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var pattern = java.util.regex.Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*\"(.*?)\"\\s*,?\\s*$",
                    java.util.regex.Pattern.MULTILINE);
            var matcher = pattern.matcher(text);
            var keys = new java.util.LinkedHashMap<String, String>();
            while (matcher.find()) keys.put(matcher.group(1), matcher.group(2));
            return keys;
        }
    }

    /**
     * Evidence for "hiding never puts the panel (or its entry) somewhere wrong": across the screen geometries
     * and container screens this panel actually meets (vanilla inventory, creative inventory, crafting table all
     * centre the GUI the same way, but the panel must not ASSUME that - it takes {@code left}/{@code top} as
     * given), the entry lands exactly where the fold button was, stays on screen, and never reaches into the
     * inventory area. Nothing has to be moved to hide: the same input position produces the entry.
     */
    @Test void hidingKeepsTheEntryWhereTheFoldButtonWasOnEveryScreenGeometry() {
        int[][] screens = {{854, 480}, {1280, 720}, {1920, 1080}, {640, 400}, {480, 320}};
        int[] guiWidths = {176, 176, 176, 176, 176};   // vanilla inventory, creative and crafting screens
        for (int index = 0; index < screens.length; index++) {
            int width = screens[index][0];
            int height = screens[index][1];
            int guiLeft = (width - guiWidths[index]) / 2;
            for (int top : new int[]{40, 90, 200, height / 2}) {
                for (int count : new int[]{9, 16, 24, 30, 36}) {
                    var grid = CacheGrid.forCount(count);
                    var expanded = PanelLayout.compute(height, guiLeft, top, grid, 0, -1, false);
                    var hidden = PanelLayout.hidden(height, guiLeft, top, grid, false);
                    assertTrue(hidden.hidden());
                    assertEquals(expanded.foldButton(), hidden.bounds(),
                            "the entry moved on a " + width + "x" + height + " screen at top " + top);
                    assertTrue(hidden.bounds().x() >= 0 && hidden.bounds().y() >= 0,
                            "the entry left the screen at " + width + "x" + height);
                    assertTrue(hidden.bounds().x() + hidden.bounds().width() <= width);
                    assertTrue(hidden.bounds().y() + hidden.bounds().height() <= height);
                    // It never reaches into the inventory screen - whatever screen that is.
                    assertTrue(hidden.bounds().x() + hidden.bounds().width() <= guiLeft - PanelLayout.GAP,
                            "the entry reaches into the GUI area on a " + width + "x" + height + " screen");
                }
            }
        }
    }
    /**
     * The bottom border band's real pixels, measured from the shipped panel.png: the footer art paints all 49 rows
     * of the right cap down to the panel's last row (which is the outer black line), and the 1 px centre column
     * that fills the band between the caps is painted too - so a button placed there cannot fall into a hole.
     */
    @Test void theBottomBorderBandIsPaintedWhereTheButtonsSit() throws Exception {
        var url = PanelLayoutTest.class.getResource("/assets/create_feed_me_packages/textures/gui/panel.png");
        assertNotNull(url, "panel.png is missing from the mod's own assets");
        var panel = javax.imageio.ImageIO.read(url);
        assertNotNull(panel);
        int paintedRows = 0;
        for (int v = 91; v < 140; v++) {
            int painted = 0;
            for (int u = 53; u <= 66; u++) if ((panel.getRGB(u, v) >>> 24) != 0) painted++;
            if (painted == 14) paintedRows++;
        }
        assertEquals(49, paintedRows, "the footer art must paint all 49 rows of the right cap");
        for (int u = 53; u <= 66; u++) {
            assertEquals(0x000000, panel.getRGB(u, 139) & 0x00FFFFFF,
                    "the panel's bottom row must be the outer black line at u=" + u);
        }
        // Measured, not assumed: in every row the buttons reach into (v=125..137, after the user's up-1 nudge) the
        // footer art paints exactly 30 columns - u=26..39 (left cap), the single centre column u=44 that frame()
        // repeats across the whole middle band, and u=53..67 (right cap plus its inner line). So both rects sit on
        // art, never on a transparent hole. The rows v=124 and v=138 are painted too but deliberately left free.
        for (int v = 125; v <= 137; v++) {
            int painted = 0;
            for (int u = 26; u <= 67; u++) if ((panel.getRGB(u, v) >>> 24) != 0) painted++;
            assertEquals(30, painted, "footer row v=" + v + " must paint exactly the 30 art columns");
            assertTrue((panel.getRGB(44, v) >>> 24) != 0,
                    "the repeated centre column must be painted in the rows the buttons use (v=" + v + ")");
        }
        assertTrue((panel.getRGB(44, 120) >>> 24) != 0,
                "the 1 px centre column that fills the band between the caps must be painted");
    }

    /** The shipped bytecode must draw the three ready-made sheets and must not carry the old fill-glyph path. */
    @Test void theShippedButtonCodeDrawsTheThreeSheetsAndHasNoFillGlyphPathLeft() throws Exception {
        try (var in = PanelLayoutTest.class
                .getResourceAsStream("/dev/scathiard/feedmepackages/client/LogisticsPanel.class")) {
            assertNotNull(in, "the compiled panel class must be on the test classpath");
            String code = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(code.contains("textures/gui/button.png"), "the transfer sheet must ship");
            assertTrue(code.contains("textures/gui/button_fold.png"), "the fold sheet must ship");
            assertTrue(code.contains("textures/gui/button_unfold.png"), "the unfold sheet must ship");
            for (String gone : new String[]{"glyphFills", "BUTTON_GLYPH", "textures/gui/arrow.png", "drawArrow",
                    "ARROW_SCALE", "ARROW_SHEET", "buttonPixelX", "textures/gui/button_left.png",
                    "textures/gui/button_transfer.png"}) {
                assertFalse(code.contains(gone), "the old path is still in the shipped class: " + gone);
            }
        }
    }

    /**
     * The code-level half of the direction ruling and of "the fold button was invisible": each call site picks one
     * ready-made sheet, there is no mirroring helper, no direction flag and no negative scale anywhere, and the
     * button calls are the last thing {@code render} does, so nothing drawn afterwards can cover their rect.
     */
    @Test void theButtonDrawPathIsSingleAndIsTheLastThingRenderDraws() throws Exception {
        var source = java.nio.file.Path.of("src/main/java/dev/scathiard/feedmepackages/client/LogisticsPanel.java");
        assertTrue(java.nio.file.Files.exists(source), "run the tests from the project directory: " + source);
        String code = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(code.contains("scale(-1"), "no negative scale (mirror) may come back");
        assertFalse(code.contains("buttonPixelX") || code.contains("boolean flipped"),
                "no mirroring helper or direction flag may come back - direction comes from the sheet");
        assertFalse(code.contains("collectButton") || code.contains("collapseButton"),
                "the old mid-edge button geometry must be gone");
        assertTrue(code.contains(
                "LogisticsPanel.iconButton(g, layout.transferButton(), BUTTON_TEXTURE, \"collect_button\","),
                "the one-key collect must draw the transfer sheet");
        assertTrue(code.contains("LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_FOLD_TEXTURE,"),
                "the fold button must draw the fold sheet");
        assertTrue(code.contains(
                "LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_UNFOLD_TEXTURE, \"unfold_button\", true);"),
                "the hidden entry must draw the unfold sheet");
        int hiddenBranch = code.indexOf("if (layout.hidden()) {");
        int hiddenReturn = code.indexOf("return;", hiddenBranch);
        String hidden = code.substring(hiddenBranch, hiddenReturn);
        assertTrue(hidden.contains("renderEntry(g);"), "the hidden state draws exactly the entry");
        assertFalse(hidden.contains("blit") || hidden.contains("fill") || hidden.contains("overlay("),
                "nothing else may be drawn in the hidden state");
        int fold = code.indexOf("LogisticsPanel.renderFoldButton(g);");
        assertTrue(fold > 0, "the fold button must be drawn");
        String tail = code.substring(fold);
        int end = tail.indexOf("\n    }");
        assertTrue(end > 0, "the render method must end after the button calls");
        String after = tail.substring(0, end);
        for (String painter : new String[]{"blit", "fill", "overlay(", "text(", "render"}) {
            assertFalse(after.replace("LogisticsPanel.renderFoldButton(g);", "").contains(painter),
                    "something is still drawn after the fold button: " + painter);
        }
    }

    /**
     * The three buttons click with the <b>vanilla</b> sound and nothing else does: one {@code UI_BUTTON_CLICK} play
     * per button press path, each positioned after that button accepted the press (so the disabled collect stays
     * silent), at the vanilla pitch, with no custom sound file and no custom values anywhere. Read from the shipped
     * source and from the compiled class the jar carries. Whether it is actually audible is the user's to confirm -
     * this pins the code shape.
     */
    @Test void theThreeButtonsClickWithTheVanillaSoundAndDisabledStaysSilent() throws Exception {
        var source = java.nio.file.Path.of("src/main/java/dev/scathiard/feedmepackages/client/LogisticsPanel.java");
        assertTrue(java.nio.file.Files.exists(source), "run the tests from the project directory: " + source);
        String code = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(code.contains(
                "LogisticsPanel.MC.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));"),
                "the click must be the vanilla UI_BUTTON_CLICK at the vanilla pitch 1.0F");
        assertEquals(1, occurrences(code, "private static void playButtonClick()"),
                "there must be exactly one click helper");
        assertEquals(3, occurrences(code, "LogisticsPanel.playButtonClick();"),
                "exactly the three buttons may click (collect, fold, hidden entry)");
        int entryBranch = code.indexOf("if (layout.hidden()) {");
        int foldBranch = code.indexOf("if (inputLayout.foldButton().contains(x, y)) {");
        int transferBranch = code.indexOf("if (inputLayout.transferButton().contains(x, y)) {");
        assertTrue(entryBranch > 0 && foldBranch > entryBranch && transferBranch > foldBranch,
                "the three button branches must exist in this order");
        String entry = code.substring(entryBranch, foldBranch);
        String fold = code.substring(foldBranch, transferBranch);
        String transfer = code.substring(transferBranch, code.indexOf("int px = layout.bounds().x();", transferBranch));
        assertEquals(1, occurrences(entry, "LogisticsPanel.playButtonClick();"),
                "the hidden entry must click exactly once");
        assertEquals(1, occurrences(fold, "LogisticsPanel.playButtonClick();"),
                "the fold button must click exactly once");
        assertEquals(1, occurrences(transfer, "LogisticsPanel.playButtonClick();"),
                "the one-key collect must click exactly once, even though it also sends an action");
        // Disabled collect: the guarded early return comes BEFORE the play, and that branch has no play at all.
        int disabled = transfer.indexOf("if (!LogisticsPanel.collectEnabled()) {");
        int play = transfer.indexOf("LogisticsPanel.playButtonClick();");
        assertTrue(disabled > 0, "the disabled guard must exist");
        assertTrue(disabled < play, "the disabled guard must come before the click");
        assertTrue(transfer.substring(disabled, play).contains("return true;"),
                "a disabled button must return before it can click");
        assertTrue(transfer.substring(disabled, play).indexOf("playButtonClick") < 0,
                "the disabled branch must be silent");
        // Everything after the button branches (address bar, scrollbar, slider, cells) stays silent.
        assertTrue(code.lastIndexOf("LogisticsPanel.playButtonClick();")
                        < code.indexOf("if (inputLayout.address().contains(x, y)) {", transferBranch),
                "no other interaction may click: address, scrollbar, slider and cells stay silent");
        // The compiled class the jar carries references the vanilla click, and no custom sound ships.
        try (var in = PanelLayoutTest.class
                .getResourceAsStream("/dev/scathiard/feedmepackages/client/LogisticsPanel.class")) {
            assertNotNull(in, "the compiled panel class must be on the test classpath");
            String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("UI_BUTTON_CLICK"), "the shipped class must reference the vanilla click sound");
            assertTrue(bytes.contains("SimpleSoundInstance") && bytes.contains("forUI"),
                    "the shipped class must play through the vanilla SimpleSoundInstance.forUI");
        }
        var assets = java.nio.file.Path.of("src/main/resources/assets/create_feed_me_packages");
        try (var files = java.nio.file.Files.walk(assets)) {
            var audio = files.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".ogg") || name.endsWith(".wav") || name.equals("sounds.json"))
                    .toList();
            assertEquals(List.of(), audio, "no custom sound file may be added - the vanilla click is reused");
        }
    }

    /**
     * The rule the user confirmed ("改：点格子装不下就自动找它自己的格") lives in exactly one place, and both click
     * paths must reach it. This pins the shape: the domain answers ({@code CacheDropTarget.resolveDrop}), the panel
     * only routes, and the guard ladder keeps every other interaction ahead of the drop - so a later edit cannot
     * quietly move a click from the aimed cell to somewhere else.
     */
    @Test void bothClickPathsRouteThroughTheOneAimedDropAndTheGuardLadderStandsInOrder() throws Exception {
        var source = java.nio.file.Path.of("src/main/java/dev/scathiard/feedmepackages/client/LogisticsPanel.java");
        assertTrue(java.nio.file.Files.exists(source), "run the tests from the project directory: " + source);
        String code = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, occurrences(code, "private static void depositAt("), "one drop helper, not two");
        assertEquals(4, occurrences(code, "LogisticsPanel.depositAt("),
                "both click paths must route both ends of a drop (the aimed cell and the blank space)");
        assertEquals(1, occurrences(code, "CacheDropTarget.resolveDrop("),
                "the rule is asked in exactly one place, and that place is the domain");
        assertFalse(code.contains("CacheDropTarget.resolve("), "the panel may not keep a second resolution path");
        int press = code.indexOf("private static boolean press(");
        int release = code.indexOf("private static boolean release(");
        int scroll = code.indexOf("private static boolean scroll(");
        assertTrue(press > 0 && release > press && scroll > release, "both click paths must exist, press first");
        for (String body : List.of(code.substring(press, release), code.substring(release, scroll))) {
            assertTrue(body.contains("LogisticsPanel.depositAt(box.slot(), button)"), "the aimed cell must route");
            assertTrue(body.contains("LogisticsPanel.depositAt(-1, button)"), "the blank space must route");
        }
        // The guard ladder, in the order a click meets it: everything else still decides before the drop does.
        int at = press;
        for (String guard : List.of(
                "if (!LogisticsPanel.visible()) {",
                "if (layout.hidden()) {",
                "if (waiting != 0) {",
                "if (!insidePanel) {",
                "if (returnClose == ReturnClose.SUBMITTED) {",
                "if (button != 0 && button != 1) {",
                "if (LogisticsPanel.active() && inputLayout.returnAddressContains(x, y)) {",
                "if (inputLayout.compact()) {",
                "if (inputLayout.foldButton().contains(x, y)) {",
                "if (inputLayout.transferButton().contains(x, y)) {",
                "if (inputLayout.address().contains(x, y)) {",
                "if (inputLayout.totalRows() > inputLayout.visibleRows() && rail.contains(x, y)) {",
                "if (!LogisticsPanel.active()) {",
                "for (PanelLayout.CellBox box : inputLayout.cells()) {",
                "LogisticsPanel.depositAt(-1, button)")) {
            int found = code.indexOf(guard, at);
            assertTrue(found > at, "the guard ladder moved or lost a step: " + guard);
            at = found;
        }
    }

    /**
     * The premise of that rule, pinned: the panel body is an 18x18 gapless grid, so on the full levels (30 and
     * 36 cells) a drop "on the panel" cannot land on blank space at all - it lands on a cell, which is exactly why
     * the aimed cell has to fall back to the item's own cell. The only blank points inside the panel are the two
     * 22 px wood border strips (where the user's drop actually worked), the header's free columns, the return bar
     * and the footer band. The levels whose 0.2.2 outline is not a rectangle do have holes - pinned here as well,
     * because they are the only blank points inside the grid area.
     */
    @Test void thePanelBodyIsAGaplessGridSoAnAimedDropLandsOnACell() {
        assertEquals(18, PanelLayout.ROW);
        assertEquals(22, PanelLayout.SIDE);
        assertEquals(List.of("4,1"), gridHoles(1), "level 1 is 2 columns of 5/4: one hole");
        assertEquals(List.of("5,1", "5,2"), gridHoles(2), "level 2 is 3 columns of 6/5/5: two holes");
        assertEquals(List.of(), gridHoles(3));
        assertEquals(List.of(), gridHoles(4));
        assertEquals(List.of(), gridHoles(5));
        for (int level = 1; level <= 5; level++) {
            int count = CacheGrid.forLevel(level).count();
            var grid = CacheGrid.forCount(count);
            var layout = PanelLayout.compute(480, 300, 40, grid, 0, -1, false);
            assertFalse(layout.compact());
            int gridX = layout.bounds().x() + PanelLayout.SIDE;
            int gridY = layout.bounds().y() + PanelLayout.HEADER;
            int blanks = 0;
            for (int row = 0; row < grid.rows(); row++)
                for (int column = 0; column < grid.columns(); column++) {
                    int x = gridX + column * PanelLayout.ROW + 1;
                    int y = gridY + row * PanelLayout.ROW + 1;
                    boolean covered = insideACell(layout, x, y);
                    if (grid.slot(row, column) < 0) {
                        assertFalse(covered, "a hole must stay blank at row " + row + " column " + column);
                        blanks++;
                    } else {
                        assertTrue(covered, "every cell position must be covered at row " + row + " column " + column);
                    }
                }
            assertEquals(gridHoles(level).size(), blanks, "the body's blank points are exactly the outline's holes");
            // The full levels are the reason this rule exists: their grid area has no blank point at all.
            if (count == 30 || count == 36) assertEquals(0, blanks, "a " + count + "-cell body is covered cell for cell");
            // The wood borders are inside the panel and outside every cell: that drop is the aim-free one.
            int midY = gridY + 1;
            assertTrue(layout.bounds().contains(layout.bounds().x() + 1, midY), "the left border is inside the panel");
            assertFalse(insideACell(layout, layout.bounds().x() + 1, midY), "the left wood border must be blank");
            assertFalse(insideACell(layout, layout.bounds().x() + PanelLayout.SIDE - 1, midY),
                    "the pixel inside the left border must be blank");
            assertFalse(insideACell(layout, layout.bounds().x() + layout.bounds().width() - 2, midY),
                    "the right wood border must be blank");
        }
    }

    private static boolean insideACell(PanelLayout layout, int x, int y) {
        for (var box : layout.cells()) if (box.bounds().contains(x, y)) return true;
        return false;
    }

    /** A level outline's coordinates that carry no cell (a "5/4" column pair leaves a hole in the short one). */
    private static List<String> gridHoles(int level) {
        var grid = CacheGrid.forLevel(level);
        List<String> holes = new ArrayList<>();
        for (int row = 0; row < grid.rows(); row++)
            for (int column = 0; column < grid.columns(); column++)
                if (grid.slot(row, column) < 0) holes.add(row + "," + column);
        return holes;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }
    /**
     * The cell background sheet was cropped: the shipped {@code slot_source.png} used to be the user's full 256x256
     * panel reference of which only the 18x18 block at (101,65) was ever sampled; those exact 324 pixels now sit at
     * (0,0) and the file is 18x18. Size, colour histogram and a fingerprint of the pixel stream pin the crop, so any
     * silent re-export, resize or channel change fails the build. The reference sheet lives outside the mod.
     */
    @Test void theCroppedCellBackgroundCarriesTheSameThreeHundredAndTwentyFourPixels() throws Exception {
        var url = PanelLayoutTest.class.getResource("/assets/create_feed_me_packages/textures/gui/slot_source.png");
        assertNotNull(url, "the slot sheet is missing from the mod's own assets");
        var sheet = javax.imageio.ImageIO.read(url);
        assertNotNull(sheet, "the slot sheet is not a readable PNG");
        assertEquals(18, sheet.getWidth(), "the shipped slot sheet is the cropped 18x18 block");
        assertEquals(18, sheet.getHeight());
        assertTrue(sheet.getColorModel().hasAlpha(), "the slot sheet keeps its alpha channel");
        var histogram = new java.util.TreeMap<String, Integer>();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                int argb = sheet.getRGB(x, y);
                assertEquals(255, argb >>> 24, "the slot art is fully opaque at " + x + "," + y);
                String rgb = (argb >>> 16 & 0xFF) + "," + (argb >>> 8 & 0xFF) + "," + (argb & 0xFF);
                histogram.merge(rgb, 1, Integer::sum);
                digest.update((byte)(argb >>> 24)); digest.update((byte)(argb >>> 16));
                digest.update((byte)(argb >>> 8)); digest.update((byte)(argb));
            }
        }
        var expectedHistogram = new java.util.TreeMap<String, Integer>();
        expectedHistogram.put("109,69,59", 128);
        expectedHistogram.put("113,74,64", 128);
        expectedHistogram.put("96,61,57", 32);
        expectedHistogram.put("74,45,49", 18);
        expectedHistogram.put("181,147,112", 16);
        expectedHistogram.put("162,124,96", 2);
        assertEquals(expectedHistogram, histogram, "the cropped sheet's colour histogram is not the original block's");
        var hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        assertEquals("fd195c3a9cd355f77c04083383afdaa730838a5fbe36a6c66ca6cb5cb1cbc952", hex.toString(),
                "the cropped sheet's pixels are not the original (101,65) block byte for byte");
        assertTrue(java.nio.file.Files.size(java.nio.file.Path.of(
                "src/main/resources/assets/create_feed_me_packages/textures/gui/slot_source.png")) < 1024,
                "the cropped sheet should be a small file, not the 256x256 reference");
    }
}