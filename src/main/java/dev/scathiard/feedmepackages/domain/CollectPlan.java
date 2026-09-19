package dev.scathiard.feedmepackages.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one-key "collect what I am carrying into the cache cells that already filter it" plan.
 *
 * <p><b>Rules (user request + captain's dispatch).</b>
 * <ul>
 *   <li>Only cells that <b>already filter the exact variant</b> take anything. An item with no matching cell
 *       is left exactly where it is: no new filter is created, no empty cell is used.</li>
 *   <li>Each cell takes only what it can still hold: its item capacity
 *       ({@code groupCapacity × stackSize}), and never above its own {@code maximum} — the same
 *       group-based upper threshold the return path reads in {@code ReturnService} ({@code -1} = no limit).</li>
 *   <li>What does not fit stays in the inventory. Nothing is dropped, overflowed or returned.</li>
 *   <li>The whole thing is a <b>plan</b>: the caller simulates, then commits once. Any failure before the
 *       commit leaves both sides untouched.</li>
 * </ul>
 *
 * <p><b>Pure Java.</b> Only {@link MaterialVariant} is involved, so the arithmetic is unit-testable without
 * a Minecraft type; the server turns the plan into an inventory delta and one ledger replacement.
 *
 * <p>Deterministic: inventory slots are visited in order and a cell is topped up in that order, so the same
 * inventory always produces the same plan.
 */
public final class CollectPlan {
    /** One stack the player carries. {@code variant == null} = the cache cannot hold that stack at all. */
    public record Source(int slot, MaterialVariant variant, int count) {
        public Source {
            if (slot < 0 || count < 0) throw new IllegalArgumentException("Invalid carried stack");
        }
    }

    /** One cache cell that already filters something: what it holds, and its own upper threshold. */
    public record Target(int cell, MaterialVariant variant, int amount, int maximum) {
        public Target {
            if (cell < 0 || variant == null || amount < 0)
                throw new IllegalArgumentException("Invalid target cell");
        }
    }

    /** One cell's top-up from one inventory slot. */
    public record Move(int cell, int inventorySlot, int amount) {
        public Move {
            if (cell < 0 || inventorySlot < 0 || amount <= 0) throw new IllegalArgumentException("Invalid move");
        }
    }

    /**
     * {@code moves} = what to move; {@code moved} = how many items that is; {@code noCell} = distinct kinds the
     * inventory holds but no cell accepts; {@code full} = distinct kinds whose cell had no room left;
     * {@code carried} = distinct kinds carried in total (reporting/sanity only).
     */
    public record Plan(List<Move> moves, int moved, int noCell, int full, int carried) {
        public Plan { moves = List.copyOf(moves); }
        public boolean isEmpty() { return moves.isEmpty(); }
    }

    private CollectPlan() {}

    public static Plan simulate(List<Target> targets, List<Source> sources, int groupCapacity) {
        if (groupCapacity < 1) throw new IllegalArgumentException("Invalid group capacity");
        Objects.requireNonNull(targets);
        Objects.requireNonNull(sources);
        // Room left per exact variant. A cache holds one cell per exact item (CacheState), so the first cell
        // that filters a variant is the only one that can take it.
        Map<MaterialVariant, Integer> room = new LinkedHashMap<>();
        for (Target target : targets) room.putIfAbsent(target.variant(), roomLeft(target, groupCapacity));

        List<Move> moves = new ArrayList<>();
        Set<MaterialVariant> noCell = new LinkedHashSet<>();
        Set<MaterialVariant> full = new LinkedHashSet<>();
        Set<MaterialVariant> carried = new LinkedHashSet<>();
        int moved = 0;
        int unreadable = 0;
        for (Source source : sources) {
            if (source.count() == 0) continue;
            if (source.variant() == null) {   // the cache cannot file this stack at all: leave it alone
                unreadable++;
                continue;
            }
            carried.add(source.variant());
            Integer free = room.get(source.variant());
            if (free == null) { noCell.add(source.variant()); continue; }
            int take = Math.min(source.count(), free);
            if (take <= 0) { full.add(source.variant()); continue; }
            int cell = cellOf(targets, source.variant());
            moves.add(new Move(cell, source.slot(), take));
            moved += take;
            room.put(source.variant(), free - take);
            if (take < source.count()) full.add(source.variant());
        }
        return new Plan(moves, moved, noCell.size() + unreadable, full.size(), carried.size());
    }

    private static int cellOf(List<Target> targets, MaterialVariant variant) {
        for (Target target : targets) if (target.variant().equals(variant)) return target.cell();
        throw new IllegalStateException("No cell for a variant that had room");
    }

    /** Item capacity, capped by the cell's own upper threshold when it has one ({@code -1} = no limit). */
    private static int roomLeft(Target target, int groupCapacity) {
        int stackSize = Math.max(1, target.variant().stackSize());
        int itemCapacity = groupCapacity * stackSize;
        int free = Math.max(0, itemCapacity - target.amount());
        if (target.maximum() >= 0) free = Math.min(free, Math.max(0, target.maximum() * stackSize - target.amount()));
        return free;
    }
}
