package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.client.ClientMaterials;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side: the cache is only a view (the MaterialHints snapshot), so nothing is ever written from here -
 * the server owns the cache and debits it. Loaded on the client only (the helper picks the side first).
 */
final class ClientCacheSupply implements CacheSupply {
    @Override public List<Entry> available() {
        var entries = new ArrayList<Entry>();
        if (!ClientMaterials.active()) return entries;
        var stacks = ClientMaterials.craftingStacks();
        for (int index = 0; index < stacks.size(); index++) entries.add(new Entry(index, stacks.get(index)));
        return entries;
    }
    @Override public int take(int cell, int count) {
        // Deliberately nothing removed: a client-side write-back is a preview artefact, not a real removal.
        return 0;
    }
    @Override public String side() { return "client-view"; }
}