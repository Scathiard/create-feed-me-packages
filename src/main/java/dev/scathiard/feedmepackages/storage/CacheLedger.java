package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

/** One world authority. Corrupt data produces a locked ledger, never an empty replacement. */
public final class CacheLedger extends SavedData {
    public static final String NAME = "create_feed_me_packages_ledger";
    public static final int SCHEMA = 31002; // Adds configured capacity provenance; all previous field units are unchanged.
    public static final int MAX_REDIRECT_VARIANTS = 4096;
    private static final Factory<CacheLedger> FACTORY = new Factory<>(CacheLedger::new, CacheLedger::load);
    private Map<UUID, CacheRecord> caches = Map.of();
    private Map<UUID, UUID> personal = Map.of();
    private Map<UUID, Boolean> cacheFirst = Map.of();
    private Map<UUID, ParcelRedirect> redirects = Map.of();
    private Map<UUID, String> returnAddresses = Map.of();
    private Map<UUID, ReturnEscrow> returnEscrows = Map.of();
    private Map<UUID, Retirement> retirements = Map.of();
    private final Set<UUID> interruptedRetirements = new HashSet<>();
    /** The owner is historical provenance only after completion; the cache itself is never copied. */
    public record Retirement(UUID owner, UUID cache, UUID receipt, boolean completed) {
        public Retirement { Objects.requireNonNull(owner); Objects.requireNonNull(cache); Objects.requireNonNull(receipt); }
    }
    public Retirement retirement(UUID owner) { return retirements.get(owner); }
    public boolean ordinaryEntry(UUID cache, UUID receipt) {
        if (interruptedRetirements.contains(cache)) return false;
        var record = caches.get(cache); if (record == null || record.owner() != null) return false;
        var transfer = retirements.values().stream().filter(r -> r.cache().equals(cache)).findFirst();
        return transfer.isPresent() ? transfer.get().completed() && transfer.get().receipt().equals(receipt) : receipt == null;
    }
    public Retirement beginRetirement(UUID owner) {
        writable();
        var existing = retirements.get(owner); if (existing != null) return existing;
        UUID cache = personal.get(owner); if (cache == null) return null;
        var record = caches.get(cache);
        if (record == null || !owner.equals(record.owner())) throw new IllegalStateException("Invalid legacy owner");
        var result = new Retirement(owner, cache, UUID.randomUUID(), false);
        var next = new HashMap<>(retirements); next.put(owner, result); retirements = Map.copyOf(next); setDirty();
        return result;
    }
    /** A missing item is not proof of non-delivery. Reissue authority only after revoking the old credential. */
    public Retirement renewPendingReceipt(UUID owner) {
        writable(); var old = retirements.get(owner);
        if (old == null || old.completed()) throw new IllegalStateException("No pending retirement");
        var next = new HashMap<>(retirements); var renewed = new Retirement(owner, old.cache(), UUID.randomUUID(), false);
        next.put(owner, renewed); retirements = Map.copyOf(next); setDirty(); return renewed;
    }
    public void interruptRetirement(UUID owner) {
        var r = retirements.get(owner); if (r != null) interruptedRetirements.add(r.cache());
    }
    public void resumeRetirement(UUID owner) {
        var r = retirements.get(owner); if (r != null) interruptedRetirements.remove(r.cache());
    }
    /** Called only after the replacement pendant and its receipt have been durably saved with the player. */
    public void completeRetirement(UUID owner, UUID receipt) {
        writable(); var transfer = retirements.get(owner);
        if (transfer == null || !transfer.receipt().equals(receipt)) throw new IllegalStateException("Invalid retirement receipt");
        if (transfer.completed()) return;
        var source = caches.get(transfer.cache());
        if (source == null || !owner.equals(source.owner()) || !transfer.cache().equals(personal.get(owner)))
            throw new IllegalStateException("Stale retirement");
        var records = new HashMap<>(caches);
        records.put(transfer.cache(), new CacheRecord(source.state(), null, source.residuals(), source.fluids()));
        var owners = new HashMap<>(personal); owners.remove(owner);
        var completed = new HashMap<>(retirements);
        completed.put(owner, new Retirement(owner, transfer.cache(), receipt, true));
        caches = Map.copyOf(records); personal = Map.copyOf(owners); retirements = Map.copyOf(completed); setDirty();
    }
    private String problem = "";
    private CompoundTag preserved;
    private byte[] integrityKey = createIntegrityKey();

