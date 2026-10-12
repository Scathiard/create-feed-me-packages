package dev.scathiard.feedmepackages.domain;

/** Continuous angular selection with no inactive radius. Only an undefined center direction reanchors. */
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
    public int selected() { return size > 0 ? (int) Math.floorMod(Math.round(position), (long) size) : -1; }

    public void move(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Invalid spiral pointer");
        if (size == 0 || x == 0 && y == 0) {
            tracking = false;
            return; // There is no direction at the exact origin, but the current choice remains valid.
        }
        double angle = Math.atan2(y, x);
        if (tracking && !crossesCenter(x, y)) {
            double delta = angle - Math.atan2(lastY, lastX);
            delta = Math.atan2(Math.sin(delta), Math.cos(delta));
            position = wrap(position + delta / STEP, size);
        }
        phase = angle - position * STEP;
        lastX = x;
        lastY = y;
        tracking = true;
    }

    static double wrap(double value, int size) {
        if (size == 0) return 0;
        double result = value % size;
        double wrapped = result < 0 ? result + size : result;
        return wrapped >= size ? 0 : wrapped;
    }

    private boolean crossesCenter(double x, double y) {
        double before = Math.hypot(lastX, lastY), after = Math.hypot(x, y);
        double ax = lastX / before, ay = lastY / before, bx = x / after, by = y / after;
        // Only opposite collinear samples cross the origin. This tolerance is floating-point precision,
        // not a spatial dead zone: arbitrarily small circular gestures still advance normally.
        return ax * bx + ay * by < 0 && Math.abs(ax * by - ay * bx) <= 8 * Math.ulp(1.0);
    }
}
