package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.*;
import java.util.*;

/** Historical-world fixture only. This is never compiled into a production mod. */
final class LegacyOwnership {
    private final CacheLedger ledger;
    private Map<UUID, CacheRecord> caches;
    private Map<UUID, UUID> personal;
    private Map<UUID, ParcelRedirect> redirects;
    private static final int MAX_REDIRECT_VARIANTS = CacheLedger.MAX_REDIRECT_VARIANTS;
    static LegacyOwnership on(CacheLedger ledger) { return new LegacyOwnership(ledger); }
    private LegacyOwnership(CacheLedger ledger) {
        this.ledger=ledger; caches=read("caches"); personal=read("personal"); redirects=read("redirects");
    }
    @SuppressWarnings("unchecked")
    private <T> T read(String name) {
        try { var field=CacheLedger.class.getDeclaredField(name);field.setAccessible(true);return (T)field.get(ledger); }
        catch(ReflectiveOperationException e){throw new IllegalStateException("Legacy fixture field mismatch",e);}
    }
    private void publish() {
        write("caches",caches);write("personal",personal);write("redirects",redirects);ledger.setDirty();
    }
    private void write(String name,Object value) {
        try {var field=CacheLedger.class.getDeclaredField(name);field.setAccessible(true);field.set(ledger,value);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Legacy fixture field mismatch",e);}
    }
    private void writable() {if(!ledger.problem().isEmpty())throw new IllegalStateException(ledger.problem());}
    /** Immutable proposed maps; neither construction nor inspection publishes any state. */
    public final class Mutation {
        private final Map<UUID, CacheRecord> beforeCaches = caches;
        private final Map<UUID, UUID> beforePersonal = personal;
        private final Map<UUID, ParcelRedirect> beforeRedirects = redirects;
        private final Map<UUID, CacheRecord> afterCaches;
        private final Map<UUID, UUID> afterPersonal;
        private final Map<UUID, ParcelRedirect> afterRedirects;
        private final CacheRecord result;
        private boolean committed;
        private Mutation(Map<UUID, CacheRecord> records, Map<UUID, UUID> owners, Map<UUID, ParcelRedirect> routes, CacheRecord result) {
            afterCaches = Map.copyOf(records); afterPersonal = Map.copyOf(owners); afterRedirects = Map.copyOf(routes);
            this.result = result;
        }
        public CacheRecord result() { return result; }
        public boolean current() {
            return !committed && beforeCaches == read("caches") && beforePersonal == read("personal") && beforeRedirects == read("redirects");
        }
        /** Final caller must also prove actual input/output ownership; there are no callbacks here. */
        public void commit() {
            writable(); if (!current()) throw new IllegalStateException("Stale or repeated world transaction");
            caches = afterCaches; personal = afterPersonal; redirects = afterRedirects;
            committed = true; publish();
        }
    }

    private void sourceCurrent(CacheRecord source) {
        var actual = caches.get(source.state().id());
        if (actual == source) return;
        if (actual != null || redirects.containsKey(source.state().id()) || source.owner() != null || source.anyResidual()
                || !source.state().equals(CacheState.<ItemVariantKey>empty(source.state().id())))
            throw new IllegalStateException("Stale or unregistered nonempty source");
    }

    public Mutation prepareOwnership(CacheRecord source, CacheRecord target, CacheRecord result, Map<ParcelRoute<ItemVariantKey>, Long> routes) {
        writable(); sourceCurrent(source);
        if (source.owner() != null || result.owner() == null || !Objects.equals(personal.get(result.owner()), target == null ? null : target.state().id())
                || (target != null && (caches.get(target.state().id()) != target || !result.owner().equals(target.owner())))
                || !result.state().id().equals(target == null ? source.state().id() : target.state().id())
                || result.state().revision() != Math.incrementExact(target == null ? source.state().revision() : target.state().revision())
                || result.state().level() != Math.max(source.state().level(), target == null ? 1 : target.state().level()))
            throw new IllegalStateException("Invalid ownership proposal");
        var records = new HashMap<>(caches); records.remove(source.state().id()); records.put(result.state().id(), result);
        var owners = new HashMap<>(personal); owners.put(result.owner(), result.state().id());
        var nextRoutes = new HashMap<>(redirects);
        // A revoked filter can never accept its old parcel again, so its routing metadata is no longer needed.
        for (var entry : redirects.entrySet()) if (entry.getValue().target().equals(result.state().id())) {
            var live = new HashMap<ParcelRoute<ItemVariantKey>, Long>();
            entry.getValue().variants().forEach((old, revision) -> {
                int slot = result.state().find(old.variant());
                if (slot >= 0 && result.state().cells().get(slot).filterRevision() == revision) live.put(old, revision);
            });
            if (live.isEmpty()) nextRoutes.remove(entry.getKey());
            else nextRoutes.put(entry.getKey(), new ParcelRedirect(result.state().id(), live));
        }
        if (!source.state().id().equals(result.state().id()) && !routes.isEmpty()) {
            for (var entry : routes.entrySet()) {
                int sourceSlot = source.state().find(entry.getKey().variant()), targetSlot = result.state().find(entry.getKey().variant());
                if (sourceSlot < 0 || targetSlot < 0 || source.state().cells().get(sourceSlot).filterRevision() != entry.getKey().revision()
                        || result.state().cells().get(targetSlot).filterRevision() != entry.getValue()) throw new IllegalStateException("Invalid manufacturing parcel route");
            }
            nextRoutes.put(source.state().id(), new ParcelRedirect(result.state().id(), routes));
        }
        long count = nextRoutes.values().stream().filter(route -> route.target().equals(result.state().id())).mapToLong(route -> route.variants().size()).sum();
        if (count > MAX_REDIRECT_VARIANTS) throw new IllegalStateException("Personal parcel route table is full");
        return new Mutation(records, owners, nextRoutes, result);
    }

    public UUID personalOrCreate(UUID player) {
        writable(); UUID current = personal.get(player); if (current != null) return current;
        UUID id = UUID.randomUUID();
        var nextCaches = new HashMap<>(caches); nextCaches.put(id, new CacheRecord(CacheState.empty(id), player, Map.of()));
        var nextPersonal = new HashMap<>(personal); nextPersonal.put(player, id);
        caches = Map.copyOf(nextCaches); personal = Map.copyOf(nextPersonal); publish(); return id;
    }

}
