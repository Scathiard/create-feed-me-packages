package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Reflective access to Create: Storage's worn-backpack handler. Reflection (not a compile dependency) is
 * deliberate: this mod must build and run without that mod present, and their internals may move - when they
 * do, every lookup here returns null and the compat layer quietly does nothing.
 *
 * <p>Both known shapes are attempted: 1.3.x ({@code BackpackContainer$Cache.getOrCreateWornBackpack}) and
 * 1.1.x ({@code new BackpackContainer(ItemStack, Player)}).
 */
final class FxntBackpack {
    private static final Map<Object, ItemStackHandler> BY_PLAYER = new WeakHashMap<>();
    private static final Class<?>[] NONE = new Class<?>[0];
    private FxntBackpack() {}

    /** The backpack item handler of a player, or null when they wear none / the shapes changed. */
    static synchronized ItemStackHandler handlerOf(Player player) {
        if (player == null) return null;
        if (BY_PLAYER.containsKey(player)) return BY_PLAYER.get(player);
        ItemStackHandler handler = resolve(player);
        BY_PLAYER.put(player, handler);
        return handler;
    }

    private static ItemStackHandler resolve(Player player) {
        try {
            ItemStack worn = equipped(player);
            if (worn == null || worn.isEmpty()) return null;
            try {   // 1.3.x
                Object container = call("net.fxnt.fxntstorage.backpack.inventory.BackpackContainer$Cache",
                        "getOrCreateWornBackpack", new Class<?>[]{Player.class, ItemStack.class}, player, worn);
                return handlerOf(container);
            } catch (Throwable newer) { /* fall through to the older shape */ }
            Object container = construct("net.fxnt.fxntstorage.backpack.main.BackpackContainer",
                    new Class<?>[]{ItemStack.class, Player.class}, worn, player);
            return handlerOf(container);
        } catch (Throwable shape) {
            CompatLog.once("handler-" + shape.getClass().getName(),
                    "FMP compat: degraded ({}), their transfer stays untouched", shape.getClass().getName());
            return null;
        }
    }

    private static ItemStack equipped(Player player) throws Exception {
        Class<?> helper = Class.forName("net.fxnt.fxntstorage.backpack.util.BackpackHelper");
        Method method = helper.getMethod("getEquippedBackpackStack", net.minecraft.world.entity.LivingEntity.class);
        return (ItemStack) method.invoke(null, player);
    }
    private static ItemStackHandler handlerOf(Object container) throws Exception {
        if (container == null) return null;
        Object handler = container.getClass().getMethod("getItemHandler").invoke(container);
        return handler instanceof ItemStackHandler stackHandler ? stackHandler : null;
    }
    private static Object call(String className, String method, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(className).getMethod(method, types).invoke(null, args);
    }
    private static Object construct(String className, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(className).getConstructor(types).newInstance(args);
    }
}
