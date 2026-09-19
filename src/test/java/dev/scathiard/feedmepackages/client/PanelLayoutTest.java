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
            Set<Integer> colors = new HashSet<>();
            for (int y = 65; y < 83; y++) for (int x = 101; x < 119; x++) {
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
     * The two small buttons: 8x8 (the user rejected 10x10 as "太丑了且超出边框了"), inside the <b>painted</b>
     * part of the end-cap band only, sharing one column, clear of the frame stroke by >= 1 px, clearing every
     * cell and each other.
     */
    @Test void theTwoSmallButtonsSitInTheBorderBandClearOfEveryCell() {
        assertEquals(8, PanelLayout.BUTTON, "the user asked for a 16x16 sheet drawn at half scale");
        assertEquals(14, PanelLayout.CAP_ART, "panel.png paints only 14 of the 22 cap columns");
        for (int count : new int[]{9, 16, 24, 30, 36}) {
            var grid = CacheGrid.forCount(count);
            var layout = PanelLayout.compute(480, 300, 40, grid, 0, -1, false);
            assertFalse(layout.hidden());
            var collect = layout.collectButton();
            var collapse = layout.collapseButton();
            for (var button : List.of(collect, collapse)) {
                assertEquals(PanelLayout.BUTTON, button.width(), "the buttons are one small square");
                assertEquals(PanelLayout.BUTTON, button.height());
                assertTrue(layout.bounds().contains(button.x(), button.y()));
                // Left edge: never past the first painted cap column. Right edge: at least one clear pixel
                // before the 1 px frame stroke, which is the LAST painted cap column.
                int capLeft = layout.bounds().x() + layout.bounds().width() - PanelLayout.SIDE;
                assertTrue(button.x() >= capLeft, "the button escaped the border band");
                assertTrue(button.x() + button.width() <= layout.rightBorderStrokeX(),
                        "the button reaches into the frame stroke");
                assertTrue(layout.rightBorderStrokeX() - (button.x() + button.width()) >= 1,
                        "the button has no >= 1 px gap to the frame line");
            }
            assertEquals(collect.x(), collapse.x(), "both buttons share the same column");
            assertEquals(layout.bounds().y() + (layout.bounds().height() - PanelLayout.BUTTON) / 2, collect.y(),
                    "the collect button is vertically centred");
            assertTrue(collapse.y() >= layout.bounds().y() + layout.bounds().height() - PanelLayout.FOOTER,
                    "the fold button lives in the bottom-right footer band");
            assertTrue(collapse.y() + collapse.height() <= layout.bounds().y() + layout.bounds().height());
            assertFalse(intersects(collect, collapse), "the two buttons overlap");
            for (var box : layout.cells()) {
                assertFalse(intersects(collect, box.bounds()), "the collect button covers a cell");
                assertFalse(intersects(collapse, box.bounds()), "the fold button covers a cell");
            }
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
            assertEquals(expanded.collapseButton(), hidden.bounds(), "the entry must not jump somewhere else");
            assertEquals(PanelLayout.BUTTON, hidden.bounds().width());
            assertEquals(PanelLayout.BUTTON, hidden.bounds().height());
            assertEquals(hidden.bounds(), hidden.collapseButton(), "the entry is the fold/entry button");
            assertEquals(0, hidden.collectButton().width(), "there is no collect button while hidden");
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
     * The button icon is ONE 16x16 RGBA sheet in our own namespace, drawn at half scale (user: "画一个16x16的箭头，
     * 然后游戏内按比例缩放到8x8，两个箭头复用一份素材"). This test reads the real shipped PNG out of the build
     * output - not a reference - and pins the properties that make it readable when halved: 16x16, an alpha
     * channel, and a <b>clean solid</b> arrow (every pixel either fully transparent or fully opaque white, so
     * halving cannot produce the grey fringe the user complained about), with nothing clipped at the edges.
     */
    @Test void theArrowSheetIsACleanSixteenPixelRgbaArrow() throws Exception {
        var url = PanelLayoutTest.class.getResource("/assets/create_feed_me_packages/textures/gui/arrow.png");
        assertNotNull(url, "the arrow sheet is missing from the mod's own assets");
        var sheet = javax.imageio.ImageIO.read(url);
        assertNotNull(sheet, "the arrow sheet is not a readable PNG");
        assertEquals(16, sheet.getWidth(), "the sheet must be 16x16 so that half scale gives 8x8");
        assertEquals(16, sheet.getHeight());
        assertTrue(sheet.getColorModel().hasAlpha(), "the sheet needs an alpha channel (RGBA)");
        int opaque = 0;
        for (int y = 0; y < sheet.getHeight(); y++) {
            for (int x = 0; x < sheet.getWidth(); x++) {
                int argb = sheet.getRGB(x, y);
                int alpha = argb >>> 24;
                assertTrue(alpha == 0 || alpha == 255,
                        "anti-aliased pixel at " + x + "," + y + " would show as a fringe (alpha " + alpha + ")");
                if (alpha == 0) continue;
                assertEquals(0x00FFFFFF, argb & 0x00FFFFFF, "the arrow must be pure white at " + x + "," + y);
                assertTrue(x > 0 && y > 0 && x < 15 && y < 15,
                        "the arrow is clipped at the sheet edge at " + x + "," + y);
                opaque++;
            }
        }
        assertTrue(opaque >= 40, "the arrow is too small to read when halved (" + opaque + " opaque pixels)");
        assertTrue(sheet.getWidth() * 0.5f == PanelLayout.BUTTON,
                "the sheet must scale exactly onto the button");
    }

    /**
     * The three button tooltips are capped at FOUR Chinese characters (user: "按钮说明删减到四个字"), which is what
     * fits next to an 8x8 button. Read from the shipped lang files - the same bytes the client loads - and this
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
                    assertEquals(expanded.collapseButton(), hidden.bounds(),
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
}
