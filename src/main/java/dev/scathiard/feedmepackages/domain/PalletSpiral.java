package dev.scathiard.feedmepackages.domain;

/** Continuous angular selection. Pointer radius never chooses an item or resets a completed turn. */
public final class PalletSpiral {
    public static final int PER_TURN = 16;
    public static final double STEP = Math.PI * 2 / PER_TURN;
    private int size;
    private double position, phase, lastX, lastY;
    private boolean tracking;

    public PalletSpiral(int size, int selected) { reset(size, selected); }
    public void reset(int size, int selected) {
        if (size < 0) throw new IllegalArgumentException("Negative item count");
        this.size = size;
        position = Math.clamp(selected, 0, Math.max(0, size - 1));
        phase = -position * STEP;
        tracking = false;
    }
    public double position() { return position; }
    public double phase() { return phase; }
    public int selected() { return tracking && size > 0 ? (int) Math.floorMod(Math.round(position), (long) size) : -1; }
    public void cancel() { tracking = false; }

    public void move(double x, double y, double deadRadius) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(deadRadius) || deadRadius < 0)
            throw new IllegalArgumentException("Invalid spiral pointer");
        if (size == 0 || Math.hypot(x, y) <= deadRadius) {
            tracking = false;
            return;
        }
        double angle = Math.atan2(y, x);
        if (tracking && !crossesCenter(x, y, deadRadius)) {
            double delta = angle - Math.atan2(lastY, lastX);
            delta = Math.atan2(Math.sin(delta), Math.cos(delta));
            position = wrap(position + delta / STEP, size);
        }
        // Keep the cyclic phase bounded; crossing the list seam preserves pointer angle and reversal.
        phase = angle - position * STEP;
        lastX = x;
        lastY = y;
        tracking = true;
    }

    static double wrap(double value, int size) {
        if (size == 0) return 0;
        double result = value % size;
        double wrapped = result < 0 ? result + size : result;
        // A tiny negative remainder can round back up to size when added to a large modulus.
        return wrapped >= size ? 0 : wrapped;
    }

    private boolean crossesCenter(double x, double y, double radius) {
        double dx = x - lastX, dy = y - lastY, length = dx * dx + dy * dy;
        if (length == 0) return false;
        double t = Math.clamp(-(lastX * dx + lastY * dy) / length, 0, 1);
        return Math.hypot(lastX + t * dx, lastY + t * dy) <= radius;
    }
}
