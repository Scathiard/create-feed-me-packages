package dev.scathiard.feedmepackages.domain;

/** Transient authoritative cause, not a persisted counter or a second inventory. */
public record LogisticsSignal(long sequence, Kind kind) {
    public enum Kind { NONE, REQUEST, ARRIVAL, DEPARTURE }
    public static final LogisticsSignal NONE = new LogisticsSignal(0, Kind.NONE);
    public LogisticsSignal {
        if (sequence < 0 || kind == null || (sequence == 0) != (kind == Kind.NONE))
            throw new IllegalArgumentException("Invalid logistics signal");
    }
}
