package dev.scathiard.feedmepackages.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R-019 acceptance rules that are pure arithmetic: who takes part, how much each cell contributes, in which order, and
 * that no line can fall below its own minimum. The game-side cases (packages, carriers, refusals) build on these.
 */
class ReturnPlanTest {
    private static ReturnPlan.Line line(int slot, int requested) {
        return new ReturnPlan.Line(slot, requested);
    }

    @Test void onlyCellsThatReachTheirOwnMinimumTakePart() {
        // The specification's own case: gold may only be 63 over with a minimum of 64, so it stays where it is.
        var plan = ReturnPlan.plan(List.of(
                new ReturnPlan.Eligible(0, 4 * 64, 64),
                new ReturnPlan.Eligible(1, 3 * 64, 64),
                new ReturnPlan.Eligible(2, 2 * 64, 64),
                new ReturnPlan.Eligible(3, 63, 64)));
        assertEquals(List.of(line(0, 256), line(1, 192), line(2, 128)), plan);
        assertEquals(576, ReturnPlan.total(plan));
    }

    @Test void eachLineIsAWholeNumberOfItsOwnMinimum() {
        var plan = ReturnPlan.plan(List.of(
                new ReturnPlan.Eligible(0, 9 * 64, 64),
                new ReturnPlan.Eligible(1, 2 * 64 + 17, 2 * 64),   // one full pair, 17 items short of another
                new ReturnPlan.Eligible(2, 5 * 64, 3 * 64)));      // one triple only, the rest waits
        assertEquals(List.of(line(0, 576), line(1, 128), line(2, 192)), plan);
    }

    @Test void theOrderIsTheCacheScanOrder() {
        var plan = ReturnPlan.plan(List.of(
                new ReturnPlan.Eligible(7, 2 * 64, 64),
                new ReturnPlan.Eligible(2, 64, 64),
                new ReturnPlan.Eligible(5, 3 * 64, 64)));
        assertEquals(List.of(7, 2, 5), plan.stream().map(ReturnPlan.Line::slot).toList());
    }

    @Test void nothingEligibleMeansNoDispatch() {
        assertTrue(ReturnPlan.plan(List.of()).isEmpty());
        assertTrue(ReturnPlan.plan(null).isEmpty());
        assertTrue(ReturnPlan.plan(List.of(
                new ReturnPlan.Eligible(0, 0, 64),
                new ReturnPlan.Eligible(1, 63, 64),
                new ReturnPlan.Eligible(2, 10, 0))).isEmpty());
        assertEquals(0, ReturnPlan.total(List.of()));
    }
}
