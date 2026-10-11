package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.domain.*;
import java.util.*;

/** Server-thread-only, bounded by open panel pages. Closed windows accumulate no history. */
public final class LogisticsSignals {
    private LogisticsSignals() {}
    private static final Set<Viewer> VIEWERS = Collections.newSetFromMap(new WeakHashMap<>());
    public static final class Viewer implements AutoCloseable {
        private final UUID cache;
        private final Map<Integer, LogisticsSignal> latest = new HashMap<>();
        private Set<Integer> visible = Set.of();
        private long sequence;
        public Viewer(UUID cache) { this.cache = Objects.requireNonNull(cache); VIEWERS.add(this); }
        public boolean matches(UUID id) { return cache.equals(id); }
        public void page(int start, int count) {
            if (start < 0 || count < 0 || count > 36) throw new IllegalArgumentException("Invalid feedback page");
            page(java.util.stream.IntStream.range(start, Math.addExact(start, count)).boxed().toList());
        }
        public void page(List<Integer> slots) {
            if (slots.size() > 36 || slots.stream().anyMatch(i -> i < 0))
                throw new IllegalArgumentException("Invalid feedback page");
            var next = Set.copyOf(slots);
            if (next.size() != slots.size()) throw new IllegalArgumentException("Duplicate feedback slot");
            if (!visible.equals(next)) { visible = next; latest.clear(); }
        }
        public LogisticsSignal get(int slot) { return latest.getOrDefault(slot, LogisticsSignal.NONE); }
        @Override public void close() { VIEWERS.remove(this); latest.clear(); }
    }
    public static void publish(UUID cache, int slot, LogisticsSignal.Kind kind) {
        if (kind == LogisticsSignal.Kind.NONE) return;
        for (var viewer : VIEWERS) if (viewer.cache.equals(cache) && viewer.visible.contains(slot))
            viewer.latest.put(slot, new LogisticsSignal(++viewer.sequence, kind));
    }
    public static <K extends MaterialVariant> void received(CacheState<K> before, CacheState<K> after) {
        if (VIEWERS.isEmpty()) return;
        for (int slot = 0; slot < after.cells().size(); slot++) {
            var old = slot < before.cells().size() ? before.cells().get(slot) : null;
            if (old != null && Objects.equals(old.filter(), after.cells().get(slot).filter()) && after.cells().get(slot).amount() > old.amount())
                publish(after.id(), slot, LogisticsSignal.Kind.ARRIVAL);
        }
    }
}
