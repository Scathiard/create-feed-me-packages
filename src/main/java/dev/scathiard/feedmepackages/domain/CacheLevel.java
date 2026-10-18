package dev.scathiard.feedmepackages.domain;

/** Permanent structure rights for the five cache levels.
 *  capacity = item capacity for 64-stack items; groupCapacity = capacity / 64, uniform across all items.
 *  Per-cell item capacity = groupCapacity × item.stackSize(). */
public record CacheLevel(int level, int slots, int capacity) {
    public static CacheLevel of(int level) {
        return switch (level) {
            case 1 -> new CacheLevel(1, 9, 128);
            case 2 -> new CacheLevel(2, 16, 256);
            case 3 -> new CacheLevel(3, 24, 512);
            case 4 -> new CacheLevel(4, 30, 1024);
            case 5 -> new CacheLevel(5, 36, 2048);
            default -> throw new IllegalArgumentException("Invalid cache level: " + level);
        };
    }

    /** Uniform group capacity: the maximum number of full stacks any item can occupy, regardless of stack size. */
    public int groupCapacity() { return capacity / 64; }
}
