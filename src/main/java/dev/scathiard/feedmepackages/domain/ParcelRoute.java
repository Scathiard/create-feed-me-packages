package dev.scathiard.feedmepackages.domain;

import java.util.Objects;

/** Exact legacy parcel identity; this preserves old delivery routes, not shared-cache access. */
public record ParcelRoute<K extends MaterialVariant>(K variant, long revision) {
    public ParcelRoute { Objects.requireNonNull(variant);if(revision<0)throw new IllegalArgumentException("Negative route revision"); }
}
