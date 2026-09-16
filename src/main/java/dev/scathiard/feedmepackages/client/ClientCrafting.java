package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.*;

/** Shared exact client preview for the vanilla book and optional recipe viewers; never a mutation. */
public final class ClientCrafting {
    private ClientCrafting() {}
    /**
     * One estimate: the verdict plus the concrete targets this client could not cover. The list is
     * EXPLANATORY ONLY - it names what this client thinks is missing and never decides whether a transfer
     * may be attempted; the server arbitrates that.
     */
    public record Estimate(CraftingService.Result result, List<ItemStack> missing) {}
    public static CraftingService.Result check(Player player, RecipeHolder<CraftingRecipe> recipe, boolean maximum, boolean incrementExisting) {
        return estimate(player, recipe, maximum, incrementExisting).result();
    }
    /** The missing targets behind a verdict (empty when the client cannot name anything). */
    public static List<ItemStack> shortfall(Player player, RecipeHolder<CraftingRecipe> recipe, boolean maximum, boolean incrementExisting) {
        return estimate(player, recipe, maximum, incrementExisting).missing();
    }
    public static Estimate estimate(Player player, RecipeHolder<CraftingRecipe> recipe, boolean maximum, boolean incrementExisting) {
        if (!ClientMaterials.active()) return new Estimate(CraftingService.Result.INACTIVE, List.of());
        if (!CraftingService.supported(player.containerMenu) || !(player.containerMenu.getSlot(1).container instanceof CraftingContainer grid)) return new Estimate(CraftingService.Result.UNSUPPORTED, List.of());
        var inventory = MaterialTransaction.copies(player.getInventory().items);
        var originalInventory = MaterialTransaction.copies(inventory);
        var cache = ClientMaterials.craftingStacks(); var prior = ClientMaterials.realGrid(player, grid);
        var sources = MaterialTransaction.copies(prior);
        sources.addAll(inventory); sources.addAll(cache);
        int requested = !maximum && incrementExisting && recipe.value().matches(grid.asCraftInput(), player.level())
                ? grid.getItems().stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).min().orElse(0) + 1 : 1;
        var choice = CraftingPlanner.solve(recipe, grid.getWidth(), grid.getHeight(), grid.getMaxStackSize(), requested, maximum, sources, player.level());
        if (choice.error() != CraftingPlanner.Error.NONE) return new Estimate(switch (choice.error()) {
            case UNSUPPORTED -> CraftingService.Result.UNSUPPORTED; case TOO_COMPLEX -> CraftingService.Result.TOO_COMPLEX; default -> CraftingService.Result.MISSING;
        }, List.of());
        var missing = new ArrayList<ItemStack>();
        for (var target : choice.grid()) if (!target.isEmpty()) {
            int remaining = take(prior, target, target.getCount());
            remaining = take(inventory, target, remaining);
            remaining = take(cache, target, remaining);
            if (remaining > 0) missing.add(target.copyWithCount(remaining));
        }
        if (!missing.isEmpty()) return new Estimate(CraftingService.Result.MISSING, missing);
        for (var remainder : prior) if (!MaterialTransaction.insert(originalInventory, remainder, player.getInventory().getMaxStackSize())) return new Estimate(CraftingService.Result.NO_SPACE, missing);
        return new Estimate(CraftingService.Result.OK, List.of());
    }
    private static int take(List<ItemStack> source, ItemStack prototype, int count) {
        for (var stack : source) if (count > 0 && ItemStack.isSameItemSameComponents(stack, prototype)) {
            int use = Math.min(count, stack.getCount()); stack.shrink(use); count -= use;
        }
        return count;
    }
}
