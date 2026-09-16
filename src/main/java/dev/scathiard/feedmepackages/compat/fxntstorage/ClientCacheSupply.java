package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.client.ClientMaterials;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Client side: the cache is only a view (the {@code MaterialHints} snapshot), so nothing is ever written
 * from here - the server owns the cache and debits it. Loaded on the client only (the helper picks the side
 * before touching this class).
 */
final class ClientCacheSupply implements CacheSupply {
    @Override public List<ItemStack> available() {
        return ClientMaterials.active() ? ClientMaterials.craftingStacks() : List.of();
    }
    @Override public void take(ItemStack prototype, int count) {
        // Deliberately empty: a client-side write-back is a preview artefact, not a real removal.
    }
    @Override public String side() { return "client-view"; }
}
