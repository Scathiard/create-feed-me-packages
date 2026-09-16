package dev.scathiard.feedmepackages.compat.jei;

/**
 * The preview's release rule, kept free of game and JEI types so the boundary can be unit-tested.
 *
 * <p>A live panel releases everything to the server EXCEPT the one provable case: the client has a cache
 * view, that view is empty, and the client's own estimate already missed - every source really is empty.
 * Anything weaker is not provable (hint counts are estimates; the grid reading subtracts our own in-flight
 * reservations), and greying the button for an estimate is how a stocked cache once looked empty.
 */
public final class PreviewPolicy {
    private PreviewPolicy() {}

    public static boolean provablyUnsolvable(boolean hasCacheView, boolean estimateMissed, boolean cacheViewEmpty) {
        return hasCacheView && estimateMissed && cacheViewEmpty;
    }
}
