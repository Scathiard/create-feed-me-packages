package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
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

    /** Wrap their modifiable handler (the client preview local, their placement argument). */
    public static IItemHandlerModifiable presentModifiable(IItemHandlerModifiable original, String owner) {
        CachePresentingHandler wrapper = wrap(original, owner);
        return wrapper == null ? original : wrapper;
    }

    /** Wrap their read-only handler view (their max-craftable count argument). */
    public static IItemHandler presentSlots(IItemHandler original, String owner) {
        CachePresentingHandler wrapper = wrap(original, owner);
        return wrapper == null ? original : wrapper;
    }

    private static CachePresentingHandler wrap(IItemHandler original, String owner) {
        try {
            if (original == null) {
                CompatLog.once("pass-null", "FMP compat: pass-through (no handler at this call site)");
                return null;
            }
            if (!ModList.get().isLoaded(FXNT)) {
                CompatLog.once("pass-absent", "FMP compat: pass-through (their mod is not loaded)");
                return null;
            }
            int[] range = itemSlots(original);
            if (range == NO_RANGE) {
                CompatLog.once("slots-unknown", "FMP compat: degraded (slot layout unknown)");
                return null;
            }
            CacheSupply supply = supplyFor(original);
            if (supply == null) {
                CompatLog.once("no-owner", "FMP compat: degraded (no cache view: unknown owner)");
                return null;
            }
            List<CacheSupply.Entry> entries = supply.available();
            if (entries.isEmpty()) {
                CompatLog.once("no-cache-" + supply.side(), "FMP compat: degraded (no cache view: nothing available)");
                return null;
            }
            return new CachePresentingHandler(original, supply, entries, FxntContext.materials(), range[0], range[1], owner);
        } catch (Throwable shape) {
            CompatLog.once("present-" + shape.getClass().getName(),
                    "FMP compat: degraded ({}), their transfer stays untouched", shape.getClass().getName());
            return null;
        }
    }

    /**
     * The cache view of the side this call may use. The choice is {@link SideSelect}'s pure rule: a handler
     * belonging to one of our server players is served from the server cache, and the server cache is only
     * touched on the server thread - in single player dist is CLIENT, so dist alone must never decide this.
     */
    private static CacheSupply supplyFor(IItemHandler original) {
        boolean distIsClient = FMLEnvironment.dist.isClient();
        var server = ServerLifecycleHooks.getCurrentServer();
        boolean onServerThread = server != null && server.isSameThread();
        ServerPlayer owner = null;
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (FxntBackpack.handlerOf(player) == original) { owner = player; break; }
            }
        }
        var side = SideSelect.choose(distIsClient, onServerThread, owner != null);
        if (side == SideSelect.Side.SERVER_CACHE) {
            if (owner == null) return null;                       // cannot happen by rule; never fall back to the view
            return new ServerCacheSupply(owner);
        }
        return new ClientCacheSupply();
    }

    /** Their item slots as [first, lastExclusive]; null when the shape is unknown (then we do not lend). */
    private static int[] itemSlots(IItemHandler original) {
        int first;
        int last;
        try {   // 1.3.x: BackpackSlotLayout.createLayout().items() -> SlotSection(startIndex, count)
            Object layout = Class.forName("net.fxnt.fxntstorage.backpack.inventory.BackpackSlotLayout")
                    .getMethod("createLayout").invoke(null);
            Object section = layout.getClass().getMethod("items").invoke(layout);
            first = (Integer) section.getClass().getMethod("getStartIndex").invoke(section);
            last = first + (Integer) section.getClass().getMethod("getCount").invoke(section);
        } catch (Throwable newer) {
            try {   // 1.1.x: public constants on Util
                Class<?> util = Class.forName("net.fxnt.fxntstorage.util.Util");
                first = (Integer) util.getField("ITEM_SLOT_START_RANGE").get(null);
                last = (Integer) util.getField("ITEM_SLOT_END_RANGE").get(null);
            } catch (Throwable older) {
                return NO_RANGE;
            }
        }
        int size = original.getSlots();
        first = Math.max(0, first);
        last = Math.min(last, size);
        return last > first ? new int[]{first, last} : NO_RANGE;
    }

    /** Their empty lendable slots, in order (their occupied slots are never borrowed). */
    static List<Integer> emptySlots(IItemHandler handler, int first, int last) {
        var slots = new ArrayList<Integer>();
        for (int index = Math.max(0, first); index < Math.min(last, handler.getSlots()); index++) {
            if (handler.getStackInSlot(index).isEmpty()) slots.add(index);
        }
        return slots;
    }
}