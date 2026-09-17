package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.Method;

/**
 * F-10 evidence + fix for the last symptom: their CLIENT copy of the worn backpack container goes stale and is
 * only reloaded when their backpack screen is opened, so their preview keeps promising what the cache/backpack
 * no longer holds.
 *
 * <p>Evidence: compare their cached container slot by slot against a container freshly loaded from the very
 * same worn stack (their own {@code loadItemsFromStack}). Fix, used only when that comparison is stale: call
 * {@code loadItemsFromStack} on THEIR container - their own public reload method - on the client thread, at
 * most once per preview. It is a reload of existing state: this class never calls {@code setStackInSlot} and
 * never replaces an item instance (the F-8 prohibition).
 */
public final class FxntContainerProbe {
    private static String lastLine;
    private static long lastAt;
    private static long lastReloadAt;
    private FxntContainerProbe() {}

    public static void inspectAndRefresh(Player player) {
        try {
            if (player == null) return;
            ItemStack worn = wornStack(player);
            if (worn == null || worn.isEmpty()) return;
            Object cached = container(player, worn);
            if (cached == null) return;
            ItemStackHandler live = handler(cached);
            ItemStackHandler fresh = handler(container(player, worn.copy()));
            if (live == null || fresh == null) return;

            int stale = 0;
            int slots = Math.min(live.getSlots(), fresh.getSlots());
            for (int index = 0; index < slots; index++) {
                ItemStack a = live.getStackInSlot(index);
                ItemStack b = fresh.getStackInSlot(index);
                if (!ItemStack.isSameItemSameComponents(a, b) || a.getCount() != b.getCount()) stale++;
            }
            String line = "FMP compat: their container fresh=" + (stale == 0) + " stale=" + stale + " slots=" + slots;
            long now = System.currentTimeMillis();
            if (PreviewDiagnostic.shouldPrint(line, now, lastLine, lastAt)) {
                lastLine = line; lastAt = now; CompatLog.compat(line);
            }
            if (stale == 0 || now - lastReloadAt < 2000) return;
            lastReloadAt = now;
            Method reload = cached.getClass().getMethod("loadItemsFromStack", ItemStack.class);
            reload.invoke(cached, worn.copy());
            CompatLog.compat("FMP compat: their container refreshed via loadItemsFromStack (stale=" + stale + ")");
        } catch (Throwable failure) {
            CompatLog.once("container-probe-" + failure.getClass().getName(),
                    "FMP compat: degraded (container probe: {})", failure.getClass().getName());
        }
    }

    private static ItemStack wornStack(Player player) throws Exception {
        Class<?> helper = Class.forName("net.fxnt.fxntstorage.backpack.util.BackpackHelper");
        return (ItemStack) helper.getMethod("getEquippedBackpackStack", net.minecraft.world.entity.LivingEntity.class).invoke(null, player);
    }
    private static Object container(Player player, ItemStack stack) {
        try {
            Class<?> cache = Class.forName("net.fxnt.fxntstorage.backpack.inventory.BackpackContainer$Cache");
            return cache.getMethod("getOrCreateWornBackpack", Player.class, ItemStack.class).invoke(null, player, stack);
        } catch (Throwable newer) {
            try {
                Class<?> legacy = Class.forName("net.fxnt.fxntstorage.backpack.main.BackpackContainer");
                return legacy.getConstructor(ItemStack.class, Player.class).newInstance(stack, player);
            } catch (Throwable older) {
                return null;
            }
        }
    }
    private static ItemStackHandler handler(Object container) {
        try {
            Object handler = container.getClass().getMethod("getItemHandler").invoke(container);
            return handler instanceof ItemStackHandler stackHandler ? stackHandler : null;
        } catch (Throwable missing) {
            return null;
        }
    }
}