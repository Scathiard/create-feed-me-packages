package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Immutable transaction unit. K must be an immutable exact item identity. */
public record CacheState<K extends MaterialVariant>(UUID id, int level, long revision, List<Cell<K>> cells,
                             Map<UUID, SupplyOrder<K>> orders, CacheCapacity capacity,
                             Map<Integer, Integer> retainedCeilings, Map<Integer, CellControls> requestedControls) {
    public static final int MAX_ORDERS = 1024;

    public CacheState {
        Objects.requireNonNull(id);
        CacheLevel.of(level); Objects.requireNonNull(capacity);
        retainedCeilings = Map.copyOf(retainedCeilings); requestedControls = Map.copyOf(requestedControls);
        cells = List.copyOf(cells);
        orders = Map.copyOf(orders);
        if (revision < 0 || cells.size() < capacity.slots() || cells.size() > CacheCapacity.MAX_SLOTS || orders.size() > MAX_ORDERS)
            throw new IllegalArgumentException("Invalid cache structure");
        Set<K> variants = new HashSet<>();
        for (int slot = 0; slot < cells.size(); slot++) {
            Cell<K> cell = cells.get(slot);
            int groupCapacity = capacity.groups();
            int itemCapacity = cell.filter() != null ? capacity.items(cell.filter()) : 0;
            if (cell.amount() > retainedCeilings.getOrDefault(slot, itemCapacity) || cell.minimum() > groupCapacity
                    || cell.maximum() > groupCapacity)
                throw new IllegalArgumentException("Cell exceeds capacity");
            if (cell.filter() != null && !variants.add(cell.filter()))
                throw new IllegalArgumentException("Duplicate exact filter");
            cell.settings().validate(cell.filter(), itemCapacity);
        }
        for (var entry : retainedCeilings.entrySet()) {
            Objects.checkIndex(entry.getKey(), cells.size()); var cell = cells.get(entry.getKey());
            if (cell.filter() == null || cell.amount() <= capacity.items(cell.filter()) || entry.getValue() < cell.amount()
                    || entry.getValue() > new CacheCapacity(1, CacheCapacity.MAX_GROUPS).items(cell.filter()))
                throw new IllegalArgumentException("Invalid retained stock ceiling");
        }
        for (var entry : requestedControls.entrySet()) {
            Objects.checkIndex(entry.getKey(), cells.size()); var cell = cells.get(entry.getKey());
            entry.getValue().validate(cell.filter()); var effective = CellControls.of(cell);
            if (entry.getValue().equals(effective) || !entry.getValue().bounded(capacity, cell.filter()).equals(effective))
                throw new IllegalArgumentException("Retained controls do not describe the effective values");
        }
        for (var entry : orders.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id()))
                throw new IllegalArgumentException("Order index mismatch");
        }
    }

    public CacheState(UUID id, int level, long revision, List<Cell<K>> cells, Map<UUID, SupplyOrder<K>> orders) {
        this(id, level, revision, cells, orders, CacheCapacity.defaults(level), Map.of(), Map.of());
    }
    public CacheState<K> withRevision(long next) {
        return new CacheState<>(id, level, next, cells, orders, capacity, retainedCeilings, requestedControls);
    }
    public boolean writable(int slot) {
        return slot >= 0 && slot < capacity.slots() && !retainedCeilings.containsKey(slot);
    }

    public static <K extends MaterialVariant> CacheState<K> empty(UUID id) {
        return new CacheState<>(id, 1, 0, Collections.nCopies(CacheLevel.of(1).slots(), Cell.empty()), Map.of());
    }

    public int find(K variant) {
        for (int i = 0; i < cells.size(); i++)
            if (Objects.equals(variant, cells.get(i).filter())) return i;
        return -1;
    }

    public int pending(int slot) {
        Cell<K> cell = cells.get(slot);
        long result = orders.values().stream()
                .filter(order -> Objects.equals(order.variant(), cell.filter())
                        && order.filterRevision() == cell.filterRevision())
                .mapToLong(SupplyOrder::remaining).sum();
        return (int) Math.min(result, Integer.MAX_VALUE);
    }

    public int requestable(int slot) { return !writable(slot) ? 0 : cells.get(slot).settings().requestable(cells.get(slot).amount(), pending(slot)); }

    public Arrow arrow(int slot) {
        return !writable(slot) || cells.get(slot).shortage() == 0 ? Arrow.NONE : pending(slot) > 0 ? Arrow.REQUESTED : Arrow.UNSUPPLIED;
    }

    public CacheEdit<K> edit() { return new CacheEdit<>(this); }

    public enum Arrow { NONE, UNSUPPLIED, REQUESTED }
}
