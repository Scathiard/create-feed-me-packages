package dev.scathiard.feedmepackages.domain;

/** Art r005 transforms in 16-unit model coordinates. Display only; never gates placement or spends stock. */
public record PalletFeedPose(double tray, double jaws, double gate, double cargoZ, boolean cargo) {
    public static final double DURATION = .35;
    public static PalletFeedPose interrupted(PalletFeedPose from, double seconds, boolean hasNext) {
        if (from == null || seconds < 0 || seconds >= .03) return at(seconds,hasNext);
        double t=smooth(seconds/.03);
        return new PalletFeedPose(from.tray*(1-t),from.jaws+(.9-from.jaws)*t,
                from.gate+(-90-from.gate)*t,3.1,false);
    }
    public static PalletFeedPose at(double seconds, boolean hasNext) {
        if (seconds < 0 || seconds >= DURATION) return new PalletFeedPose(0, 0, 0, -14, hasNext);
        double open = smooth(seconds / .03), grip = smooth((seconds - .18) / .03);
        double close = smooth((seconds - .32) / .03);
        double feed = smooth((seconds - .03) / .15), retract = smooth((seconds - .21) / .11);
        return new PalletFeedPose(-17.1 * feed * (1 - retract), .9 * open * (1 - grip),
                -90 * open * (1 - close), 3.1 - 17.1 * feed, hasNext && seconds >= .03);
    }
    private static double smooth(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
}
