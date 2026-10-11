package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientCrafting;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.ArrayList;

/** Adds backed cache sources to JEI's validated vanilla transfer, without registering a handler. */
public final class JeiCacheSources {
    private JeiCacheSources() {}

    @SuppressWarnings("unchecked")
    public static boolean fill(AbstractContainerMenu menu, Object value, Player player, boolean maximum, boolean transfer) {
        if (!FmpConfig.jeiCraftingEnabled() || player != Minecraft.getInstance().player || player.containerMenu != menu
                || !CraftingService.supported(menu) || !menu.getCarried().isEmpty()
                || !ClientMaterials.active() || !LogisticsPanel.recipeReady()
                || !(value instanceof RecipeHolder<?> holder) || !(holder.value() instanceof CraftingRecipe)) return false;
        var recipe = (RecipeHolder<CraftingRecipe>) holder;
        var grid = CraftingService.grid(menu);
        var real = ClientMaterials.realGrid(player, grid);
        boolean backedGrid = false;
        for (int i = 0; i < real.size(); i++) if (real.get(i).getCount() < grid.getItem(i).getCount()) backedGrid = true;
        int requested = !maximum && recipe.value().matches(grid.asCraftInput(), player.level())
                ? grid.getItems().stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).min().orElse(0) + 1 : 1;
        var sources = new ArrayList<ItemStack>(real);
        sources.addAll(player.getInventory().items);
        var physical = CraftingPlanner.solve(recipe, grid.getWidth(), grid.getHeight(), grid.getMaxStackSize(),
                requested, maximum, sources, player.level());
        if (!backedGrid && physical.error() == CraftingPlanner.Error.NONE) {
            if (!maximum) return false; // Ordinary physical transfers keep JEI's original packet and layout.
            sources.addAll(ClientMaterials.craftingStacks());
            var combined = CraftingPlanner.solve(recipe, grid.getWidth(), grid.getHeight(), grid.getMaxStackSize(),
                    requested, true, sources, player.level());
            if (combined.error() != CraftingPlanner.Error.NONE || combined.sets() <= physical.sets()) return false;
        }
        if (ClientCrafting.check(player, recipe, maximum, true) != CraftingService.Result.OK) return false;
        // This existing intent rechecks access, revision, recipe and the JEI switch on the server.
        // Preparation only reserves; the native result click remains the consumer.
        return !transfer || LogisticsPanel.fillRecipe(recipe.id(), maximum);
    }
}
