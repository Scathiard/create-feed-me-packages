package dev.scathiard.feedmepackages.compat.fxntstorage;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server side: the real cache, debited through the SAME ledger replacement path the panel and the recipe
 * book use ({@code CacheEdit.extract} + {@code ledger.replace}) - no second storage, no shortcut around
 * reservations. Only what reservations leave free is ever offered.
 */
final class ServerCacheSupply implements CacheSupply {
    private final ServerPlayer player;
    ServerCacheSupply(ServerPlayer player) { this.player = player; }

    @Override public List<ItemStack> available() {
        var access = AccessGate.resolve(player);
        if (!access.active()) return List.of();
        var record = CacheLedger.get(player.getServer()).find(access.handle().cacheId());
        if (record == null) return List.of();
        var cells = record.state().cells();
        var stacks = new ArrayList<ItemStack>();
        for (int index = 0; index < cells.size(); index++) {
            var cell = cells.get(index);
            if (cell.amount() <= 0 || cell.filter() == null) continue;
            int free = Math.max(0, cell.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), index, null));
            if (free <= 0) continue;
            stacks.add(cell.filter().stack(player.registryAccess(), free));
        }
        return stacks;
    }

    @Override public void take(ItemStack prototype, int count) {
        if (count <= 0 || prototype.isEmpty()) return;
        var access = AccessGate.resolve(player);
        if (!access.active()) return;
        var ledger = CacheLedger.get(player.getServer());
        var before = ledger.find(access.handle().cacheId());
        if (before == null) return;
        ItemVariantKey variant;
        try {
            variant = ItemVariantKey.of(prototype, player.registryAccess());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        int slot = before.state().find(variant);
        if (slot < 0) return;
        int free = Math.max(0, before.state().cells().get(slot).amount()
                - CraftingReservations.reservedCache(access.handle().cacheId(), slot, null));
        int move = Math.min(Math.min(count, prototype.getCount()), free);
        if (move <= 0) return;
        var edit = before.state().edit();
        if (edit.extract(slot, move) <= 0) return;
        var replacement = before.withState(edit.finish());
        ledger.replace(access.handle(), before.state().revision(), replacement);
        CompatLog.compat("FMP compat: debited " + move + " x " + variant + " from the cache of " + player.getGameProfile().getName());
    }

    @Override public String side() { return "server-cache"; }
}
