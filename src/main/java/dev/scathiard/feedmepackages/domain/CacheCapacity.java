package dev.scathiard.feedmepackages.domain;

/** Bounded server rights. The same bound must be safe for both items and 8000 mB fluid groups. */
public record CacheCapacity(int slots, int groups) {
    /** 0.3.0 bounded every cache at 128 cells; the dragon-head expansion (R-018) exceeds that ceiling on purpose. */
    public static final int MAX_SLOTS = 512;
    /** Cells one dragon-head expansion adds; also the unit the level upgrade has to carry over. */
    public static final int EXPANSION_SLOTS = 5;
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
