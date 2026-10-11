package dev.scathiard.feedmepackages.domain;

import java.util.Objects;

/** A filter is a template, never stock. -1 disables a threshold.
 * minimum/maximum retain the old saved group fields; settings is the current, item-count authority. */
public record Cell<K extends MaterialVariant>(K filter, int amount, int minimum, int maximum, long filterRevision,
                                             CellSettings settings) {
    public Cell(K filter, int amount, int minimum, int maximum, long filterRevision) {
        this(filter, amount, minimum, maximum, filterRevision, CellSettings.fromGroups(filter, minimum, maximum));
    }
    public Cell {
        Objects.requireNonNull(settings);
        if (amount < 0 || minimum < -1 || maximum < -1 || filterRevision < 0)
            throw new IllegalArgumentException("Negative cell state");
        if (filter == null && (amount != 0 || minimum != -1 || maximum != -1))
            throw new IllegalArgumentException("Unfiltered cell holds stock or thresholds");
        if (minimum >= 0 && maximum >= 0 && minimum > maximum)
            throw new IllegalArgumentException("Minimum exceeds maximum");
    }

    public static <K extends MaterialVariant> Cell<K> empty() { return new Cell<>(null, 0, -1, -1, 0); }

    public Cell<K> withAmount(int next) {
        return new Cell<>(filter, next, minimum, maximum, filterRevision, settings);
    }

    public Cell<K> configure(K next) {
        if (Objects.equals(filter, next)) return this;
        if (amount != 0) throw new IllegalStateException("Empty the cell before changing its filter");
        return new Cell<>(next, 0, -1, -1, Math.incrementExact(filterRevision));
    }

    public Cell<K> thresholds(int min, int max) {
        return new Cell<>(filter, amount, min, max, filterRevision);
    }

    public Cell<K> settings(CellSettings next) {
        return new Cell<>(filter, amount, minimum, maximum, filterRevision, next);
    }

    /** Shortage in items: minimum groups × stack size − current amount. */
    public int shortage() { return settings.lower() < 0 ? 0 : Math.max(0, settings.lower() - amount); }
    public int surplus() { return settings.upper() < 0 ? 0 : Math.max(0, amount - settings.upper()); }
}
