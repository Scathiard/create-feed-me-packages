package dev.scathiard.feedmepackages.domain;

/** Bounded server rights. The same bound must be safe for both items and 8000 mB fluid groups. */
public record CacheCapacity(int slots, int groups) {
    /** Maximum base size in server configuration. Paid links add to that value. */
    public static final int MAX_SLOTS = 128;
    public static final int MAX_GROUPS = Integer.MAX_VALUE / 8000;
    public static final int SLOTS_PER_LINK = 5;

    public static CacheCapacity configured(int slots, int groups) {
        if (slots > MAX_SLOTS) throw new IllegalArgumentException("Base slot configuration exceeds " + MAX_SLOTS);
        return new CacheCapacity(slots, groups);
    }

    public CacheCapacity expanded(int links) {
        long slotsAfter = (long) slots + (long) links * SLOTS_PER_LINK;
        if (links < 0 || slotsAfter > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Unrepresentable expanded capacity");
        return new CacheCapacity((int) slotsAfter, groups);
    }

    public CacheCapacity {
        if (slots < 1 || groups < 1 || groups > MAX_GROUPS)
            throw new IllegalArgumentException("Unsupported cache capacity");
    }
    public static CacheCapacity defaults(int level) {
        var value = CacheLevel.of(level); return configured(value.slots(), value.groupCapacity());
    }
    public int items(MaterialVariant variant) { return Math.multiplyExact(groups, variant.stackSize()); }
}
