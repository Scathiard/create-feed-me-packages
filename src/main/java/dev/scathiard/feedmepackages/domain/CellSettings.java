package dev.scathiard.feedmepackages.domain;

/** All current controls use item counts. -1 disables a stock threshold or means unlimited pending. */
public record CellSettings(int lower, int upper, int batch, int pendingLimit) {
    public CellSettings {
        if (lower < -1 || upper < -1 || batch < 1 || pendingLimit < -1 || pendingLimit == 0
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
        if (lower > capacity || upper > capacity || batch > nineStacks || pendingLimit > nineStacks)
            throw new IllegalArgumentException("Cell controls outside capacity");
    }

    /** Already-promised stock first covers the shortage; a batch never creates demand on its own. */
    public int requestable(int amount, long pending) {
        if (amount < 0 || pending < 0) throw new IllegalArgumentException("Negative stock");
        long shortage = lower < 0 ? 0 : Math.max(0L, (long) lower - amount - pending);
        if (shortage == 0) return 0;
        long allowance = pendingLimit < 0 ? Integer.MAX_VALUE : Math.max(0L, pendingLimit - pending);
        long target = Math.min(Math.max(shortage, batch), allowance);
        return target < batch ? 0 : Math.toIntExact(target);
    }
}
