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
            }
            assertEquals(layout.rightBorderStrokeX(), fold.x() + fold.width() - 1,
                    "the fold button must hug the right black line");
            assertEquals(layout.bottomBorderStrokeY() - 1, fold.y() + fold.height() - 1,
                    "both buttons must hug the bottom black line");
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
     * The two 13x13 button sheets the user drew ship byte for byte and each already carries its own icon, so the
     * direction needs no runtime transform at all. Read from the build output - the same bytes the client loads -
     * and pinned to the exact white pixels he painted.
     */
    @Test void theTwoButtonSheetsAreTheUsersThirteenPixelPair() throws Exception {
        var fold = readSheet("/assets/create_feed_me_packages/textures/gui/button.png");
        var transfer = readSheet("/assets/create_feed_me_packages/textures/gui/button_transfer.png");
        var foldWhite = whitePixels(fold);
        var transferWhite = whitePixels(transfer);
        assertEquals(16, foldWhite.size(), "the fold sheet's chevron changed");
        assertEquals(25, transferWhite.size(), "the transfer sheet's glyph changed");
        assertEquals(java.util.Set.of("5,3", "5,4", "6,4", "5,5", "6,5", "7,5", "5,6", "6,6", "7,6", "8,6",
                "5,7", "6,7", "7,7", "5,8", "6,8", "5,9"), foldWhite,
                "the fold sheet is not the right-pointing chevron the user drew");
        assertEquals(java.util.Set.of("6,2", "6,3", "6,4", "4,5", "5,5", "6,5", "7,5", "8,5", "3,6", "5,6",
                "6,6", "7,6", "9,6", "3,7", "6,7", "9,7", "3,8", "9,8", "3,9", "4,9", "5,9", "6,9", "7,9",
                "8,9", "9,9"), transferWhite,
                "the transfer sheet is not the drop-into-the-box glyph the user drew");
        // Fold: a chevron - the tip is its right-most white pixel, on the middle row.
        assertEquals(8, maxColumn(foldWhite), "the fold chevron's tip must be its right-most white column");
        assertTrue(foldWhite.contains("8,6"), "the fold chevron's tip must sit on the middle row");
        assertEquals(1, rowWidth(foldWhite, 3), "the chevron must start as a single pixel");
        assertEquals(4, rowWidth(foldWhite, 6), "the chevron must be widest at its middle row");
        // Transfer: a downward arrow (a centre column) dropping into a tray (a wide base).
        for (int y = 2; y <= 4; y++) {
            assertTrue(transferWhite.contains("6," + y), "the arrow shaft must be the centre column at row " + y);
        }
        assertEquals(7, rowWidth(transferWhite, 9), "the tray's base must be the widest white row (7 px)");
        assertTrue(transferWhite.contains("3,9") && transferWhite.contains("9,9"),
                "the tray's base must span the glyph's full width");
        assertEquals(7, maxRowWidth(transferWhite), "no other row may be wider than the tray's base");
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
    private static int rowWidth(java.util.Set<String> pixels, int row) {
        return (int) pixels.stream().filter(key -> Integer.parseInt(key.split(",")[1]) == row).count();
    }

    private static int maxRowWidth(java.util.Set<String> pixels) {
        return pixels.stream().mapToInt(key -> Integer.parseInt(key.split(",")[1]))
                .distinct().map(row -> rowWidth(pixels, row)).max().orElse(0);
    }

    /** Exactly the two sheets the user drew ship in the mod - no second revision, no filler sheet. */
    @Test void theModShipsExactlyTheTwoButtonSheets() {
        for (String present : new String[]{"button.png", "button_transfer.png"}) {
            assertNotNull(PanelLayoutTest.class.getResource(
                    "/assets/create_feed_me_packages/textures/gui/" + present), present + " is missing");
        }
        for (String gone : new String[]{"button_left.png", "button_7x7.png", "button_fold.png", "arrow.png"}) {
            assertNull(PanelLayoutTest.class.getResource(
                    "/assets/create_feed_me_packages/textures/gui/" + gone), gone + " must not ship");
        }
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
        assertTrue((panel.getRGB(44, 120) >>> 24) != 0,
                "the 1 px centre column that fills the band between the caps must be painted");
    }

    /** The shipped bytecode must draw the two ready-made sheets and must not carry the old fill-glyph path. */
    @Test void theShippedButtonCodeDrawsTheTwoSheetsAndHasNoFillGlyphPathLeft() throws Exception {
        try (var in = PanelLayoutTest.class
                .getResourceAsStream("/dev/scathiard/feedmepackages/client/LogisticsPanel.class")) {
            assertNotNull(in, "the compiled panel class must be on the test classpath");
            String code = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(code.contains("textures/gui/button.png"), "the fold sheet must ship");
            assertTrue(code.contains("textures/gui/button_transfer.png"), "the transfer sheet must ship");
            for (String gone : new String[]{"glyphFills", "BUTTON_GLYPH", "textures/gui/arrow.png", "drawArrow",
                    "ARROW_SCALE", "ARROW_SHEET", "buttonPixelX", "textures/gui/button_left.png"}) {
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
                "LogisticsPanel.iconButton(g, layout.transferButton(), BUTTON_TRANSFER_TEXTURE, \"collect_button\","),
                "the transfer button must draw the transfer sheet");
        assertTrue(code.contains("LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_TEXTURE,"),
                "the fold button must draw the fold sheet");
        assertTrue(code.contains(
                "LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_TEXTURE, \"unfold_button\", true);"),
                "the hidden entry must draw the fold sheet");
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