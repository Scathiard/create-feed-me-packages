package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * F-11, READ-ONLY: the contents of the worn backpack, for the diagnostic line only.
 *
 * <p>Nothing of the other mod's state is taken, constructed, reloaded or written. The single contact is their
 * pure static getter that names the worn stack ({@code BackpackHelper.getEquippedBackpackStack}); the contents
 * are then read from THAT ITEM's own {@code DataComponents.CONTAINER} component with vanilla API, which is the
 * authority anyway. Their container object is never touched: no {@code getOrCreateWornBackpack} (it hands back
 * the cached instance after {@code setContext}, and client-side may replace the attachment), no constructor, no
 * {@code getItemHandler}, no {@code loadItemsFromStack}, no slot write.
 *
 * <p>This is the F-11 rollback: F-10's "align their cached container with the authority" is gone for good - the
 * user's run showed it makes their own refresh worse ("更坏了，原本开关背包还能刷新的，现在都刷新不了了").
 */
final class FxntBackpack {
    private FxntBackpack() {}

    /** The worn backpack's contents as the item itself declares them; empty when they wear none. */
    static List<ItemStack> wornContents(Player player) {
        var contents = new ArrayList<ItemStack>();
        try {
            ItemStack worn = equipped(player);
            if (worn == null || worn.isEmpty()) return contents;
            ItemContainerContents component = worn.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            for (int index = 0; index < component.getSlots(); index++) contents.add(component.getStackInSlot(index));
        } catch (Throwable shape) {
            CompatLog.once("worn-contents-" + shape.getClass().getName(),
                    "FMP compat: degraded ({}), their transfer stays untouched", shape.getClass().getName());
        }
        return contents;
    }

    private static ItemStack equipped(Player player) throws Exception {
        Class<?> helper = Class.forName("net.fxnt.fxntstorage.backpack.util.BackpackHelper");
        Method method = helper.getMethod("getEquippedBackpackStack", net.minecraft.world.entity.LivingEntity.class);
        return (ItemStack) method.invoke(null, player);
    }
}
