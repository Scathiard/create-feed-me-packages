package dev.scathiard.feedmepackages.domain;

/** Material units (items or mB). pendingLimit is retained for old saves only. */
public record CellSettings(int lower, int upper, int batch, int pendingLimit, int returnBatch) {
    public CellSettings(int lower, int upper, int batch, int pendingLimit) {
        this(lower, upper, batch, pendingLimit, 1);
    }
    public CellSettings {
        if (lower < -1 || upper < -1 || batch < 1 || returnBatch < 1 || pendingLimit < -1 || pendingLimit == 0
                || lower >= 0 && upper >= 0 && lower > upper)
            throw new IllegalArgumentException("Invalid cell controls");
    }

    public static CellSettings fromGroups(MaterialVariant filter, int lower, int upper) {
        int size = filter == null ? 1 : filter.stackSize();
        return new CellSettings(lower < 0 ? -1 : Math.multiplyExact(lower, size),
                upper < 0 ? -1 : Math.multiplyExact(upper, size), 1, -1);
    }

    public void validate(MaterialVariant filter, int capacity) {
        if (filter == null) {
            if (!equals(new CellSettings(-1, -1, 1, -1)))
                throw new IllegalArgumentException("Unfiltered controls");
            return;
        }
        int nineStacks = Math.multiplyExact(9, filter.stackSize());
        if (lower > capacity || upper > capacity || batch > filter.batchLimit() || returnBatch > filter.batchLimit() || pendingLimit > nineStacks)
            throw new IllegalArgumentException("Cell controls outside capacity");
    }

    /** Already-promised stock first covers the shortage; a batch never creates demand on its own. */
    public int requestable(int amount, long pending) {
        if (amount < 0 || pending < 0) throw new IllegalArgumentException("Negative stock");
        long shortage = lower < 0 ? 0 : Math.max(0L, (long) lower - amount - pending);
        if (shortage == 0) return 0;
        long target = Math.max(shortage, batch);
        // An old conflicting setup remains readable, but cannot send a batch straight into returns.
        if (upper >= 0 && (long) amount + pending + target >= (long) upper + returnBatch) return 0;
        return Math.toIntExact(target);
    }

    /** Exact-shortage replenishment cannot cross the return line, regardless of its batch minimum. */
    public static int minimumStockGap(int batch, int returnBatch) {
        return batch == 1 ? 0 : Math.max(batch, returnBatch);
    }

    /** Larger replenishment batches retain the configured stock-interval protection. */
    public boolean conflicting() {
        return lower > 0 && upper >= 0 && (long) upper - lower < minimumStockGap(batch, returnBatch);
    }

    /** A carrier drawn from this very cell must not pull the retained stock below the return line. */
    public int returnable(int amount, int available, int packageCapacity, int carrierCost) {
        if (amount < 0 || available < 0 || packageCapacity < 0 || carrierCost < 0)
            throw new IllegalArgumentException("Negative return input");
        if (upper < 0) return 0;
        int surplus = (int)Math.max(0L, (long) amount - upper - carrierCost);
        int offered = Math.min(surplus, Math.min(available, packageCapacity));
        return offered < returnBatch ? 0 : offered;
    }
}
