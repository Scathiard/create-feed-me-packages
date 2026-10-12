package dev.scathiard.feedmepackages.compat.tacz;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.Method;

/** Client-only quantities for the author's HUD and shot scheduling, never authoritative ammunition. */
public final class TaczClientSources {
    private TaczClientSources() {}
    private record Api(Class<?> gun, Class<?> ammo, Class<?> box, Method inventory, Method dummy,
                       Method ammoMatches, Method boxMatches, Method boxCount, Method creative, Method allCreative, Method needsAmmo) {}
    private static Api api;
    private static boolean attempted, failed;
    public static boolean ready() {
        if (!OptionalMixins.taczClientCompatible() || failed) return false;
        if (!attempted) {
            attempted = true;
            try {
                var gun = Class.forName("com.tacz.guns.api.item.IGun");
                var ammo = Class.forName("com.tacz.guns.api.item.IAmmo");
                var box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
                api = new Api(gun, ammo, box, gun.getMethod("useInventoryAmmo", ItemStack.class), gun.getMethod("useDummyAmmo", ItemStack.class),
                        ammo.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class), box.getMethod("isAmmoBoxOfGun", ItemStack.class, ItemStack.class),
                        box.getMethod("getAmmoCount", ItemStack.class), box.getMethod("isCreative", ItemStack.class), box.getMethod("isAllTypeCreative", ItemStack.class),
                        Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("needCheckAmmo"));
            } catch (ReflectiveOperationException | LinkageError e) { unavailable(e); }
        }
        return api != null && !failed;
    }
    private static void unavailable(Throwable cause) {
        if (!failed) FeedMePackages.LOGGER.warn("FMP TaCZ client quantity port disabled: public API failed", cause);
        failed = true;
    }
    /** Only the native HUD's local read receives this view; the real player inventory is never replaced. */
    public static Inventory hudSources(Inventory original, ItemStack gun) {
        if (!ready()) return original;
        var hints = TaczMaterials.clientHints(gun);
        if (hints.isEmpty()) return original;
        return new Inventory(original.player) {
            @Override public int getContainerSize() { return original.getContainerSize() + hints.size(); }
            @Override public ItemStack getItem(int slot) {
                int nativeSize = original.getContainerSize();
                return slot < nativeSize ? original.getItem(slot) : hints.get(slot - nativeSize).copy();
            }
        };
    }
    /** Native inventory-fed guns use the HUD's magazine proxy for shot count. Do not depend on HUD rendering. */
    public static int firingCount(ItemStack gun, int original) {
        if (!ready() || !api.gun.isInstance(gun.getItem())) return original;
        var hints = TaczMaterials.clientHints(gun);
        var player = Minecraft.getInstance().player;
        if (hints.isEmpty() || player == null) return original;
        try {
            if (!(boolean)api.inventory.invoke(gun.getItem(), gun) || (boolean)api.dummy.invoke(gun.getItem(), gun)) return original;
            // Use the author's public matching/box rules for ordinary sources; cache is already filtered loose ammo.
            long total = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                if (api.ammo.isInstance(stack.getItem()) && (boolean)api.ammoMatches.invoke(stack.getItem(), gun, stack)) total += stack.getCount();
                if (api.box.isInstance(stack.getItem()) && (boolean)api.boxMatches.invoke(stack.getItem(), gun, stack)) {
                    if (creativeBox(stack)) return 9999; // Native creative-box scheduling sentinel.
                    total += (int)api.boxCount.invoke(stack.getItem(), stack);
                }
            }
            for (var hint : hints) total += hint.getCount();
            return Math.max(original, (int)Math.clamp(total, 0, Integer.MAX_VALUE));
        } catch (ReflectiveOperationException | LinkageError e) { unavailable(e); return original; }
    }
    /** A material hint saturates at 4096 per variant; do not present that as an exact total. */
    public static String format(String nativeText, boolean currentCount) {
        if (!ready()) return nativeText;
        var player = Minecraft.getInstance().player;
        if (player == null || player.getAbilities().instabuild) return nativeText;
        var gun = player.getMainHandItem();
        if (gun.isEmpty() || !api.gun.isInstance(gun.getItem())) return nativeText;
        try {
            if (!(boolean)api.needsAmmo.invoke(player)) return nativeText;
            if ((boolean)api.dummy.invoke(gun.getItem(), gun) || (currentCount && !(boolean)api.inventory.invoke(gun.getItem(), gun))) return nativeText;
            var hints = TaczMaterials.clientHints(gun);
            if (hints.stream().noneMatch(stack -> stack.getCount() >= 4096)) return nativeText;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                var stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && api.box.isInstance(stack.getItem())
                        && (boolean)api.boxMatches.invoke(stack.getItem(), gun, stack) && creativeBox(stack)) return nativeText;
            }
            return "≥" + nativeText;
        } catch (ReflectiveOperationException | LinkageError e) { unavailable(e); return nativeText; }
    }
    private static boolean creativeBox(ItemStack stack) throws ReflectiveOperationException {
        return (boolean)api.creative.invoke(stack.getItem(), stack) || (boolean)api.allCreative.invoke(stack.getItem(), stack);
    }
}
