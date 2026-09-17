package dev.scathiard.feedmepackages.compat.fxntstorage;

import org.junit.jupiter.api.Test;

import static dev.scathiard.feedmepackages.compat.fxntstorage.SideSelect.Side.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-4's hard gate: the side must not be decided by dist. In SINGLE PLAYER dist is CLIENT even while the
 * integrated server thread runs their placement, so the only correct answer there is SERVER_CACHE - the old
 * dist-based rule returned CLIENT_VIEW and every debit silently became a no-op.
 */
class SideSelectTest {
    @Test void singlePlayerServerThreadUsesTheServerCacheEvenThoughDistIsClient() {
        // This is the exact combination that failed on the user's machine.
        assertEquals(SERVER_CACHE, SideSelect.choose(true, true, true));
        assertEquals(CLIENT_VIEW, SideSelect.choose(true, true, false), "without a matched owner there is nothing to debit");
    }

    @Test void dedicatedServerUsesTheServerCache() {
        assertEquals(SERVER_CACHE, SideSelect.choose(false, true, true));
        assertEquals(SERVER_CACHE, SideSelect.choose(false, false, true));
    }

    @Test void aHandlerOfOursIsNeverServedFromTheClientViewWhenTheServerThreadOwnsTheCall() {
        for (boolean distIsClient : new boolean[]{true, false}) {
            assertNotEquals(CLIENT_VIEW, SideSelect.choose(distIsClient, true, true));
        }
    }

    @Test void clientThreadOrUnknownOwnerFallsBackToTheView() {
        // Single player, CLIENT thread, handler not matched to a server player (e.g. a preview): view only.
        assertEquals(CLIENT_VIEW, SideSelect.choose(true, false, false));
        assertEquals(CLIENT_VIEW, SideSelect.choose(true, false, true), "never read the ledger off the server thread");
        assertEquals(CLIENT_VIEW, SideSelect.choose(false, false, false));
    }

    @Test void theWholeTable() {
        for (boolean distIsClient : new boolean[]{true, false}) {
            for (boolean onServerThread : new boolean[]{true, false}) {
                for (boolean matches : new boolean[]{true, false}) {
                    var expected = (matches && (onServerThread || !distIsClient)) ? SERVER_CACHE : CLIENT_VIEW;
                    assertEquals(expected, SideSelect.choose(distIsClient, onServerThread, matches),
                            distIsClient + "/" + onServerThread + "/" + matches);
                }
            }
        }
    }
}