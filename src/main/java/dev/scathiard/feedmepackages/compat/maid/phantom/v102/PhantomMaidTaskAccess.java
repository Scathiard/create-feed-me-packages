package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

/** FMP state belongs to the native task and is saved alongside it, never in a global UUID table. */
public interface PhantomMaidTaskAccess {
    PhantomMaidTaskState fmp$maidState();
}
