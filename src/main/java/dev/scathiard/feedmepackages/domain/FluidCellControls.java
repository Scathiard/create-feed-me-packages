package dev.scathiard.feedmepackages.domain;

/** Fluid amounts stay exact mB; the legacy 1 mB default does not force a batch. */
public final class FluidCellControls {
    public static final int BUCKET = 1000, GROUP = 8 * BUCKET;
    private FluidCellControls() {}

    public static int maximum(int packageCapacity) {
        return Math.max(1, packageCapacity / BUCKET * BUCKET);
    }
    public static boolean batch(int amount) { return amount == 1 || amount >= BUCKET && amount % BUCKET == 0; }
    private static boolean threshold(int amount) { return amount == -1 || amount >= 0 && amount % BUCKET == 0; }
    /** A reduced transport limit never rewrites old controls or prevents lowering them one at a time. */
    public static boolean accepts(CellSettings next, CellSettings previous, int packageCapacity) {
        int limit = maximum(packageCapacity);
        return threshold(next.lower()) && threshold(next.upper()) && batch(next.batch()) && batch(next.returnBatch())
                && next.pendingLimit() == -1 && !next.conflicting()
                && (next.batch() == previous.batch() || next.batch() <= limit)
                && (next.returnBatch() == previous.returnBatch() || next.returnBatch() <= limit);
    }
}
