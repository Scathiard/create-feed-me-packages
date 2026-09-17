package dev.scathiard.feedmepackages.compat.fxntstorage;

/**
 * F-4: which cache view a call may use. This is a PURE function on purpose - the previous build asked
 * {@code FMLEnvironment.dist.isClient()}, which is TRUE in single player even on the integrated server's
 * thread, so the server-side placement borrowed the client view and its no-op take made every debit vanish.
 *
 * <p>Rules: a handler that belongs to one of OUR server players is never served from the client view; the
 * server cache is only read on the server thread (never cross-thread from client code).
 */
public final class SideSelect {
    public enum Side { SERVER_CACHE, CLIENT_VIEW }
    private SideSelect() {}

    /**
     * @param distIsClient            {@code FMLEnvironment.dist.isClient()} (true in single player!)
     * @param onServerThread          the current server exists and we are running on its thread
     * @param matchesServerPlayer     the handler is the worn-backpack handler of one of our server players
     */
    public static Side choose(boolean distIsClient, boolean onServerThread, boolean matchesServerPlayer) {
        if (matchesServerPlayer && (onServerThread || !distIsClient)) return Side.SERVER_CACHE;
        return Side.CLIENT_VIEW;
    }
}