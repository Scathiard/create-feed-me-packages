package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.util.UUID;

/**
 * FMP's own markers on a Create Phantom courier task (T2-b). They live under <b>our</b> keys, next to the native
 * data, exactly like the transport bee's {@code fmp_maid_return}: the native task never reads them, and a task
 * without them stays a completely ordinary player task.
 *
 * <p><b>Missing and corrupt are different things.</b> All three keys absent = native player task (we do nothing).
 * Any partial or unparsable combination = corrupt, which must <b>never</b> degrade into a normal player task
 * (that is what would let a maid's parcel be resolved as a player parcel).
 */
public final class PhantomMaidTaskMarker {
    private PhantomMaidTaskMarker() {}
    public static final String KEY_TASK = "fmp_phantom_task";
    public static final String KEY_MAID = "fmp_phantom_maid";
    public static final String KEY_DIRECTION = "fmp_phantom_direction";

    public enum Direction { OUTBOUND, RETURN }
    /** NATIVE = no marker at all; MARKED = complete and parsable; CORRUPT = anything in between. */
    public enum State { NATIVE, MARKED, CORRUPT }

    public record Decoded(State state, UUID taskId, UUID maidId, Direction direction) {
        public boolean marked() { return state == State.MARKED; }
        public boolean outbound() { return state == State.MARKED && direction == Direction.OUTBOUND; }
    }

    public static Decoded decode(String taskId, String maidId, String direction) {
        boolean anyTask = present(taskId), anyMaid = present(maidId), anyDirection = present(direction);
        if (!anyTask && !anyMaid && !anyDirection) return new Decoded(State.NATIVE, null, null, null);
        if (!anyTask || !anyMaid || !anyDirection) return new Decoded(State.CORRUPT, null, null, null);
        try {
            UUID task = UUID.fromString(taskId.trim());
            UUID maid = UUID.fromString(maidId.trim());
            return new Decoded(State.MARKED, task, maid, Direction.valueOf(direction.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException malformed) {
            return new Decoded(State.CORRUPT, null, null, null);
        }
    }

    /** The three strings to write under {@link #KEY_TASK}/{@link #KEY_MAID}/{@link #KEY_DIRECTION}. */
    public static String[] encode(UUID taskId, UUID maidId, Direction direction) {
        if (taskId == null || maidId == null || direction == null) throw new IllegalArgumentException("Incomplete marker");
        return new String[]{taskId.toString(), maidId.toString(), direction.name()};
    }

    /** Does this task belong to us at all (marked or corrupt)? Corrupt tasks must not be handed to native paths. */
    public static boolean ours(Decoded decoded) { return decoded.state() != State.NATIVE; }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
