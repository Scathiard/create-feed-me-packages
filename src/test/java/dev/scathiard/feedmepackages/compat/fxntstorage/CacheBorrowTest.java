package dev.scathiard.feedmepackages.compat.fxntstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lending rules of the Create: Storage compat layer, pinned without a game. F-3 is the rule this file
 * exists for: the borrow set is filtered by the RECIPE's materials and computed by ONE shared function, so
 * the client preview and the server placement cannot disagree.
 */
class CacheBorrowTest {
    private static CacheBorrow.Supply supply(int key, int cell, int available, int maxStack) {
        return new CacheBorrow.Supply(key, cell, available, maxStack);
    }
    private static final IntPredicate ANY = key -> true;

    @Test void onlyCellsTheRecipeAcceptsAreBorrowed() {
        var borrow = new CacheBorrow();
        // Cell 0 holds iron ingots (accepted, key 1), cell 1 holds logs (not accepted, key 0).
        borrow.plan(List.of(0, 1, 2), List.of(supply(0, 1, 64, 64), supply(1, 0, 12, 64)), key -> key == 1);
        assertEquals(1, borrow.exposed(), "only the accepted cell may be lent");
        assertEquals(0, borrow.lend(0).cell(), "the accepted cell's index must be the one we debit later");
        assertNull(borrow.lend(1), "the rejected cell must not be presented anywhere");
    }

    @Test void nothingIsBorrowedWithoutMaterials() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0, 1), List.of(supply(0, 0, 64, 64)), key -> false);
        assertEquals(0, borrow.exposed(), "an empty filter lends nothing (their own behaviour is preserved)");
    }

    @Test void bothSidesComputeTheSameBorrowSetFromTheSameInput() {
        var supplies = List.of(supply(0, 3, 64, 64), supply(1, 7, 12, 16));
        var slots = List.of(2, 4, 5);
        IntPredicate accepted = key -> key == 1;
        var client = new CacheBorrow();
        var server = new CacheBorrow();
        client.plan(slots, supplies, accepted);
        server.plan(slots, supplies, accepted);
        assertEquals(client.exposed(), server.exposed());
        for (int slot : slots) {
            var a = client.lend(slot);
            var b = server.lend(slot);
            assertEquals(a == null, b == null, "slot " + slot + " must be decided identically");
            if (a != null) {
                assertEquals(a.cell(), b.cell());
                assertEquals(a.presented(), b.presented());
            }
        }
    }

    @Test void aSupplyShortageExposesFewerSlots() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0, 1, 2, 3), List.of(supply(0, 0, 64, 64)), ANY);
        assertEquals(1, borrow.exposed());
        assertNull(borrow.lend(1));
    }

    @Test void weNeverPresentMoreThanTheCacheCanGiveOrTheItemCanStack() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0, 1), List.of(supply(0, 0, 100, 16), supply(1, 1, 3, 64)), ANY);
        assertEquals(16, borrow.lend(0).presented());
        assertEquals(3, borrow.lend(1).presented());
    }

    @Test void aHandBackDebitsExactlyWhatWasTakenFromThatCell() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(2), List.of(supply(0, 5, 64, 64)), ANY);
        var settled = borrow.settle(2, 60);
        assertEquals(4, settled.taken(), "presented - remaining is the amount really taken");
        assertEquals(5, settled.cell(), "the debit must name the same cell we presented");
        assertNull(borrow.settle(2, 60), "a second hand-back must not debit again");
    }

    @Test void anExtractKeepsTheLendingForTheRest() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0), List.of(supply(0, 9, 10, 64)), ANY);
        assertEquals(3, borrow.use(0, 3).taken());
        assertEquals(7, borrow.lend(0).presented());
        assertEquals(7, borrow.use(0, 100).taken());
        assertNull(borrow.lend(0), "an exhausted lending is over");
    }
}