package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Historical merge fixture shared by unit and GameTests; never packaged with the mod. */
public final class CacheMerge {
    private CacheMerge() {}
    public enum Reason { SAME_CACHE, CONFIGURATION_CONFLICT, NO_SPACE, INVALID_ORDER, TOO_MANY_ORDERS }
    public static final class Rejected extends IllegalArgumentException {
        private final Reason reason;
        private Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    public record Plan<K extends MaterialVariant>(CacheState<K> source, CacheState<K> target, CacheState<K> merged, Map<ParcelRoute<K>, Long> sourceRoutes) {
        public Plan { sourceRoutes = Map.copyOf(sourceRoutes); }
    }

    public static <K extends MaterialVariant> Plan<K> simulate(CacheState<K> source, CacheState<K> target) {
        if (source.id().equals(target.id())) throw new Rejected(Reason.SAME_CACHE);
        validateOrders(source); validateOrders(target);
        if ((long) source.orders().size() + target.orders().size() > CacheState.MAX_ORDERS)
            throw new Rejected(Reason.TOO_MANY_ORDERS);
        var limits = CacheLevel.of(Math.max(source.level(), target.level()));
        var cells = new ArrayList<>(target.cells());
        while (cells.size() < limits.slots()) cells.add(Cell.empty());
        long revision = cells.stream().mapToLong(Cell::filterRevision).max().orElse(0);
        Map<ParcelRoute<K>, Long> routes = new LinkedHashMap<>();
        for (var offered : source.cells()) {
            if (offered.filter() == null) continue;
            int slot = find(cells, offered.filter());
            if (slot >= 0) {
                var existing = cells.get(slot);
                if (existing.minimum() != offered.minimum() || existing.maximum() != offered.maximum())
                    throw new Rejected(Reason.CONFIGURATION_CONFLICT);
                long amount = (long) existing.amount() + offered.amount();
                if (amount > (long) limits.groupCapacity() * offered.filter().stackSize()) throw new Rejected(Reason.NO_SPACE);
                cells.set(slot, existing.withAmount((int) amount));
            } else {
                slot = find(cells, null);
                if (slot < 0 || offered.amount() > (long) limits.groupCapacity() * offered.filter().stackSize()) throw new Rejected(Reason.NO_SPACE);
                revision = Math.incrementExact(revision);
                cells.set(slot, new Cell<>(offered.filter(), offered.amount(), offered.minimum(), offered.maximum(), revision));
            }
            routes.put(new ParcelRoute<>(offered.filter(), offered.filterRevision()), cells.get(slot).filterRevision());
        }
        var orders = new LinkedHashMap<>(target.orders());
        for (var order : source.orders().values()) {
            long mapped = routes.get(new ParcelRoute<>(order.variant(), order.filterRevision()));
            var transferred = new SupplyOrder<>(order.id(), order.player(), order.variant(), mapped, order.remaining(), order.uncertain());
            if (orders.putIfAbsent(order.id(), transferred) != null) throw new Rejected(Reason.INVALID_ORDER);
        }
        var merged = new CacheState<>(target.id(), limits.level(), Math.incrementExact(target.revision()), cells, orders);
        return new Plan<>(source, target, merged, routes);
    }

    private static <K extends MaterialVariant> void validateOrders(CacheState<K> state) {
        for (var order : state.orders().values()) {
            int slot = state.find(order.variant());
            if (slot < 0 || state.cells().get(slot).filterRevision() != order.filterRevision()) throw new Rejected(Reason.INVALID_ORDER);
        }
    }
    private static <K extends MaterialVariant> int find(List<Cell<K>> cells, K variant) {
        for (int index = 0; index < cells.size(); index++) if (Objects.equals(cells.get(index).filter(), variant)) return index;
        return -1;
    }
}
