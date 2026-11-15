package dev.scathiard.feedmepackages.domain;

/** Bounded server rights. The same bound must be safe for both items and 8000 mB fluid groups. */
public record CacheCapacity(int slots, int groups) {
    public static final int MAX_SLOTS = 128;
    public static final int MAX_GROUPS = Integer.MAX_VALUE / 8000;
    public CacheCapacity {
        if (slots < 1 || slots > MAX_SLOTS || groups < 1 || groups > MAX_GROUPS)
            throw new IllegalArgumentException("Unsupported cache capacity");
    }
    public static CacheCapacity defaults(int level) {
        var value = CacheLevel.of(level); return new CacheCapacity(value.slots(), value.groupCapacity());
    }
    public int items(MaterialVariant variant) { return Math.multiplyExact(groups, variant.stackSize()); }
}
