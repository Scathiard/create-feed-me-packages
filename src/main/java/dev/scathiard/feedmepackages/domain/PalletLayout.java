package dev.scathiard.feedmepackages.domain;

import java.util.ArrayList;
import java.util.List;

/** Grid pagination and a continuous spiral use the same stable item indices. */
public final class PalletLayout {
    private PalletLayout() {}
    public record Tile(int index, int x, int y) {
        public boolean contains(double px, double py) { return px >= x && px < x + 24 && py >= y && py < y + 24; }
    }
    public record View(List<Tile> tiles, int first, int step, int maximum) {}
    public record SpiralTile(int index, double x, double y, double size, float opacity, boolean current) {}
    public record SpiralView(List<SpiralTile> tiles, double centerX, double centerY, double deadRadius) {}

    public static View grid(int size, int offset, int width, int height) {
        validate(size, width, height);
        var tiles = new ArrayList<Tile>();
        int columns = Math.max(1, Math.min(8, (width - 32) / 26)), rows = Math.max(1, Math.min(6, (height - 96) / 26));
        int maximum = Math.max(0, (int) Math.ceil(size / (double) columns) - rows) * columns;
        int first = Math.clamp(offset / columns * columns, 0, maximum), x = (width - columns * 26) / 2, y = 60;
        for (int i = 0; i < Math.min(columns * rows, size - first); i++)
            tiles.add(new Tile(first + i, x + i % columns * 26, y + i / columns * 26));
        return new View(List.copyOf(tiles), first, columns, maximum);
    }

    public static SpiralView spiral(int size, double position, double phase, int width, int height) {
        validate(size, width, height);
        if (!Double.isFinite(position) || !Double.isFinite(phase)) throw new IllegalArgumentException("Invalid spiral phase");
        double cx = width / 2.0, cy = height / 2.0;
        double outer = Math.max(12, Math.min(cx - 16, cy - 34));
        double radius = Math.min(92, outer / 1.85);
        double baseSize = Math.min(24, radius * PalletSpiral.STEP * .86);
        double dead = Math.max(6, radius * .35);
        double pointerAngle = Math.IEEEremainder(phase + position * PalletSpiral.STEP, Math.PI * 2);
        position = PalletSpiral.wrap(position, size);
        var tiles = new ArrayList<SpiralTile>();
        if (size == 0) return new SpiralView(List.of(), cx, cy, dead);
        // A finite window over a cyclic stream: visible copies keep both ends connected while
        // inventory identity remains the original index. Only the nearest occurrence is current.
        long from = (long) Math.floor(position) - PalletSpiral.PER_TURN;
        long to = (long) Math.ceil(position) + PalletSpiral.PER_TURN * 3 / 2;
        for (long i = from; i <= to; i++) {
            double relative = i - position;
            double scale = Math.exp(-relative * Math.log(1.7) / PalletSpiral.PER_TURN);
            double r = radius * scale, side = baseSize * scale;
            double angle = pointerAngle + relative * PalletSpiral.STEP;
            double x = cx + Math.cos(angle) * r, y = cy + Math.sin(angle) * r;
            float opacity = (float) Math.max(0, 1 - Math.abs(relative) /
                    (relative < 0 ? PalletSpiral.PER_TURN : PalletSpiral.PER_TURN * 1.5));
            if (opacity <= .025 || x - side / 2 < 8 || x + side / 2 > width - 8 ||
                    y - side / 2 < 26 || y + side / 2 > height - 26) continue;
            tiles.add(new SpiralTile((int) Math.floorMod(i, (long) size), x, y, side, opacity, i == Math.round(position)));
        }
        return new SpiralView(List.copyOf(tiles), cx, cy, dead);
    }
    private static void validate(int size, int width, int height) {
        if (size < 0 || width < 0 || height < 0) throw new IllegalArgumentException("Invalid pallet viewport");
    }
}
