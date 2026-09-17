package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * F-7 approved fallback: after THEIR transfer finished, ask vanilla (and Curios, through its own API) to
 * re-sync the containers the client is looking at, so the client view stops being optimistic without the
 * player having to reopen a screen. It only broadcasts existing state: no item is created, moved or written by
 * us, and the call is rate limited to once per transfer (their handle is called once per placement).
 */
public final class FxntResync {
    private FxntResync() {}

    public static void afterPlacement(ServerPlayer player) {
        if (player == null) return;
        try {
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            compatLog("FMP compat: resync after their placement (menu + inventory broadcast)");
        } catch (Throwable failure) {
            CompatLog.once("resync-menu-" + failure.getClass().getName(), "FMP compat: resync degraded ({})", failure.getClass().getName());
        }
        resyncCurios(player);
    }

    /** The worn backpack lives in a Curios slot, so its client copy is refreshed through Curios' own API. */
    private static void resyncCurios(ServerPlayer player) {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object optional = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (!(optional instanceof java.util.Optional<?> inventory) || inventory.isEmpty()) return;
            Object handler = inventory.get();
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            if (!(curios instanceof java.util.Map<?, ?> map)) return;
            boolean touched = false;
            for (Object stacksHandler : map.values()) {
                Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                stacks.getClass().getMethod("setChanged").invoke(stacks);
                touched = true;
            }
            compatLog("FMP compat: resync after their placement (curios slots=" + map.size() + ", touched=" + touched + ")");
        } catch (Throwable failure) {
            CompatLog.once("resync-curios-" + failure.getClass().getName(),
                    "FMP compat: resync degraded for curios ({})", failure.getClass().getName());
        }
    }

    private static void compatLog(String message) {
        CompatLog.compat(message);
    }
}