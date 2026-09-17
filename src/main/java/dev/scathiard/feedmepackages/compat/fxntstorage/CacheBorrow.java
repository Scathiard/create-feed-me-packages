package dev.scathiard.feedmepackages.compat.fxntstorage;

import java.util.*;

/**
 * The pure lending plan behind the Create: Storage compat wrapper, shared by BOTH sides so the client preview
 * and the server placement always borrow the same cells.
 *
 * <p>Rules: at most one cache stack per empty slot, only from cells the caller's material filter accepts,
 * never more than the cache can give or the item can stack; a shortage exposes fewer slots, and an empty
 * filter exposes nothing (then their own behaviour is preserved).
 */
public final class CacheBorrow {
    /** One cache cell we may present: {@code key} is its position in the side's view, {@code cell} its cache index. */
    public record Supply(int key, int cell, int available, int maxStack) {}
    public record Lend(int slot, int key, int cell, int presented) {}
    public record Settled(int slot, int key, int cell, int presented, int taken) {}

    private final Map<Integer, Lend> lends = new LinkedHashMap<>();

    /** The one rule both sides use: which empty slots lend which cache cells, in slot order. */
    public void plan(List<Integer> emptySlots, List<Supply> supplies, java.util.function.IntPredicate wanted) {
        lends.clear();
        if (emptySlots == null || supplies == null || wanted == null) return;
        int next = 0;
        for (int slot : emptySlots) {
            while (next < supplies.size() && !wanted.test(supplies.get(next).key())) next++;
            if (next >= supplies.size()) break;
            Supply supply = supplies.get(next++);
            int presented = Math.min(supply.available(), Math.max(0, supply.maxStack()));
            if (presented <= 0) continue;
            lends.put(slot, new Lend(slot, supply.key(), supply.cell(), presented));
        }
    }
    public Lend lend(int slot) { return lends.get(slot); }
    public int exposed() { return lends.size(); }
    public void forget(int slot) { lends.remove(slot); }
    /** Hand a lent slot back with {@code remaining} items left; null when the slot is not ours to settle. */
    public Settled settle(int slot, int remaining) {
        Lend lend = lends.remove(slot);
        if (lend == null) return null;
        int left = Math.max(0, Math.min(remaining, lend.presented()));
        return new Settled(slot, lend.key(), lend.cell(), lend.presented(), lend.presented() - left);
    }
    /** Take {@code amount} out of a lent slot without ending the lending; the lending ends when it is empty. */
    public Settled use(int slot, int amount) {
        Lend lend = lends.get(slot);
        if (lend == null) return null;
        int taken = Math.max(0, Math.min(amount, lend.presented()));
        int left = lend.presented() - taken;
        if (left <= 0) lends.remove(slot); else lends.put(slot, new Lend(slot, lend.key(), lend.cell(), left));
        return new Settled(slot, lend.key(), lend.cell(), lend.presented(), taken);
    }
    public void clear() { lends.clear(); }
}