package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-key collect plan. The user's rules are pinned here: only items that already have a cell move, each
 * cell takes only what it can still hold, nothing else is created, and the whole thing is one plan.
 */
class CollectPlanTest {
    /** A stand-in exact item; only {@link MaterialVariant#stackSize()} is meaningful to the plan. */
    private record Thing(String name, int stackSize) implements MaterialVariant {
        @Override public String toString() { return name; }
    }

    private static final Thing STONE = new Thing("stone", 64);
    private static final Thing IRON = new Thing("iron", 64);
    private static final Thing EGG = new Thing("egg", 16);

    private static CollectPlan.Target cell(int slot, MaterialVariant variant, int amount) {
        return new CollectPlan.Target(slot, variant, amount, -1);
    }

    @Test void onlyItemsThatAlreadyHaveACellMove() {
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(cell(0, STONE, 5), cell(3, IRON, 0)),
                List.of(new CollectPlan.Source(0, STONE, 10),
                        new CollectPlan.Source(1, new Thing("gravel", 64), 64),
                        new CollectPlan.Source(2, IRON, 7)),
                2);
        assertEquals(2, plan.moves().size(), "only the two matching kinds may move");
        assertEquals(17, plan.moved(), "10 stone + 7 iron");
        assertEquals(1, plan.noCell(), "gravel has no cell and must be counted, not collected");
        assertEquals(0, plan.full());
        assertEquals(3, plan.carried());
        assertEquals(new CollectPlan.Move(0, 0, 10), plan.moves().get(0));
        assertEquals(new CollectPlan.Move(3, 2, 7), plan.moves().get(1));
    }

    @Test void aCellTakesOnlyWhatItsCapacityStillHolds() {
        // Level 1 group capacity is 2 stacks: a 64-stack item cell holds 128 items, 3 are already in.
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(cell(0, STONE, 3)),
                List.of(new CollectPlan.Source(0, STONE, 64), new CollectPlan.Source(1, STONE, 64),
                        new CollectPlan.Source(2, STONE, 64)),
                2);
        assertEquals(125, plan.moved(), "the cell may only reach its 128-item capacity");
        assertEquals(1, plan.full(), "stone has a cell that ran out of room");
        assertEquals(0, plan.noCell());
        assertEquals(125, plan.moves().stream().mapToInt(CollectPlan.Move::amount).sum());
    }

    @Test void aCellsOwnMaximumCapsTheIntakeBelowItsCapacity() {
        // An egg cell holds 16 x 2 = 32 items, but its maximum says "keep at most one group" = 16.
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(new CollectPlan.Target(0, EGG, 4, 1)),
                List.of(new CollectPlan.Source(0, EGG, 16)),
                2);
        assertEquals(12, plan.moved(), "a maximum of one group (16) minus the 4 already there");
        assertEquals(1, plan.full());
    }

    @Test void anUnreadableStackIsLeftAloneAndCounted() {
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, null, 64), new CollectPlan.Source(1, STONE, 2)),
                2);
        assertEquals(1, plan.moves().size());
        assertEquals(2, plan.moved());
        assertEquals(1, plan.noCell(), "a stack the cache cannot file is reported, never dropped");
        assertEquals(1, plan.carried(), "the unreadable stack is not a carried kind; the stone is");
    }

    @Test void nothingMatchesMeansAnEmptyPlan() {
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, IRON, 64), new CollectPlan.Source(1, EGG, 3)),
                2);
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.moved());
        assertEquals(2, plan.noCell());
        assertEquals(2, plan.carried());
    }

    @Test void aFullCellIsReportedOnceAndKeepsTheItemsInTheInventory() {
        CollectPlan.Plan plan = CollectPlan.simulate(
                List.of(cell(0, STONE, 128)),
                List.of(new CollectPlan.Source(0, STONE, 64), new CollectPlan.Source(5, STONE, 1)),
                2);
        assertTrue(plan.isEmpty(), "an already-full cell takes nothing");
        assertEquals(1, plan.full(), "the kind is counted once, not per slot");
        assertEquals(0, plan.noCell());
    }

    @Test void theSameInventoryAlwaysProducesTheSamePlan() {
        List<CollectPlan.Target> targets = List.of(cell(1, STONE, 0), cell(2, IRON, 0));
        List<CollectPlan.Source> sources = List.of(new CollectPlan.Source(3, STONE, 5),
                new CollectPlan.Source(4, IRON, 6), new CollectPlan.Source(5, STONE, 7));
        assertEquals(CollectPlan.simulate(targets, sources, 2), CollectPlan.simulate(targets, sources, 2));
        CollectPlan.Plan plan = CollectPlan.simulate(targets, sources, 2);
        assertEquals(List.of(new CollectPlan.Move(1, 3, 5), new CollectPlan.Move(2, 4, 6),
                new CollectPlan.Move(1, 5, 7)), plan.moves(), "slot order decides, which cell is taken from the plan");
    }

    @Test void emptyStacksAndBadArgumentsAreRefused() {
        CollectPlan.Plan plan = CollectPlan.simulate(List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, STONE, 0), new CollectPlan.Source(1, null, 0)), 2);
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.carried(), "an empty slot is not a carried kind");
        assertEquals(0, plan.noCell());
        assertThrows(IllegalArgumentException.class,
                () -> CollectPlan.simulate(List.of(cell(0, STONE, 0)), List.of(), 0));
    }

    /**
     * The silence rule (user, 2026-09-19): "没有物品可转移／已满／一切失败路径 ⇒ 一律不发任何消息", and when
     * something did move the player gets exactly one line. This predicate is the single gate the server reads.
     */
    @Test void thePlayerIsToldOnlyWhenSomethingActuallyMoved() {
        assertFalse(CollectPlan.simulate(List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, IRON, 64)), 2).shouldReport(), "an unmatched kind is silent");
        assertFalse(CollectPlan.simulate(List.of(cell(0, STONE, 128)),
                List.of(new CollectPlan.Source(0, STONE, 64)), 2).shouldReport(), "a full cell is silent");
        assertFalse(CollectPlan.simulate(List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, STONE, 0)), 2).shouldReport(), "an empty inventory is silent");
        assertFalse(CollectPlan.simulate(List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, null, 64)), 2).shouldReport(), "an unreadable stack is silent");
        assertTrue(CollectPlan.simulate(List.of(cell(0, STONE, 0)),
                List.of(new CollectPlan.Source(0, STONE, 3)), 2).shouldReport(), "a move is reported once");
        assertTrue(CollectPlan.simulate(List.of(cell(0, STONE, 126)),
                List.of(new CollectPlan.Source(0, STONE, 64)), 2).shouldReport(), "a partial move is reported");
    }
}
