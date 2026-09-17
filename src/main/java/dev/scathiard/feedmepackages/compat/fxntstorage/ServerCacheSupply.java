package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server side: the real cache, debited BY CELL INDEX through the same ledger replacement path the panel and
 * the recipe book use. No variant lookup, no silent early return: every branch that removes nothing says so
 * once, and {@link #take} returns exactly how many items the cache really lost.
 */
public final class ServerCacheSupply implements CacheSupply {
    private final ServerPlayer player;
    public ServerCacheSupply(ServerPlayer player) { this.player = player; }

    @Override public List<Entry> available() {
        var entries = new ArrayList<Entry>();
        var access = AccessGate.resolve(player);
        if (!access.active()) {
            skipped("cache not active");
            return entries;
        }
        var record = CacheLedger.get(player.getServer()).find(access.handle().cacheId());
        if (record == null) {
            skipped("no cache record");
            return entries;
        }
        var cells = record.state().cells();
        for (int index = 0; index < cells.size(); index++) {
            var cell = cells.get(index);
            if (cell.amount() <= 0 || cell.filter() == null) continue;
            int free = free(record, index);
            if (free <= 0) continue;
            entries.add(new Entry(index, cell.filter().stack(player.registryAccess(), free)));
        }
        return entries;
    }

    @Override public int take(int cell, int count) {
        if (count <= 0) return 0;
        var access = AccessGate.resolve(player);
        if (!access.active()) {
            skipped("cache not active");
            return 0;
        }
        var ledger = CacheLedger.get(player.getServer());
        var before = ledger.find(access.handle().cacheId());
        if (before == null) {
            skipped("no cache record");
            return 0;
        }
        if (cell < 0 || cell >= before.state().cells().size()) {
            skipped("cell " + cell + " out of range");
            return 0;
        }
        int move = Math.min(count, free(before, cell));
        if (move <= 0) {
            skipped("cell " + cell + " has nothing free (reserved or empty)");
            return 0;
        }
        var edit = before.state().edit();
        int removed = edit.extract(cell, move);
        if (removed <= 0) {
            skipped("cell " + cell + " refused the extract");
            return 0;
        }
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        return removed;
    }

    /** What the cell can give without touching another consumer's reservation. */
    private int free(dev.scathiard.feedmepackages.storage.CacheRecord record, int cell) {
        int amount = record.state().cells().get(cell).amount();
        int reserved = CraftingReservations.reservedCache(record.state().id(), cell, null);
        return Math.max(0, amount - reserved);
    }

    private void skipped(String reason) {
        CompatLog.once("debit-skipped:" + reason, "FMP compat: debit skipped ({})", reason);
    }

    @Override public String side() { return "server-cache"; }
}