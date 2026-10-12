package dev.scathiard.feedmepackages.compat.maid;

/**
 * The one rule that decides whether a maid-bee return may <b>end</b> (user 2026-10-13, plan B).
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested: ending a return means removing the bee entity,
 * so it is only allowed when the bee is recorded, the maid is reachable right now and the native carrier item was
 * resolved. <b>"The bee is gone and the bee item never reached her" must be impossible on every path.</b>
 */
public final class ReturnDecision {
    private ReturnDecision() {}

    public static boolean mayEnd(boolean recorded, boolean maidReachable, boolean itemResolved) {
        return recorded && maidReachable && itemResolved;
    }
}
