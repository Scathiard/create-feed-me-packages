package dev.scathiard.feedmepackages.domain;

/** Local r014 valve/jet feedback. Success receipts trigger it; it never gates use or owns fluid. */
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
        double compression = returning ? carry * (1 - smooth(t / .06))
                : t <= .06 ? smooth(t / .06) + carry * (1 - smooth(t / .06))
                : 1 - smooth((t - .06) / .18);
        double jet = returning ? 0 : smooth((t - .02) / .03) * (1 - smooth((t - .07) / .03));
        return new Pose(compression, jet, returning ? -1 : t);
    }
    public record Pose(double compression, double jet, double elapsed) {
        public static final Pose REST = new Pose(0, 0, -1);
        public double middle() { return .18 * compression; }
        public double tip() { return .45 * compression; }
        public double dropAge(int index) { return Math.max(0, elapsed - (.04 + index * .008)); }
        public double dropScale(int index) {
            if (elapsed < .04 + index * .008) return 0;
            double age = dropAge(index);
            return smooth(age / .012) * (1 - smooth((age - .065) / .035));
        }
        public boolean emitting() { return jet > 0 || dropScale(0) > 0 || dropScale(1) > 0 || dropScale(2) > 0; }
    }
    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
