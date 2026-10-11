package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Private simulation workspace. Discard on failure; publish its result only once. */
public final class CacheEdit<K extends MaterialVariant> {
    private final CacheState<K> before;
    private final List<Cell<K>> cells;
    private final Map<UUID, SupplyOrder<K>> orders;
    private int level;
    private CacheCapacity capacity;
    private final Map<Integer, Integer> retainedCeilings;
    private final Map<Integer, CellControls> requestedControls;
    private boolean finished;

    CacheEdit(CacheState<K> before) {
        this.before = before;
        cells = new ArrayList<>(before.cells());
        orders = new LinkedHashMap<>(before.orders());
        level = before.level(); capacity = before.capacity();
        retainedCeilings = new HashMap<>(before.retainedCeilings());
        requestedControls = new HashMap<>(before.requestedControls());
    }

    /** Immutable view of this private simulation, without finishing or publishing it. */
    public List<Cell<K>> cells() { open(); return List.copyOf(cells); }

    public void filter(int slot, K variant) {
        open(); writable(slot);
        if (variant != null) for (int i = 0; i < cells.size(); i++)
            if (i != slot && variant.equals(cells.get(i).filter()))
                throw new IllegalArgumentException("Duplicate exact filter");
        Cell<K> old = cells.get(slot);
        Cell<K> next = old.configure(variant);
        if (!next.equals(old)) {
            // Routes do not contain the visual slot number. A moved/recreated variant must
            // never inherit another slot's local counter and accept an old sealed parcel.
            long revision = Math.incrementExact(cells.stream().mapToLong(Cell::filterRevision).max().orElse(0));
            next = new Cell<>(next.filter(), next.amount(), next.minimum(), next.maximum(), revision, next.settings());
            reset(slot); requestedControls.remove(slot); retainedCeilings.remove(slot);
        }
        cells.set(slot, next);
    }

    public void thresholds(int slot, int min, int max) {
        open(); writable(slot); requestedControls.remove(slot);
        Cell<K> cell = cells.get(slot);
        int groupCapacity = capacity.groups();
        if (min > groupCapacity || max > groupCapacity) throw new IllegalArgumentException("Threshold exceeds capacity");
        cells.set(slot, cell.thresholds(min, max));
    }

    public int insert(int slot, K variant, int offered) {
        open();
        if (offered < 0) throw new IllegalArgumentException("Negative offer");
        if (!canWrite(slot)) return 0;
        Cell<K> cell = cells.get(slot);
        if (variant == null || !variant.equals(cell.filter())) return 0;
        int itemCapacity = capacity.groups() * variant.stackSize();
        int accepted = Math.min(offered, Math.max(0, itemCapacity - cell.amount()));
        cells.set(slot, cell.withAmount(cell.amount() + accepted));
        return accepted;
    }

    public void settings(int slot, CellSettings settings) {
        open(); writable(slot); requestedControls.remove(slot);
        Cell<K> cell = cells.get(slot);
        if (cell.filter() == null) throw new IllegalArgumentException("No filter");
        settings.validate(cell.filter(), Math.multiplyExact(capacity.groups(), cell.filter().stackSize()));
        cells.set(slot, cell.settings(settings));
    }

    public int extract(int slot, int wanted) {
        open();
        if (wanted < 0) throw new IllegalArgumentException("Negative extraction");
        Cell<K> cell = cells.get(slot);
        int taken = Math.min(wanted, cell.amount());
        cells.set(slot, cell.withAmount(cell.amount() - taken));
        return taken;
    }

    public void request(int slot, UUID orderId, UUID player, int amount, boolean uncertain) {
        open(); writable(slot);
        Cell<K> cell = cells.get(slot);
        long pending = orders.values().stream().filter(o -> Objects.equals(o.variant(), cell.filter())
                && o.filterRevision() == cell.filterRevision()).mapToLong(SupplyOrder::remaining).sum();
        if (amount < cell.settings().batch() || amount > cell.settings().requestable(cell.amount(), pending) || orders.size() >= CacheState.MAX_ORDERS)
            throw new IllegalArgumentException("Request has no unreserved shortage or order table is full");
        if (orders.containsKey(orderId)) throw new IllegalArgumentException("Duplicate order identity");
        orders.put(orderId, new SupplyOrder<>(orderId, player, cell.filter(), cell.filterRevision(), amount, uncertain));
    }

