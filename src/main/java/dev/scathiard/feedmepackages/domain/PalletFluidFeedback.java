package dev.scathiard.feedmepackages.domain;

/** Local r018 press/hold/return feedback. Receipts trigger it; no particles, stock or use cooldown. */
public final class PalletFluidFeedback {
    public static final double DURATION = .24;
    private double since = Double.NEGATIVE_INFINITY, carry;
    private boolean returning;

    public void success(double now) {
        carry = pose(now).compression();
        since = now;
        returning = false;
    }
    public void cancel(double now) {
        carry = pose(now).compression();
        since = now;
        returning = true;
    }
    public void reset() { since = Double.NEGATIVE_INFINITY; carry = 0; returning = false; }
    public Pose pose(double now) {
        double t = Math.max(0, now - since);
        if (!Double.isFinite(t) || t >= (returning ? .06 : DURATION)) return Pose.REST;
        double press = Math.clamp(t / .08, 0, 1);
        press = 2 * press - press * press;
        double compression = returning ? carry * (1 - smooth(t / .06))
                : t < .08 ? carry + (1 - carry) * press
                : t <= .208 ? 1 : Math.clamp((DURATION - t) / .032, 0, 1);
        return new Pose(compression);
    }
    public record Pose(double compression) {
        public static final Pose REST = new Pose(0);
        public double middle() { return .375 * compression; }
        public double tip() { return .75 * compression; }
        public boolean active() { return compression > 0; }
    }
    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
