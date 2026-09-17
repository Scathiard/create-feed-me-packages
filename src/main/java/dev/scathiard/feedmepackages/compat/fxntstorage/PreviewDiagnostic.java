package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;

/**
 * F-5/F-7 diagnostic, CLIENT preview path: for each material of this recipe, say how much the player
 * inventory, their worn backpack and our lent cache each hold, so the log NAMES the source that is lying.
 * It is evaluated on every entry into the presenter and printed only when the numbers change, at most once
 * every two seconds (a per-tick preview cannot flood the log but a real change always shows).
 */
public final class PreviewDiagnostic {
    private static String lastLine;
    private static String lastHintLine;
    private static long lastPrintedAt;
    private static long lastHintAt;

    private PreviewDiagnostic() {}

    public static void report(Player player, RecipeHolder<CraftingRecipe> recipe) {
        try {
            if (player == null || recipe == null || recipe.value() == null) return;
            var inventory = new ArrayList<ItemStack>(player.getInventory().items);
            // Their own client path reads the WORN backpack stack; handlerOf(...) resolves exactly that stack.
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
            var hints = dev.scathiard.feedmepackages.client.ClientMaterials.stacks();
            int hintTotal = 0;
            for (var stack : hints) hintTotal += stack.getCount();
            String hintLine = "FMP compat: hints view stacks=" + hints.size() + " total=" + hintTotal + " serial=" + dev.scathiard.feedmepackages.client.ClientMaterials.version();
            if (shouldPrint(hintLine, System.currentTimeMillis(), lastHintLine, lastHintAt)) { lastHintLine = hintLine; lastHintAt = System.currentTimeMillis(); CompatLog.compat(hintLine); }
            String line = text.toString();
            long now = System.currentTimeMillis();
            if (!shouldPrint(line, now, lastLine, lastPrintedAt)) return;   // unchanged: stay quiet
            lastLine = line; lastPrintedAt = now;
            CompatLog.compat(line);
        } catch (Throwable ignored) {
            // Diagnostics must never disturb their preview.
        }
    }

    /** F-8: one line that says what THEIR preview answered (read-only observation; never a decision). */
    public static String verdictLine(String typeOrNull, int missing) {
        return "FMP compat: their preview returned=" + (typeOrNull == null ? "null" : typeOrNull) + " missing=" + Math.max(0, missing);
    }

    /** Pure: a changed content always prints; identical content is throttled to once every two seconds. */
    static boolean shouldPrint(String line, long now, String previous, long previousAt) {
        if (previous == null || !previous.equals(line)) return true;
        return now - previousAt >= 2000;
    }

    private static String name(Ingredient ingredient) {
        try {
            var items = ingredient.getItems();
            return items.length == 0 ? "?" : items[0].getItem().toString();
        } catch (Throwable missing) {
            return "?";
        }
    }
    /** True when the player inventory, their worn backpack or our lent cache can cover the ingredient. */
    public static boolean covered(Player player, Ingredient ingredient) {
        try {
            for (ItemStack stack : player.getInventory().items) if (ingredient.test(stack)) return true;
            var worn = FxntBackpack.handlerOf(player);
            if (worn != null) for (int index = 0; index < worn.getSlots(); index++) if (ingredient.test(worn.getStackInSlot(index))) return true;
            for (var entry : new ClientCacheSupply().available()) if (ingredient.test(entry.stack())) return true;
            return false;
        } catch (Throwable unavailable) {
            return true;   // cannot tell: never claim something is missing
        }
    }
}