    /** Caller must prove first local arrival of this physical parcel, including its exact route. */
    public void arrived(UUID orderId, int arrived) {
        open();
        if (arrived < 0) throw new IllegalArgumentException("Negative arrival");
        SupplyOrder<K> old = orders.get(orderId);
        if (old == null) return; // Explicitly reset or already fully accounted; late stock still exists.
        int remaining = Math.max(0, old.remaining() - arrived);
        if (remaining == 0) orders.remove(orderId);
        else orders.put(orderId, new SupplyOrder<>(old.id(), old.player(), old.variant(), old.filterRevision(), remaining, old.uncertain()));
    }

    /** Release only quantities proven not dispatched; never resurrect an already received order. */
    public void confirmDispatch(UUID orderId, int notDispatched) {
        open();
        if (notDispatched < 0) throw new IllegalArgumentException("Negative unfulfilled quantity");
        SupplyOrder<K> old = orders.get(orderId);
        if (old == null) return;
        int remaining = Math.max(0, old.remaining() - notDispatched);
        if (remaining == 0) orders.remove(orderId);
        else orders.put(orderId, new SupplyOrder<>(old.id(), old.player(), old.variant(), old.filterRevision(), remaining, false));
    }

    public void reset(int slot) {
        open();
        Cell<K> cell = cells.get(slot);
        orders.values().removeIf(o -> Objects.equals(o.variant(), cell.filter())
                && o.filterRevision() == cell.filterRevision());
    }

    public void upgrade() { upgrade(CacheCapacity.defaults(level + 1)); }
    public void upgrade(CacheCapacity next) {
        open(); CacheLevel.of(level + 1); resize(next); level++;
    }
    /** Only a validated previous state may establish retained stock. Never trims, reorders or silently writes it off. */
    public void resize(CacheCapacity next) {
        open(); Objects.requireNonNull(next);
        if (next.equals(capacity)) return;
        while (cells.size() < next.slots()) cells.add(Cell.empty());
        for (int slot = 0; slot < cells.size(); slot++) {
            var cell = cells.get(slot); if (cell.filter() == null) continue;
            if (cell.amount() > next.items(cell.filter())) retainedCeilings.merge(slot, cell.amount(), Math::max);
            else retainedCeilings.remove(slot);
            var desired = requestedControls.getOrDefault(slot, CellControls.of(cell));
            var effective = desired.bounded(next, cell.filter());
            cells.set(slot, effective.apply(cell));
            if (effective.equals(desired)) requestedControls.remove(slot); else requestedControls.put(slot, desired);
        }
        capacity = next;
    }
    private boolean canWrite(int slot) {
        Objects.checkIndex(slot, cells.size()); var cell = cells.get(slot);
        return slot < capacity.slots() && (cell.filter() == null || cell.amount() <= capacity.items(cell.filter()));
    }
    private void writable(int slot) { if (!canWrite(slot)) throw new IllegalArgumentException("Cell is read-only after configured capacity reduction"); }

    public CacheState<K> finish() {
        open();
        finished = true;
        retainedCeilings.entrySet().removeIf(e -> cells.get(e.getKey()).amount() <= capacity.items(cells.get(e.getKey()).filter()));
        if (level == before.level() && capacity.equals(before.capacity()) && retainedCeilings.equals(before.retainedCeilings())
                && requestedControls.equals(before.requestedControls()) && cells.equals(before.cells()) && orders.equals(before.orders())) return before;
        return new CacheState<>(before.id(), level, Math.incrementExact(before.revision()), cells, orders, capacity, retainedCeilings, requestedControls);
    }

    private void open() {
        if (finished) throw new IllegalStateException("Transaction already finished");
    }
}
