package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * F-7/F-8 approved fallback: after THEIR transfer finished, ask vanilla and Curios to re-send the containers
 * the client is looking at. Only existing state is broadcast: nothing is created, moved or written by us.
 *
 * <p>Curios API (verified with javap on curios-neoforge 9.5.1+1.21.1):
 * {@code CuriosApi.getCuriosInventory(LivingEntity) -> Optional<ICuriosItemHandler>},
 * {@code ICuriosItemHandler.getCurios() -> Map<String, ICurioStacksHandler>},
 * {@code ICurioStacksHandler.getStacks() -> IDynamicStackHandler} (an {@code IItemHandlerModifiable}). The
 * slot handler is marked changed through its own API when available, otherwise each slot is re-stacked with a
 * copy of itself - which re-sends the slot without altering a single item.
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
        resyncCurios(player);
    }

    /** The worn backpack lives in a Curios slot; its client copy is refreshed through Curios' own API. */
    private static void resyncCurios(ServerPlayer player) {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object optional = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
            if (!(optional instanceof Optional<?> inventory) || inventory.isEmpty()) {
                CompatLog.once("resync-curios-none", "FMP compat: resync curios skipped (no curios inventory)");
                return;
            }
            Object handler = inventory.get();
            Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
            if (!(curios instanceof Map<?, ?> map)) return;
            int slots = 0;
            String via = "none";
            for (Object stacksHandler : map.values()) {
                if (stacksHandler == null) continue;
                Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                if (stacks == null) continue;
                Method changed = null;
                try { changed = stacks.getClass().getMethod("setChanged"); } catch (NoSuchMethodException absent) { changed = null; }
                if (changed != null) {
                    changed.invoke(stacks);
                    via = "setChanged";
                } else {
                    Method size = stacks.getClass().getMethod("getSlots");
                    Method get = stacks.getClass().getMethod("getStackInSlot", int.class);
                    Method set = stacks.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
                    int count = (Integer) size.invoke(stacks);
                    for (int index = 0; index < count; index++) {
                        ItemStack current = (ItemStack) get.invoke(stacks, index);
                        set.invoke(stacks, index, current.copy());
                    }
                    via = "restack";
                    slots += count;
                }
            }
            CompatLog.once("resync-curios-ok:" + via, "FMP compat: resync curios ok via={} slots={}", via, slots);
        } catch (Throwable failure) {
            CompatLog.once("resync-curios-" + failure.getClass().getName(),
                    "FMP compat: resync degraded for curios ({})", failure.getClass().getName());
        }
    }
}