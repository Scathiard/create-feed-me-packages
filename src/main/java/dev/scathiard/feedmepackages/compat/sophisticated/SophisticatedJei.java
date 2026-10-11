package dev.scathiard.feedmepackages.compat.sophisticated;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.network.TransferOrigin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;

/** Called only after the author's JEI path could not transfer from native sources. */
public final class SophisticatedJei {
    private SophisticatedJei() {}
    @SuppressWarnings("unchecked")
    public static boolean fallback(AbstractContainerMenu menu, RecipeHolder<?> recipe, Player player, boolean maximum, boolean transfer) {
        return fill(menu, recipe, player, maximum, transfer, false);
    }
    public static boolean augmentMaximum(AbstractContainerMenu menu, RecipeHolder<?> recipe, Player player, boolean transfer) {
        return fill(menu, recipe, player, true, transfer, true);
    }
    @SuppressWarnings("unchecked")
    private static boolean fill(AbstractContainerMenu menu, RecipeHolder<?> recipe, Player player, boolean maximum, boolean transfer, boolean requireImprovement) {
        if (!FmpConfig.jeiCraftingEnabled() || !ClientCompatibility.sophisticatedSourcesAvailable()
                || !ClientMaterials.active() || !(recipe.value() instanceof CraftingRecipe)
                || player.containerMenu != menu || !menu.getCarried().isEmpty()) return false;
        var context = SophisticatedAccess.resolve(player);
        if (context == null) return false;
        var sources = new ArrayList<ItemStack>(); context.all().forEach(s -> sources.add(s.getItem().copy()));
        var physical = requireImprovement ? CraftingPlanner.solve((RecipeHolder<CraftingRecipe>) recipe, 3, 3, 64, 1, true, sources, player.level()) : null;
        sources.addAll(ClientMaterials.stacks());
        var choice = CraftingPlanner.solve((RecipeHolder<CraftingRecipe>) recipe, 3, 3, 64, 1, maximum, sources, player.level());
        if (choice.error() != CraftingPlanner.Error.NONE || physical != null && physical.sets() >= choice.sets()) return false;
        if (transfer) {
            context.open();
            PacketDistributor.sendToServer(new SophisticatedTransfer.Request(menu.containerId, context.upgrade(), ClientMaterials.cacheId(),
                    recipe.id(), maximum, TransferOrigin.Viewer.JEI));
        }
        return true;
    }
}
