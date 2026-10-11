package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Both views page the same item order. Spiral targets retain a usable size as the total grows. */
public final class PalletLayout {
    private PalletLayout() {}
    public record Tile(int index, int x, int y) {
        public boolean contains(double px, double py) { return px >= x && px < x + 24 && py >= y && py < y + 24; }
    }
    public record View(List<Tile> tiles, int first, int step, int maximum) {}
    public static View view(boolean spiral, int size, int offset, int width, int height) {
        if (size < 0 || width < 0 || height < 0) throw new IllegalArgumentException("Invalid pallet viewport");
        var tiles = new ArrayList<Tile>();
        if (spiral) {
            int radius = Math.max(26, Math.min(width / 2 - 20, (height - 90) / 2 - 12));
            int visible = Math.max(1, Math.min(12, (radius - 26) / 6 + 1));
            int maximum = Math.max(0, size - visible), first = Math.clamp(offset, 0, maximum);
            for (int i = 0; i < Math.min(visible, size - first); i++) {
                double angle = Math.toRadians(-90 + 60 * i); int r = 26 + 6 * i;
                tiles.add(new Tile(first + i, width / 2 + (int) Math.round(Math.cos(angle) * r) - 12,
                        height / 2 + 8 + (int) Math.round(Math.sin(angle) * r) - 12));
            }
            return new View(List.copyOf(tiles), first, 1, maximum);
        }
        int columns = Math.max(1, Math.min(8, (width - 32) / 26)), rows = Math.max(1, Math.min(6, (height - 96) / 26));
        int maximum = Math.max(0, (int) Math.ceil(size / (double) columns) - rows) * columns;
        int first = Math.clamp(offset / columns * columns, 0, maximum), x = (width - columns * 26) / 2, y = 60;
        for (int i = 0; i < Math.min(columns * rows, size - first); i++) tiles.add(new Tile(first + i, x + i % columns * 26, y + i / columns * 26));
        return new View(List.copyOf(tiles), first, columns, maximum);
    }
}