    private static byte[] createIntegrityKey() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes;
    }

    /** Internal world data; key material never leaves this class or enters a packet/log. */
    public String authenticate(String material) {
        writable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(integrityKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Required JDK integrity algorithm unavailable", failure);
        }
    }

    public boolean authentic(String material, String signature) {
        if (!problem.isEmpty() || signature.length() != 64) return false;
        return MessageDigest.isEqual(authenticate(material).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }

    public static CacheLedger get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        CacheLedger ledger = storage.get(FACTORY, NAME);
        if (ledger == null) {
            ledger = new CacheLedger();
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat")))
                ledger.problem = "Cache file exists but could not be read; refusing to replace it";
            storage.set(NAME, ledger);
        }
        return ledger;
    }

    public String problem() { return problem; }
    public CacheRecord find(UUID id) { return caches.get(id); }
    public UUID personal(UUID player) { return personal.get(player); }
    // The existing schema field is inert; it cannot override the fixed backpack-first policy.
    public boolean cacheFirst(UUID player) { return false; }
    public boolean isOwner(UUID cacheId, UUID player) {
        var record = caches.get(cacheId);
        return record != null && record.owner() != null && record.owner().equals(player);
    }
    public String returnAddress(UUID cacheId) { return returnAddresses.get(cacheId); }
    public ReturnEscrow returnEscrow(UUID cacheId) { return returnEscrows.get(cacheId); }
    public Collection<ReturnEscrow> unresolvedReturns() { return returnEscrows.values(); }
    private void available(UUID cacheId) {
        if (returnEscrows.containsKey(cacheId)) throw new IllegalStateException("Cache has an unresolved return handoff");
    }
    public void beginReturn(ReturnEscrow escrow, CacheRecord expected, HolderLookup.Provider registries) {
        writable(); available(escrow.handle().cacheId());
        if (escrow.rejected() || caches.get(escrow.handle().cacheId()) != expected)
            throw new IllegalStateException("Stale return preparation");
        escrow.settled(expected, registries); // Prove every debit before calling any native transport code.
        var next = new HashMap<>(returnEscrows); next.put(escrow.handle().cacheId(), escrow);
        returnEscrows = Map.copyOf(next); setDirty();
    }
    private void currentReturn(ReturnEscrow escrow) {
        writable();
        if (returnEscrows.get(escrow.handle().cacheId()) != escrow) throw new IllegalStateException("Stale return completion");
    }
    public void finishReturn(ReturnEscrow escrow, HolderLookup.Provider registries) {
        currentReturn(escrow);
        if (escrow.rejected()) throw new IllegalStateException("Rejected transport cannot settle a fee");
        var result = escrow.settled(caches.get(escrow.handle().cacheId()), registries);
        var records = new HashMap<>(caches); records.put(escrow.handle().cacheId(), result);
        var holds = new HashMap<>(returnEscrows); holds.remove(escrow.handle().cacheId());
        // No external callbacks in this commit. All sources were unavailable for the whole native handoff.
        caches = Map.copyOf(records); returnEscrows = Map.copyOf(holds); setDirty();
    }
    public ReturnEscrow rejectReturn(ReturnEscrow escrow) {
        currentReturn(escrow); var rejected = escrow.asRejected();
        var holds = new HashMap<>(returnEscrows); holds.put(escrow.handle().cacheId(), rejected);
        returnEscrows = Map.copyOf(holds); setDirty(); return rejected;
    }
    /** Caller must first return the real inventory carrier, if any. Uncertain handoffs cannot use this path. */
    public void cancelRejectedReturn(ReturnEscrow escrow) {
        currentReturn(escrow);
        if (!escrow.rejected()) throw new IllegalStateException("Uncertain transport cannot be automatically refunded");
        var holds = new HashMap<>(returnEscrows); holds.remove(escrow.handle().cacheId());
        returnEscrows = Map.copyOf(holds); setDirty();
    }
    public void setReturnAddress(UUID cacheId, String address) {
        writable(); available(cacheId); var next = new HashMap<>(returnAddresses);
        if (address == null) next.remove(cacheId); else next.put(cacheId, address);
        returnAddresses = Map.copyOf(next); setDirty();
    }

    public record ParcelTarget(UUID cacheId, long revision) {}
    /** Caller must authenticate the original parcel before resolving a manufacturing redirect. */
    public ParcelTarget parcelTarget(UUID original, ItemVariantKey variant, long revision) {
        UUID id = original; long currentRevision = revision;
        if (!caches.containsKey(id)) {
            var route = redirects.get(id); if (route == null) return null;
            Long mapped = route.variants().get(new ParcelRoute<>(variant, revision));
            if (mapped == null) return null;
            id = route.target(); currentRevision = mapped;
        }
        var record = caches.get(id); if (record == null) return null;
        int slot = record.state().find(variant);
        return slot >= 0 && record.state().cells().get(slot).filterRevision() == currentRevision ? new ParcelTarget(id, currentRevision) : null;
    }

    /** Fresh unregistered source for manufacturing preview; no ledger entry is created here. */
    public CacheRecord ordinaryForManufacturing(UUID id) {
        writable();
        if (id == null) return new CacheRecord(CacheState.empty(UUID.randomUUID()), null, Map.of());
        available(id);
        var record = caches.get(id);
        if (record == null || record.owner() != null) throw new IllegalStateException("Invalid ordinary manufacturing source");
        return record;
    }

    /** Immutable proposed maps; neither construction nor inspection publishes any state. */
    public final class Mutation {
        private final Map<UUID, ReturnEscrow> beforeReturns = returnEscrows;
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
            return !committed && beforeCaches == caches && beforePersonal == personal && beforeRedirects == redirects && beforeReturns == returnEscrows;
        }
        /** Final caller must also prove actual input/output ownership; there are no callbacks here. */
        public void commit() {
            writable(); if (!current()) throw new IllegalStateException("Stale or repeated world transaction");
            for (UUID id : returnEscrows.keySet()) if (afterCaches.get(id) != caches.get(id)) throw new IllegalStateException("Manufacturing changed held return stock");
            caches = afterCaches; personal = afterPersonal; redirects = afterRedirects;
            committed = true; setDirty();
        }
    }

    private void sourceCurrent(CacheRecord source) {
        available(source.state().id());
        var actual = caches.get(source.state().id());
        if (actual == source) return;
        if (actual != null || redirects.containsKey(source.state().id()) || source.owner() != null || source.anyResidual()
                || !source.state().equals(CacheState.<ItemVariantKey>empty(source.state().id())))
            throw new IllegalStateException("Stale or unregistered nonempty source");
    }

    public Mutation prepareOrdinaryUpgrade(CacheRecord source, int targetLevel) {
        writable(); sourceCurrent(source);
        if (source.owner() != null || source.state().level() + 1 != targetLevel) throw new IllegalArgumentException("Upgrade must advance one level");
        var edit = source.state().edit(); edit.upgrade(dev.scathiard.feedmepackages.FmpConfig.rules().capacity(targetLevel)); var result = source.withState(edit.finish());
        var records = new HashMap<>(caches); records.put(result.state().id(), result);
        return new Mutation(records, personal, redirects, result);
    }

    public void setCacheFirst(UUID player, boolean first) {
        writable(); var next = new HashMap<>(cacheFirst); next.put(player, first); cacheFirst = Map.copyOf(next); setDirty();
    }

    /** Fluid was added after private retirement: no new cross-cache or ownership redirect is manufactured. */
    public ParcelTarget fluidParcelTarget(UUID original, dev.scathiard.feedmepackages.fluid.FluidVariantKey variant, long revision) {
        var record = caches.get(original);
        if (record == null || record.owner() != null) return null;
        int slot = record.fluids().state().find(variant);
        return slot >= 0 && record.fluids().state().cells().get(slot).filterRevision() == revision
                ? new ParcelTarget(original, revision) : null;
    }

    public UUID createOrdinary() {
        writable(); UUID id = UUID.randomUUID();
        var next = new HashMap<>(caches); next.put(id, new CacheRecord(CacheState.empty(id), null, Map.of()));
        caches = Map.copyOf(next); setDirty(); return id;
    }

    /** No callbacks inside commit. The operation's effective access must be re-resolved first. */
    public void replace(CacheHandle handle, long expectedRevision, CacheRecord replacement) {
        writable(); available(handle.cacheId()); CacheRecord old = caches.get(handle.cacheId());
        if (old == null || old.state().revision() != expectedRevision || !replacement.state().id().equals(handle.cacheId())
                || !Objects.equals(old.owner(), replacement.owner()) || (old.owner() != null && !old.owner().equals(handle.playerId())))
            throw new IllegalStateException("Stale or unauthorized cache transaction");
        long nextRevision = Math.incrementExact(expectedRevision);
        if (replacement.state().revision() < expectedRevision || replacement.state().revision() > nextRevision)
            throw new IllegalStateException("Invalid cache revision advance");
        // Residual-only changes belong to the same optimistic transaction boundary as inventory.
        if (replacement.state().revision() == expectedRevision) {
            var state = replacement.state();
            replacement = replacement.withState(state.withRevision(nextRevision));
        }
        var next = new HashMap<>(caches); next.put(handle.cacheId(), replacement); caches = Map.copyOf(next); setDirty();
    }

    private void writable() { if (!problem.isEmpty()) throw new IllegalStateException(problem); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        writable(); tag.putInt("schema", SCHEMA); tag.putByteArray("integrity_key", integrityKey);
        ListTag records = new ListTag();
        for (var entry : caches.entrySet()) {
            CacheRecord record = entry.getValue(); var state = record.state(); CompoundTag row = new CompoundTag();
            row.putUUID("id", state.id()); row.putInt("level", state.level()); row.putLong("revision", state.revision());
            if (record.owner() != null) row.putUUID("owner", record.owner());
            else retirements.values().stream().filter(r -> r.completed() && r.cache().equals(state.id())).findFirst()
                    .ifPresent(r -> row.putUUID("owner", r.owner())); // Keep the old column's original provenance.
            ListTag cells = new ListTag();
            for (Cell<ItemVariantKey> cell : state.cells()) {
                CompoundTag data = new CompoundTag();
                if (cell.filter() != null) data.putString("filter", cell.filter().encoded());
                data.putInt("amount", cell.amount()); data.putInt("min", cell.minimum()); data.putInt("max", cell.maximum()); data.putLong("revision", cell.filterRevision());
                var controls = new CompoundTag(); var settings = cell.settings();
                controls.putInt("lower", settings.lower()); controls.putInt("upper", settings.upper());
                controls.putInt("batch", settings.batch()); controls.putInt("pending_limit", settings.pendingLimit());
                data.put("item_controls", controls);
                cells.add(data);
            }
            row.put(CapacityCodec.KEY, CapacityCodec.write(state));
            row.put("cells", cells); ListTag orders = new ListTag();
            for (var order : state.orders().values()) {
                CompoundTag data = new CompoundTag(); data.putUUID("id", order.id()); data.putUUID("player", order.player());
                data.putString("variant", order.variant().encoded()); data.putLong("revision", order.filterRevision());
                data.putInt("remaining", order.remaining()); data.putBoolean("uncertain", order.uncertain()); orders.add(data);
            }
            row.put("orders", orders);
            if (!record.residuals().isEmpty()) {
                ListTag residualRows = new ListTag();
                for (var resid : record.residuals().entrySet()) {
                    var data = new CompoundTag(); data.putString("variant", resid.getKey().encoded());
                    data.put("package", resid.getValue().save(registries)); residualRows.add(data);
                }
                row.put("residuals", residualRows);
            }
            if (record.fluids().hasData()) row.put("astra_fluids", FluidStorageCodec.write(record.fluids(), registries));
            records.add(row);
        }
        tag.put("caches", records);
        ListTag preferences = new ListTag();
        cacheFirst.forEach((player, first) -> { var data = new CompoundTag(); data.putUUID("player", player); data.putBoolean("cache_first", first); preferences.add(data); });
        tag.put("preferences", preferences);
        ListTag routeRows = new ListTag();
        redirects.forEach((source, route) -> {
            var row = new CompoundTag(); row.putUUID("source", source); row.putUUID("target", route.target()); var variants = new ListTag();
            route.variants().forEach((old, revision) -> {
                var mapping = new CompoundTag(); mapping.putString("variant", old.variant().encoded()); mapping.putLong("before", old.revision()); mapping.putLong("after", revision); variants.add(mapping);
            }); row.put("variants", variants); routeRows.add(row);
        }); tag.put("redirects", routeRows);
        ListTag returnRows = new ListTag();
        returnAddresses.forEach((cacheId, address) -> { var data = new CompoundTag(); data.putUUID("cache", cacheId); data.putString("address", address); returnRows.add(data); });
        tag.put("returnAddresses", returnRows);
        var transfers = new ListTag();
        retirements.values().forEach(r -> {
            var data = new CompoundTag(); data.putUUID("owner", r.owner()); data.putUUID("cache", r.cache());
            data.putUUID("receipt", r.receipt()); data.putBoolean("completed", r.completed()); transfers.add(data);
        });
        tag.put("private_retirements", transfers);
        var holds = new ListTag(); returnEscrows.values().forEach(hold -> holds.add(hold.save(registries)));
        tag.put("return_handoffs", holds);
        return tag;
    }

    public static CacheLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        var ledger = new CacheLedger();
        String context = "ledger header";
        try {
            fields(tag, Set.of("schema", "integrity_key", "caches", "preferences", "redirects", "grants", "returnAddresses", "private_retirements", "return_handoffs"));
            require(tag, "schema", Tag.TAG_INT);
            int sourceSchema = tag.getInt("schema");
            if (sourceSchema != SCHEMA && sourceSchema != 31001 && sourceSchema != 8 && sourceSchema != 2 && sourceSchema != 4 && sourceSchema != 5 && sourceSchema != 6 && sourceSchema != 7)
                throw new IllegalArgumentException("Unsupported cache schema");
            require(tag, "integrity_key", Tag.TAG_BYTE_ARRAY);
            if (tag.getByteArray("integrity_key").length != 32) throw new IllegalArgumentException("Invalid world integrity state");
            ledger.integrityKey = tag.getByteArray("integrity_key").clone();
            var caches = new HashMap<UUID, CacheRecord>(); var personal = new HashMap<UUID, UUID>();
            ListTag rows = compounds(tag, "caches");
            int cacheIndex = 0;
            for (Tag element : rows) {
                CompoundTag row = (CompoundTag)element;
                context = "cache row " + cacheIndex++ + (row.hasUUID("id") ? " (" + row.getUUID("id") + ")" : " (invalid identity)");
                fields(row, sourceSchema == SCHEMA ? Set.of("id", "level", "revision", "owner", "cells", "orders", "residual", "residuals", "astra_fluids", CapacityCodec.KEY)
                        : sourceSchema == 31001 ? Set.of("id", "level", "revision", "owner", "cells", "orders", "residual", "residuals", "astra_fluids")
                        : Set.of("id", "level", "revision", "owner", "cells", "orders", "residual", "residuals"));
                if (sourceSchema == SCHEMA) require(row, CapacityCodec.KEY, Tag.TAG_COMPOUND);
                require(row, "id", Tag.TAG_INT_ARRAY); require(row, "level", Tag.TAG_INT); require(row, "revision", Tag.TAG_LONG);
                if (row.contains("owner")) require(row, "owner", Tag.TAG_INT_ARRAY);
                UUID id = row.getUUID("id"); int level = row.getInt("level"); UUID owner = row.contains("owner") ? row.getUUID("owner") : null;
                List<Cell<ItemVariantKey>> cells = new ArrayList<>(); var cellRows = compounds(row, "cells");
                if (cellRows.size() < 1 || cellRows.size() > CacheCapacity.MAX_SLOTS || !row.contains(CapacityCodec.KEY) && cellRows.size() != CacheLevel.of(level).slots()) throw new IllegalArgumentException("Invalid cell count");
                for (Tag cellTag : cellRows) {
                    var cell = (CompoundTag)cellTag;
                    fields(cell, sourceSchema >= 31001 ? Set.of("filter", "amount", "min", "max", "revision", "item_controls")
                            : Set.of("filter", "amount", "min", "max", "revision"));
                    require(cell, "amount", Tag.TAG_INT); require(cell, "min", Tag.TAG_INT); require(cell, "max", Tag.TAG_INT); require(cell, "revision", Tag.TAG_LONG);
                    if (cell.contains("filter")) require(cell, "filter", Tag.TAG_STRING);
                    ItemVariantKey filter = cell.contains("filter") ? ItemVariantKey.decode(cell.getString("filter"), registries) : null;
                    int min = cell.getInt("min"), max = cell.getInt("max");
                    if (sourceSchema == 2 && filter != null) {
                        if (min >= 0 && min % filter.stackSize() != 0 || max >= 0 && max % filter.stackSize() != 0)
                            throw new IllegalArgumentException("Legacy item threshold cannot be converted without rounding");
                        min = min < 0 ? -1 : min / filter.stackSize(); max = max < 0 ? -1 : max / filter.stackSize();
                    }
                    int amount = cell.getInt("amount");
                    // Never truncate historical stock or settings to make a legacy record fit.
                    if (sourceSchema < 5 && filter != null) {
                        int itemCap = CacheLevel.of(level).groupCapacity() * filter.stackSize();
                        if (amount > itemCap || min > CacheLevel.of(level).groupCapacity() || max > CacheLevel.of(level).groupCapacity())
                            throw new IllegalArgumentException("Legacy cache exceeds current capacity; original data preserved");
                    }
                    CellSettings settings = CellSettings.fromGroups(filter, min, max);
                    if (sourceSchema >= 31001) {
                        require(cell, "item_controls", Tag.TAG_COMPOUND); var controls = cell.getCompound("item_controls");
                        fields(controls, Set.of("lower", "upper", "batch", "pending_limit"));
                        for (String name : List.of("lower", "upper", "batch", "pending_limit")) require(controls, name, Tag.TAG_INT);
                        settings = new CellSettings(controls.getInt("lower"), controls.getInt("upper"), controls.getInt("batch"), controls.getInt("pending_limit"));
                    }
                    cells.add(new Cell<>(filter, amount, min, max, cell.getLong("revision"), settings));
                }
                var orders = new HashMap<UUID, SupplyOrder<ItemVariantKey>>(); var orderRows = compounds(row, "orders");
                if (orderRows.size() > CacheState.MAX_ORDERS) throw new IllegalArgumentException("Too many orders");
                for (Tag orderTag : orderRows) {
                    var data = (CompoundTag)orderTag;
                    fields(data, Set.of("id", "player", "variant", "revision", "remaining", "uncertain"));
                    require(data, "id", Tag.TAG_INT_ARRAY); require(data, "player", Tag.TAG_INT_ARRAY); require(data, "variant", Tag.TAG_STRING);
                    require(data, "remaining", Tag.TAG_INT); require(data, "revision", Tag.TAG_LONG); bool(data, "uncertain");
                    UUID orderId = data.getUUID("id"); var order = new SupplyOrder<>(orderId, data.getUUID("player"), ItemVariantKey.decode(data.getString("variant"), registries), data.getLong("revision"), data.getInt("remaining"), data.getBoolean("uncertain"));
                    if (orders.put(orderId, order) != null) throw new IllegalArgumentException("Duplicate order");
                }
                if (row.contains("residual")) require(row, "residual", Tag.TAG_COMPOUND);
                Map<ItemVariantKey, ItemStack> residuals = new HashMap<>();
                if (row.contains("residuals")) {
                    for (Tag residualTag : compounds(row, "residuals")) {
                        var data = (CompoundTag)residualTag;
                        fields(data, Set.of("variant", "package"));
                        require(data, "variant", Tag.TAG_STRING); require(data, "package", Tag.TAG_COMPOUND);
                        ItemVariantKey variant = ItemVariantKey.decode(data.getString("variant"), registries);
                        ItemStack box = ItemStack.parse(registries, data.getCompound("package")).orElseThrow();
                        if (box.getCount() != 1 || !com.simibubi.create.content.logistics.box.PackageItem.isPackage(box))
                            throw new IllegalArgumentException("Residual slot contains a non-package");
                        if (residuals.put(variant, box) != null) throw new IllegalArgumentException("Duplicate residual variant");
                    }
                } else if (row.contains("residual")) {
                    ItemStack box = ItemStack.parse(registries, row.getCompound("residual")).orElseThrow();
                    if (!box.isEmpty()) {
                        if (box.getCount() != 1 || !com.simibubi.create.content.logistics.box.PackageItem.isPackage(box))
                            throw new IllegalArgumentException("Residual slot contains a non-package");
                        var seal = box.get(dev.scathiard.feedmepackages.registry.FmpRegistries.PARCEL_SEAL.get());
                        if (seal == null) throw new IllegalArgumentException("Residual package has no seal");
                        residuals.put(ItemVariantKey.decode(seal.variant(), registries), box);
                    }
                }
                var state = CapacityCodec.read(row, id, level, row.getLong("revision"), cells, orders);
                var fluids = CacheFluids.empty(state);
                if (row.contains("astra_fluids")) {
                    require(row, "astra_fluids", Tag.TAG_COMPOUND);
                    fluids = FluidStorageCodec.read(row.getCompound("astra_fluids"), state, registries);
                }
                if (caches.put(id, new CacheRecord(state, owner, residuals, fluids)) != null) throw new IllegalArgumentException("Duplicate cache identity");
                if (owner != null && personal.put(owner, id) != null) throw new IllegalArgumentException("Duplicate personal cache");
            }
            context = "ledger metadata";
            var preferences = new HashMap<UUID, Boolean>();
            for (Tag element : compounds(tag, "preferences")) {
                var row = (CompoundTag)element;
                fields(row, Set.of("player", "cache_first")); require(row, "player", Tag.TAG_INT_ARRAY); bool(row, "cache_first");
                if (preferences.put(row.getUUID("player"), row.getBoolean("cache_first")) != null) throw new IllegalArgumentException("Duplicate player preference");
            }
            var redirects = new HashMap<UUID, ParcelRedirect>(); var routeCounts = new HashMap<UUID, Integer>();
            for (Tag element : compounds(tag, "redirects")) {
                var row = (CompoundTag)element; fields(row, Set.of("source", "target", "variants"));
                require(row, "source", Tag.TAG_INT_ARRAY); require(row, "target", Tag.TAG_INT_ARRAY);
                UUID source = row.getUUID("source"), target = row.getUUID("target");
                if (caches.containsKey(source) || !caches.containsKey(target) || caches.get(target).owner() == null)
                    throw new IllegalArgumentException("Invalid redirect ownership");
                var mappings = new HashMap<ParcelRoute<ItemVariantKey>, Long>();
                for (Tag mappingTag : compounds(row, "variants")) {
                    var mapping = (CompoundTag)mappingTag; fields(mapping, Set.of("variant", "before", "after"));
                    require(mapping, "variant", Tag.TAG_STRING); require(mapping, "before", Tag.TAG_LONG); require(mapping, "after", Tag.TAG_LONG);
                    var key = new ParcelRoute<>(ItemVariantKey.decode(mapping.getString("variant"), registries), mapping.getLong("before"));
                    if (mappings.put(key, mapping.getLong("after")) != null) throw new IllegalArgumentException("Duplicate redirect variant");
                }
                if (redirects.put(source, new ParcelRedirect(target, mappings)) != null) throw new IllegalArgumentException("Duplicate redirect identity");
                if (routeCounts.merge(target, mappings.size(), Math::addExact) > MAX_REDIRECT_VARIANTS) throw new IllegalArgumentException("Too many personal parcel routes");
            }
            // Schema <=6 persisted a vestigial capacity-grant table; the redesign removed that entitlement flow.
            // Tolerate the field so old worlds still migrate their caches, but treat the grants as obsolete.
            var retirements = new HashMap<UUID, Retirement>(); var receipts = new HashSet<UUID>(); var retiredCaches = new HashSet<UUID>();
            if (sourceSchema >= 31001) {
                for (Tag element : compounds(tag, "private_retirements")) {
                    var row = (CompoundTag) element; fields(row, Set.of("owner", "cache", "receipt", "completed"));
                    for (String name : List.of("owner", "cache", "receipt")) require(row, name, Tag.TAG_INT_ARRAY);
                    bool(row, "completed");
                    var transfer = new Retirement(row.getUUID("owner"), row.getUUID("cache"), row.getUUID("receipt"), row.getBoolean("completed"));
                    if (!transfer.cache().equals(personal.get(transfer.owner())) || !receipts.add(transfer.receipt())
                            || !retiredCaches.add(transfer.cache()) || retirements.put(transfer.owner(), transfer) != null)
                        throw new IllegalArgumentException("Invalid or duplicate retirement");
                    if (transfer.completed()) {
                        var source = caches.get(transfer.cache());
                        caches.put(transfer.cache(), new CacheRecord(source.state(), null, source.residuals(), source.fluids())); personal.remove(transfer.owner());
                    }
                }
            } else if (tag.contains("private_retirements")) throw new IllegalArgumentException("Retirement in legacy schema");
            ledger.retirements = Map.copyOf(retirements);
            ledger.caches = Map.copyOf(caches); ledger.personal = Map.copyOf(personal); ledger.cacheFirst = Map.copyOf(preferences);
            ledger.redirects = Map.copyOf(redirects);
            var returnAddresses = new HashMap<UUID, String>();
            if (tag.contains("returnAddresses")) {
                for (Tag element : compounds(tag, "returnAddresses")) {
                    var row = (CompoundTag)element;
                    fields(row, Set.of("cache", "address")); require(row, "cache", Tag.TAG_INT_ARRAY); require(row, "address", Tag.TAG_STRING);
                    if (returnAddresses.put(row.getUUID("cache"), row.getString("address")) != null) throw new IllegalArgumentException("Duplicate return address");
                }
            }
            ledger.returnAddresses = Map.copyOf(returnAddresses);
            var holds = new HashMap<UUID, ReturnEscrow>();
            if (tag.contains("return_handoffs")) {
                if (sourceSchema < 31001) throw new IllegalArgumentException("Return escrows in a foreign schema");
                var pending = compounds(tag, "return_handoffs");
                if (pending.size() > caches.size()) throw new IllegalArgumentException("Too many return escrows");
                for (Tag element : pending) {
                    var hold = ReturnEscrow.load((CompoundTag) element, registries);
                    hold.settled(caches.get(hold.handle().cacheId()), registries);
                    if (holds.put(hold.handle().cacheId(), hold) != null) throw new IllegalArgumentException("Duplicate return escrow");
                }
            }
            ledger.returnEscrows = Map.copyOf(holds);
        } catch (RuntimeException failure) {
            ledger.problem = "Cache data rejected at " + context + ": " + failure.getClass().getSimpleName();
            ledger.preserved = tag.copy();
            FeedMePackages.LOGGER.error("FMP cache ledger locked at {}; original data retained", context, failure);
        }
        return ledger;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or mistyped ledger field: " + key);
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LIST); var list = (ListTag)tag.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Wrong ledger list element type: " + key);
        return list;
    }

    private static void bool(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_BYTE);
        if (tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IllegalArgumentException("Invalid boolean: " + key);
    }

    private static void fields(CompoundTag tag, Set<String> allowed) {
        if (!allowed.containsAll(tag.getAllKeys())) throw new IllegalArgumentException("Unknown ledger fields");
    }
}
