package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * F-5 diagnostic, CLIENT preview path only: for each material of the recipe this call is about, say how much
 * the player inventory, their worn backpack and our lent cache each hold. The point is to name the source that
 * is lying when the button promises something the server cannot deliver. Rate limited by content: the same
 * recipe with the same numbers logs once.
 */
public final class PreviewDiagnostic {
    private PreviewDiagnostic() {}

    public static void report(Player player, RecipeHolder<CraftingRecipe> recipe) {
        try {
            if (player == null || recipe == null || recipe.value() == null) return;
            var inventory = new ArrayList<ItemStack>(player.getInventory().items);
            // Their own client path reads the WORN backpack stack (BackpackHelper.getEquippedBackpackStack), and
            // handlerOf(...) resolves exactly that stack, so this is the same container their preview sees.
            var backpack = new ArrayList<ItemStack>();
            var worn = FxntBackpack.handlerOf(player);
            if (worn != null) for (int index = 0; index < worn.getSlots(); index++) backpack.add(worn.getStackInSlot(index));
            var lent = new ArrayList<ItemStack>();
            for (var entry : new ClientCacheSupply().available()) lent.add(entry.stack());

            var text = new StringBuilder("FMP compat: preview recipe=").append(recipe.id());
            for (Ingredient ingredient : recipe.value().getIngredients()) {
                text.append(" | ").append(name(ingredient))
                        .append(": inventory=").append(PreviewCounts.sum(inventory, ingredient::test, ItemStack::getCount))
                        .append(" backpack=").append(PreviewCounts.sum(backpack, ingredient::test, ItemStack::getCount))
                        .append(" lent=").append(PreviewCounts.sum(lent, ingredient::test, ItemStack::getCount));
            }
            CompatLog.once("preview:" + text, "%s", text);
        } catch (Throwable ignored) {
            // Diagnostics must never disturb their preview.
        }
    }

    private static String name(Ingredient ingredient) {
        try {
            var items = ingredient.getItems();
            return items.length == 0 ? "?" : items[0].getItem().toString();
        } catch (Throwable missing) {
            return "?";
        }
    }
}