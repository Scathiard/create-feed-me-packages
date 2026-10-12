package dev.scathiard.feedmepackages.compat.maid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single rule that decides whether a maid-bee return may end (user 2026-10-13, plan B). Pure Java on purpose:
 * the game classes of {@code MaidCouriers} are only available in the GameTest source set, while this rule must be
 * cheap to pin. The invariant it protects: a return may never be ended unless the bee item can be handed over, so
 * "the bee is gone and the item never reached her" cannot happen on any path.
 */
class ReturnDecisionTest {

    @Test void aReturnEndsOnlyWhenItIsRecordedReachableAndTheItemIsThere() {
        assertTrue(ReturnDecision.mayEnd(true, true, true));
    }

    @Test void withoutTheNativeBeeItemTheReturnIsNeverEnded() {
        assertFalse(ReturnDecision.mayEnd(true, true, false), "no item resolved must never end the return");
    }

    @Test void anUnreachableMaidOrAMissingRecordNeverEndsTheReturn() {
        assertFalse(ReturnDecision.mayEnd(true, false, true), "unreachable maid must keep the bee alive in the world");
        assertFalse(ReturnDecision.mayEnd(false, true, true), "without a record the bee is not ours to end");
        assertFalse(ReturnDecision.mayEnd(false, false, false));
    }
}
