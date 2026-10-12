package dev.scathiard.feedmepackages.domain;

/** Desired controls retained separately when a server capacity reduction bounds their effective values. */
public record CellControls(int minimum, int maximum, CellSettings settings) {
    public CellControls {
        if (minimum < -1 || maximum < -1 || minimum >= 0 && maximum >= 0 && minimum > maximum)
            throw new IllegalArgumentException("Invalid retained controls");
        java.util.Objects.requireNonNull(settings);
    }
    public static CellControls of(Cell<?> cell) { return new CellControls(cell.minimum(), cell.maximum(), cell.settings()); }
    public CellControls bounded(CacheCapacity capacity, MaterialVariant filter) {
        int units = capacity.items(filter);
        return new CellControls(Math.min(minimum, capacity.groups()), Math.min(maximum, capacity.groups()),
                new CellSettings(Math.min(settings.lower(), units), Math.min(settings.upper(), units), settings.batch(), settings.pendingLimit()));
    }
    public void validate(MaterialVariant filter) {
        if (filter == null || minimum > CacheCapacity.MAX_GROUPS || maximum > CacheCapacity.MAX_GROUPS)
            throw new IllegalArgumentException("Invalid retained control source");
        settings.validate(filter, new CacheCapacity(1, CacheCapacity.MAX_GROUPS).items(filter));
    }
    public <K extends MaterialVariant> Cell<K> apply(Cell<K> cell) {
        return new Cell<>(cell.filter(), cell.amount(), minimum, maximum, cell.filterRevision(), settings);
    }
}
