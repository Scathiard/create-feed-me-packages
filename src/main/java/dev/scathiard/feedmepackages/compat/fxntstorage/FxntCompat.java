package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.minecraft.world.entity.player.Inventory;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * The seam between Create: Storage's own transfer calls and our cache. Ordinary code (the mixins live in
 * their own package, because Mixin must never load helpers from the declared mixin package). It is called for
 * ONE of their calls and returns a wrapper that exists only in that call's local/argument.
 */
public final class FxntCompat {
    private static final String FXNT = "fxntstorage";
    private static final int[] NO_RANGE = null;
    private FxntCompat() {}

    /** Server side: present through the vanilla Inventory the call already carries (owner = its player). */
    public static Inventory presentInventory(Inventory original, String owner) {
        try {
            if (original == null) {
                CompatLog.once("pass-null", "FMP compat: pass-through (no inventory at this call site)");
                return null;
            }
            if (!ModList.get().isLoaded(FXNT)) {
                CompatLog.once("pass-absent", "FMP compat: pass-through (their mod is not loaded)");
                return original;
            }
            ServerPlayer player = original.player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            if (player == null) {
                CompatLog.once("no-owner", "FMP compat: degraded (the inventory carries no server player)");
                return original;
            }
            var supply = new ServerCacheSupply(player);
            var entries = supply.available();
            if (entries.isEmpty()) {
                CompatLog.once("no-cache-" + supply.side(), "FMP compat: degraded (no cache view: nothing available)");
                return original;
            }
            return new CachePresentingInventory(player, supply, entries, FxntContext.materials(), owner, false);
        } catch (Throwable shape) {
            CompatLog.once("present-" + shape.getClass().getName(),
                    "FMP compat: degraded ({}), their transfer stays untouched", shape.getClass().getName());
            return original;
        }
    }

    /** Client side: the same rule with a read-only view - it presents, it never debits and never writes. */
    public static Inventory presentInventoryReadOnly(Inventory original, String owner) {
        try {
            if (original == null || !ModList.get().isLoaded(FXNT)) {
                CompatLog.once("pass-absent", "FMP compat: pass-through (their mod is not loaded)");
                return original;
            }
            var player = original.player;
            var supply = new ReadOnlyCacheSupply();
            var entries = supply.available();
            if (player == null || entries.isEmpty()) {
                CompatLog.once("no-cache-" + supply.side(), "FMP compat: degraded (no cache view: nothing available)");
                return original;
            }
            return new CachePresentingInventory(player, supply, entries, FxntContext.materials(), owner, true);
        } catch (Throwable shape) {
            CompatLog.once("present-" + shape.getClass().getName(),
                    "FMP compat: degraded ({}), their transfer stays untouched", shape.getClass().getName());
            return original;
        }
    }
}
