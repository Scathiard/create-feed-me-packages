package dev.scathiard.feedmepackages.compat.jei.takeover;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.compat.jei.FmpJeiPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * CLIENT ONLY. When our logistics panel is live we answer the crafting-table transfer query ourselves
 * (cache supply). Otherwise we touch nothing and the previously registered handler keeps answering
 * (e.g. FXNT Storage's backpack-only handler). Soft failure: any throw is swallowed and logged.
 */
@Mixin(targets = "mezz.jei.library.recipes.RecipeTransferManager")
public abstract class TakeoverRecipeTransferManagerMixin {
    private static volatile boolean fmp$lastServed = false;

    static {
        FeedMePackages.LOGGER.info("FMP JEI takeover: mixin class loaded");
    }

    @Inject(method = "getRecipeTransferHandler", at = @At("RETURN"), cancellable = true)
    private void fmp$takeover(AbstractContainerMenu menu, IRecipeCategory<?> category, CallbackInfoReturnable<Optional<?>> cir) {
        try {
            if (menu == null || category == null) return;
            if (menu.getClass() != CraftingMenu.class) return;
            if (category.getRecipeType() != (Object) mezz.jei.api.constants.RecipeTypes.CRAFTING) return;
            var handler = FmpJeiPlugin.craftingHandler().orElse(null);
            if (handler == null) return;
            // At RETURN we can see what JEI itself would have answered: that is the handler we displaced, and it
            // is the one that may know extra sources (e.g. a worn backpack). Keep it so our handler can consult
            // it FIRST and only top up the shortfall from the cache.
            Object answer = cir.getReturnValue() instanceof Optional<?> optional ? optional.orElse(null) : null;
            FmpJeiPlugin.rememberDisplaced(answer);
            boolean serve = LogisticsPanel.cacheLive();
            if (serve) cir.setReturnValue(Optional.of(handler));
            if (serve != fmp$lastServed) {
                fmp$lastServed = serve;
                FeedMePackages.LOGGER.info("FMP JEI takeover: {}", serve
                        ? "serving (panel live, menu=CraftingMenu, recipeType=minecraft:crafting)"
                        : "deferring (panel not live; the previously registered handler answers)");
            }
        } catch (Throwable t) {
            FeedMePackages.LOGGER.info("FMP JEI takeover: probe failed {}", t.getClass().getName());
        }
    }
}