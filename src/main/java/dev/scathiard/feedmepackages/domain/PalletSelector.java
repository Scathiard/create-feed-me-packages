package dev.scathiard.feedmepackages.domain;

/** r006 detents: input chooses an item immediately; the damped display never delays confirmation. */
public final class PalletSelector {
    public static final double THRESHOLD = .58;
    private int size;
    private long anchor;
    private double raw, position, velocity;
    public PalletSelector(int size, int selected) { reset(size, selected); }
    public void reset(int count, int selected) {
        size = Math.max(0, count);
        anchor = size == 0 ? 0 : Math.floorMod(selected, size);
        raw = position = anchor; velocity = 0;
    }
    public void drag(double cells) {
        if (size == 0 || !Double.isFinite(cells)) return;
        raw += Math.clamp(cells, -10000, 10000);
        if (raw - anchor > THRESHOLD) anchor = (long) Math.ceil(raw - THRESHOLD);
        else if (raw - anchor < -THRESHOLD) anchor = (long) Math.floor(raw + THRESHOLD);
    }
    public void step(int direction) {
        if (size > 0) { anchor += Integer.signum(direction); raw = anchor; }
    }
    public void advance(double seconds) {
        double t = Math.clamp(seconds, 0, .08), offset = position - anchor, term = velocity + 60 * offset;
        double decay = Math.exp(-60 * t);
        position = anchor + (offset + term * t) * decay;
        velocity = (velocity - 60 * term * t) * decay;
        if (Math.abs(position - anchor) < .001 && Math.abs(velocity) < .025) { position = anchor; velocity = 0; }
    }
    public int selected() { return size == 0 ? -1 : (int) Math.floorMod(anchor, (long) size); }
    public long anchor() { return anchor; }
    public double position() { return position; }
}
