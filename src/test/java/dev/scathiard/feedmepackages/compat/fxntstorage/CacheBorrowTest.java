package dev.scathiard.feedmepackages.compat.fxntstorage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lending rules of the Create: Storage compat layer, pinned without a game: only the slots we are given
 * are lent, never more than the cache can give, and a hand-back debits exactly what was taken.
 */
class CacheBorrowTest {
    private static CacheBorrow.Supply supply(int key, int available, int maxStack) {
        return new CacheBorrow.Supply(key, available, maxStack);
    }

    @Test void onlyTheOfferedEmptySlotsAreLent() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(3, 5), List.of(supply(0, 64, 64), supply(1, 8, 64)));
        assertEquals(2, borrow.exposed());
        assertNotNull(borrow.lend(3));
        assertNotNull(borrow.lend(5));
        assertNull(borrow.lend(4), "a slot we were not offered must stay theirs");
        assertNull(borrow.lend(0));
    }

    @Test void weNeverPresentMoreThanTheCacheCanGiveOrTheItemCanStack() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0, 1), List.of(supply(0, 100, 16), supply(1, 3, 64)));
        assertEquals(16, borrow.lend(0).presented(), "clipped to the item's stack size");
        assertEquals(3, borrow.lend(1).presented(), "clipped to what the cache can give");
    }

    @Test void aSupplyShortageExposesFewerSlotsInsteadOfInventingMaterial() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0, 1, 2, 3), List.of(supply(0, 64, 64)));
        assertEquals(1, borrow.exposed());
        assertNotNull(borrow.lend(0));
        assertNull(borrow.lend(1));
        // ...and an empty supply lends nothing at all.
        var none = new CacheBorrow();
        none.plan(List.of(0, 1), List.of());
        assertEquals(0, none.exposed());
    }

    @Test void aHandBackDebitsExactlyWhatWasTaken() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(2), List.of(supply(0, 64, 64)));
        assertEquals(64, borrow.lend(2).presented());
        // They kept 4 and wrote back 60: exactly 4 items were taken.
        assertEquals(4, borrow.settle(2, 60).taken());
        assertNull(borrow.lend(2), "settling ends the lending for that slot");
        assertNull(borrow.settle(2, 60), "a second hand-back must not debit again");
    }

    @Test void aHandBackCanMeanNothingTaken() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(0), List.of(supply(0, 5, 64)));
        assertEquals(0, borrow.settle(0, 5).taken(), "an untouched stack takes nothing");
        var second = new CacheBorrow();
        second.plan(List.of(0), List.of(supply(0, 5, 64)));
        assertEquals(0, second.settle(0, 9).taken(), "a larger remainder is still nothing taken");
        var third = new CacheBorrow();
        third.plan(List.of(0), List.of(supply(0, 5, 64)));
        assertEquals(5, third.settle(0, 0).taken(), "an empty remainder took everything we presented");
        var negative = new CacheBorrow();
        negative.plan(List.of(0), List.of(supply(0, 5, 64)));
        assertEquals(5, negative.settle(0, -3).taken(), "a negative remainder cannot exceed what we presented");
    }

    @Test void theirOwnStackReturningEndsTheLending() {
        var borrow = new CacheBorrow();
        borrow.plan(List.of(1), List.of(supply(0, 64, 64)));
        borrow.forget(1);
        assertNull(borrow.lend(1));
        assertEquals(0, borrow.exposed());
    }
}
