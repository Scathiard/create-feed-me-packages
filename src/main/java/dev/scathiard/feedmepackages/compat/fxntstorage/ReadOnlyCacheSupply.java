package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.client.ClientMaterials;

import java.util.ArrayList;
import java.util.List;

/** Client presenter supply: a read-only cache view. Any write is refused out loud - it must never be the side. */
final class ReadOnlyCacheSupply implements CacheSupply {
    @Override public List<Entry> available() {
        var entries = new ArrayList<Entry>();
        if (!ClientMaterials.active()) return entries;
        var stacks = ClientMaterials.stacks();
        for (int index = 0; index < stacks.size(); index++) entries.add(new Entry(index, stacks.get(index)));
        return entries;
    }
    @Override public int take(int cell, int count) {
        CompatLog.once("debit-refused:readonly", "FMP compat: debit refused (read-only client presenter)");
        return 0;
    }
    @Override public String side() { return "client-view"; }
}