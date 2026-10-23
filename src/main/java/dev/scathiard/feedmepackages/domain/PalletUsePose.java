package dev.scathiard.feedmepackages.domain;

/** Local cargo-only pose, sampled from native use ticks; no independent animation clock. */
public record PalletUsePose(double lift, double turn) {
    public static final PalletUsePose REST = new PalletUsePose(0, 0);
    public static PalletUsePose at(double elapsedTicks) {
        if (!Double.isFinite(elapsedTicks) || elapsedTicks <= 0) return REST;
        double bounce = Math.sin(Math.PI * (elapsedTicks % 16) / 16);
        return new PalletUsePose(1.5 * bounce * bounce, (elapsedTicks % 32) * 360 / 32);
    }
}
