package dev.scathiard.feedmepackages.compat.biotech;

import java.util.UUID;

/** FMP ownership travels with the native flight, including after its cargo has been delivered. */
public interface BiotechTaskLink {
    UUID fmp$target();
    UUID fmp$source();
    UUID fmp$owner();
    void fmp$target(UUID target);
    void fmp$source(UUID source);
    void fmp$owner(UUID owner);
}
