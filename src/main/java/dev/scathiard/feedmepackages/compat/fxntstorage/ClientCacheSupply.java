package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.client.ClientMaterials;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side: the cache is only a view (the MaterialHints snapshot). It must never be the side that serves a
 * server-side placement - if a debit reaches here it says so loudly, because that is exactly the F-4 failure
 * (single player: dist is CLIENT, so the side used to be picked wrongly and nothing was ever debited).
 */
final class ClientCacheSupply implements CacheSupply {
    @Override public List<Entry> available() {
        var entries = new ArrayList<Entry>();
        if (!ClientMaterials.active()) return entries;
        // F-5: the SERVER-side ceiling is amount - reserved, so the client must not add its own reservations
        // back (craftingStacks() did, which made the preview more optimistic than the server).
        var stacks = ClientMaterials.stacks();
        for (int index = 0; index < stacks.size(); index++) entries.add(new Entry(index, stacks.get(index)));
        return entries;
    }
    @Override public int take(int cell, int count) {
        CompatLog.once("debit-refused:client", "FMP compat: debit refused (client view cannot write; server side not selected)");
        return 0;
    }
    @Override public String side() { return "client-view"; }
}