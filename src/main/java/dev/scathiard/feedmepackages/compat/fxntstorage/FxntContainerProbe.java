package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * F-10 evidence + fix for the last symptom: their CLIENT copy of the worn backpack container goes stale and is
 * only reloaded when their backpack is re-equipped or their screen is opened, so their preview keeps promising
 * what the cache / backpack no longer holds.
 *
 * <p>Evidence: read THEIR cached container straight out of the player attachment (no creation, no re-context) and
 * compare it slot by slot against an independent container built by THEM from the very same worn stack - their
 * public constructor ends in {@code loadItemsFromStack}, the only place in their jar that calls it.
 *
 * <p>Fix, applied only when that comparison is stale: call {@code loadItemsFromStack} on THEIR cached instance -
 * their own public reload method - on the client thread, at most once per two seconds. It is a reload of existing
 * state: this class never calls {@code setStackInSlot} and never replaces an item instance (the F-8 prohibition),
 * and it never rewrites the worn stack itself.
 *
 * <p>Deliberately NOT used: {@code BackpackContainer$Cache.getOrCreateWornBackpack} - bytecode shows it hands back
 * the cached instance itself after calling {@code setContext} on it (and, client-side, replaces the attachment
 * when the upgrades do not match), so it can neither observe nor produce a fresh container.
 */
public final class FxntContainerProbe {
    private static final String ATTACHMENT_TYPES = "net.fxnt.fxntstorage.init.ModAttachmentTypes";
    private static final String ATTACHMENT_FIELD = "WORN_BACKPACK_CONTAINER";
    private static final String CONTAINER = "net.fxnt.fxntstorage.backpack.inventory.BackpackContainer";
    private static final String LEGACY_CONTAINER = "net.fxnt.fxntstorage.backpack.main.BackpackContainer";
    private static final String RELOAD = "loadItemsFromStack";
    private static final long RELOAD_INTERVAL_MS = 2000L;

    private static String lastLine;
    private static long lastAt;
    private static long lastReloadAt;

    private FxntContainerProbe() {}

    public static void inspectAndRefresh(Player player) {
        try {
            if (player == null) return;
            ItemStack worn = wornStack(player);
            if (worn == null || worn.isEmpty()) return;
            Object cached = cachedContainer(player);
            if (cached == null) return;
            ItemStackHandler live = handler(cached);
            ItemStackHandler loaded = handler(freshContainer(player, worn.copy()));
            if (live == null || loaded == null) return;

            int stale = 0;
            int slots = Math.min(live.getSlots(), loaded.getSlots());
            for (int index = 0; index < slots; index++) {
                ItemStack held = live.getStackInSlot(index);
                ItemStack fresh = loaded.getStackInSlot(index);
                if (!ItemStack.isSameItemSameComponents(held, fresh) || held.getCount() != fresh.getCount()) stale++;
            }
            long now = System.currentTimeMillis();
            String line = "FMP compat: their container fresh=" + (stale == 0) + " stale=" + stale + " slots=" + slots;
            if (PreviewDiagnostic.shouldPrint(line, now, lastLine, lastAt)) {
                lastLine = line;
                lastAt = now;
                CompatLog.compat(line);
            }
            if (stale == 0 || now - lastReloadAt < RELOAD_INTERVAL_MS) return;
            lastReloadAt = now;
            cached.getClass().getMethod(RELOAD, ItemStack.class).invoke(cached, worn.copy());
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

    /** THEIR cached container, read straight from the attachment: nothing is created, re-contexted or saved. */
    private static Object cachedContainer(Player player) {
        try {
            Field field = Class.forName(ATTACHMENT_TYPES).getField(ATTACHMENT_FIELD);
            Object supplier = field.get(null);
            if (supplier == null) return null;
            Method getData = Player.class.getMethod("getData", Supplier.class);
            return getData.invoke(player, supplier);
        } catch (Throwable unreadable) {
            CompatLog.once("worn-container-unreadable-" + unreadable.getClass().getName(),
                    "FMP compat: degraded (worn container not readable: {})", unreadable.getClass().getName());
            return null;
        }
    }

    /** An INDEPENDENT container built by THEM from the same worn stack; their constructor loads it from the item. */
    private static Object freshContainer(Player player, ItemStack stack) {
        try {
            return Class.forName(CONTAINER).getConstructor(Player.class, ItemStack.class).newInstance(player, stack);
        } catch (Throwable newer) {
            try {
                return Class.forName(LEGACY_CONTAINER).getConstructor(ItemStack.class, Player.class).newInstance(stack, player);
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
