package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.server.level.ServerPlayer;

/**
 * F-7 approved fallback, F-8 REVERTED: after THEIR transfer finished we only ask vanilla to re-send the two
 * menus the player is looking at. Only existing state is broadcast.
 *
 * <p>The former Curios branch (walking Curios' slot handlers and re-stacking every slot with a copy of itself)
 * has been REMOVED: although the data looked identical, it replaced the ITEM INSTANCES of another mod's
 * container, and fxntstorage caches the worn-backpack container per instance - so their own withdrawal broke.
 * Rewriting somebody else's item slot with a copy is henceforth a forbidden technique in this project.
 */
public final class FxntResync {
    private FxntResync() {}

    public static void afterPlacement(ServerPlayer player) {
        if (player == null) return;
        try {
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            CompatLog.once("resync-menu-ok", "FMP compat: resync after their placement (menu + inventory broadcast)");
        } catch (Throwable failure) {
            CompatLog.once("resync-menu-" + failure.getClass().getName(), "FMP compat: resync degraded ({})", failure.getClass().getName());
        }
    }
}