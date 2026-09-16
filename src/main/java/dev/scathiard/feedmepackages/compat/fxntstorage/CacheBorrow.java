package dev.scathiard.feedmepackages.compat.fxntstorage;

import java.util.*;

/**
 * The pure lending plan behind the Create: Storage compat wrapper: which of their empty slots we may borrow,
 * how much we may present there, and how much was really taken when they hand a slot back.
 *
 * <p>Free of game types on purpose so the rules are unit-testable. The rules are deliberately conservative:
 * at most one cache stack per empty slot, never more than the cache can give or the item can stack, and any
 * shortage simply exposes fewer slots (which degrades to their own behaviour instead of inventing material).
 */
public final class CacheBorrow {
    /** One cache stack we may present: {@code key} identifies it, {@code available} is what the cache can give. */
    public record Supply(int key, int available, int maxStack) {}
    /** A slot we currently lend: what we presented there. */
    public record Lend(int slot, int key, int presented) {}
    /** What a hand-back means: {@code taken} items are gone and must be debited from the cache. */
    public record Settled(int slot, int key, int presented, int taken) {}

    private final Map<Integer, Lend> lends = new LinkedHashMap<>();

    /** Assign at most one cache stack per empty slot, in slot order; leftover supply is simply not exposed. */
    public void plan(List<Integer> emptySlots, List<Supply> supplies) {
        lends.clear();
        if (emptySlots == null || supplies == null) return;
        int next = 0;
        for (int slot : emptySlots) {
            if (next >= supplies.size()) break;
            Supply supply = supplies.get(next++);
            int presented = Math.min(supply.available(), Math.max(0, supply.maxStack()));
            if (presented <= 0) continue;
            lends.put(slot, new Lend(slot, supply.key(), presented));
        }
    }
    public Lend lend(int slot) { return lends.get(slot); }
    public int exposed() { return lends.size(); }
    /** Their own stack reappeared in that slot: the lending for it is over. */
    public void forget(int slot) { lends.remove(slot); }
    /**
     * Take {@code amount} out of a lent slot without ending the lending (an extract may be repeated);
     * null when the slot is not ours. The lending ends by itself once everything presented was taken.
     */
    public Settled use(int slot, int amount) {
        Lend lend = lends.get(slot);
        if (lend == null) return null;
        int taken = Math.max(0, Math.min(amount, lend.presented()));
        int left = lend.presented() - taken;
        if (left <= 0) lends.remove(slot); else lends.put(slot, new Lend(slot, lend.key(), left));
        return new Settled(slot, lend.key(), lend.presented(), taken);
    }
    /** Hand a lent slot back with {@code remaining} items left; null when the slot is not ours to settle. */
    public Settled settle(int slot, int remaining) {
        Lend lend = lends.remove(slot);
        if (lend == null) return null;
        int left = Math.max(0, Math.min(remaining, lend.presented()));
        return new Settled(slot, lend.key(), lend.presented(), lend.presented() - left);
    }
    public void clear() { lends.clear(); }
}
