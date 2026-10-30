package dev.scathiard.feedmepackages.storage;

import net.minecraft.nbt.*;
import java.util.*;

/** Index persistent ownership links before decoding any third-party item or fluid.
 * Unknown identities fail the entire index; a failed group is never reduced to its readable rows. */
final class LedgerPartitions {
    static final List<String> TABLES = List.of("caches", "redirects", "returnAddresses", "private_retirements", "return_handoffs");
    record Group(Set<UUID> identities, Set<UUID> owners, CompoundTag rows, String conflict) {}
    private record Row(String table, CompoundTag value, UUID cache) {}
    private final Map<UUID, UUID> parents = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> owners = new HashMap<>();
    private final Map<String, UUID> claims = new HashMap<>();
    private final Set<UUID> conflicts = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();

    static CompoundTag header(CompoundTag source) {
        var header = source.copy();
        for (String table : TABLES) header.put(table, new ListTag());
        return header;
    }
    static List<Group> index(CompoundTag source) {
        var index = new LedgerPartitions();
        for (Tag value : table(source, "caches", true)) {
            var row = (CompoundTag) value; UUID cache = id(row, "id");
            index.add("caches", row, cache);
            index.unique("cache", cache, cache);
            if (row.contains("owner")) index.owner(id(row, "owner"), cache);
        }
        for (Tag value : table(source, "redirects", true)) {
            var row = (CompoundTag) value; UUID from = id(row, "source"), to = id(row, "target");
            index.add("redirects", row, from); index.join(from, to); index.unique("redirect", from, from);
        }
        for (Tag value : table(source, "private_retirements", true)) {
            var row = (CompoundTag) value; UUID cache = id(row, "cache"), owner = id(row, "owner");
            index.add("private_retirements", row, cache); index.owner(owner, cache);
            index.unique("receipt", id(row, "receipt"), cache);
            index.unique("retirement-owner", owner, cache); index.unique("retirement-cache", cache, cache);
        }
        for (Tag value : table(source, "return_handoffs", false)) {
            var row = (CompoundTag) value; UUID cache = id(row, "cache");
            index.add("return_handoffs", row, cache);
            index.unique("handoff", id(row, "id"), cache); index.unique("held-cache", cache, cache);
        }
        for (Tag value : table(source, "returnAddresses", false)) {
            var row = (CompoundTag) value; UUID cache = id(row, "cache");
            index.add("returnAddresses", row, cache); index.unique("address", cache, cache);
        }
        var grouped = new LinkedHashMap<UUID, CompoundTag>();
        var ids = new LinkedHashMap<UUID, Set<UUID>>();
        var groupOwners = new HashMap<UUID, Set<UUID>>();
        var bad = new HashSet<UUID>();
        for (UUID cache : index.parents.keySet()) {
            UUID root = index.root(cache);
            ids.computeIfAbsent(root, unused -> new LinkedHashSet<>()).add(cache);
            groupOwners.computeIfAbsent(root, unused -> new HashSet<>()).addAll(index.owners.getOrDefault(cache, Set.of()));
        }
        for (UUID cache : index.conflicts) bad.add(index.root(cache));
        for (var row : index.rows) {
            var part = grouped.computeIfAbsent(index.root(row.cache), unused -> {
                var tag = new CompoundTag(); for (String table : TABLES) tag.put(table, new ListTag()); return tag;
            });
            part.getList(row.table, Tag.TAG_COMPOUND).add(row.value.copy());
        }
        var result = new ArrayList<Group>();
        for (var entry : grouped.entrySet()) {
            UUID root = entry.getKey();
            result.add(new Group(Set.copyOf(ids.get(root)), Set.copyOf(groupOwners.get(root)), entry.getValue(),
                    bad.contains(root) ? "Conflicting persistent identities" : ""));
        }
        return List.copyOf(result);
    }
    private void add(String table, CompoundTag row, UUID cache) {
        parents.putIfAbsent(cache, cache); rows.add(new Row(table, row, cache));
    }
    private void owner(UUID owner, UUID cache) {
        owners.computeIfAbsent(cache, unused -> new HashSet<>()).add(owner);
        UUID other = claims.putIfAbsent("owner:" + owner, cache);
        if (other != null) join(cache, other);
    }
    private void unique(String kind, UUID identity, UUID cache) {
        UUID other = claims.putIfAbsent(kind + ":" + identity, cache);
        if (other != null) { join(cache, other); conflicts.add(cache); conflicts.add(other); }
    }
    private UUID root(UUID id) {
        parents.putIfAbsent(id, id); UUID root = id;
        while (!parents.get(root).equals(root)) root = parents.get(root);
        while (!parents.get(id).equals(root)) { UUID next = parents.put(id, root); id = next; }
        return root;
    }
    private void join(UUID one, UUID two) { parents.put(root(one), root(two)); }
    private static UUID id(CompoundTag row, String key) {
        if (!row.hasUUID(key)) throw new IllegalArgumentException("Cannot determine ledger ownership field: " + key);
        return row.getUUID(key);
    }
    private static ListTag table(CompoundTag source, String name, boolean required) {
        if (!required && !source.contains(name)) return new ListTag();
        if (!source.contains(name, Tag.TAG_LIST)) throw new IllegalArgumentException("Cannot index ledger table: " + name);
        var list = (ListTag) source.get(name);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Cannot index ledger rows: " + name);
        return list;
    }
}
