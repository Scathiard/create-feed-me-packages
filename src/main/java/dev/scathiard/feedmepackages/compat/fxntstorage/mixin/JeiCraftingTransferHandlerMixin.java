package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntContext;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * F-6, CLIENT side: the same rule, applied at the one call their preview makes to {@code Player.getInventory()}.
 * The presenter is read-only: it can present our cache, it can never debit and it writes nothing.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler")
public abstract class JeiCraftingTransferHandlerMixin {
    @Inject(method = "transferRecipe", at = @At("HEAD"))
    private void fmp$recordRecipeMaterials(CraftingMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
                                           Player player, boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> info) {
        if (recipe != null && recipe.value() != null) {
            dev.scathiard.feedmepackages.compat.fxntstorage.FxntContainerProbe.inspectAndRefresh(player);
            FxntContext.materials(recipe.value().getIngredients());
            dev.scathiard.feedmepackages.compat.fxntstorage.PreviewDiagnostic.report(player, recipe);
        }
    }

    @Redirect(method = "transferRecipe", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"))
    private Inventory fmp$presentCacheToTheirPreview(Player player) {
        CompatLog.once("hooked:transferRecipe", "FMP compat: hooked transferRecipe (Player.getInventory -> read-only presenter)");
        CompatLog.once("readonly-installed", "FMP compat: read-only presenter installed (client)");
        return FxntCompat.presentInventoryReadOnly(player.getInventory(), "fxntstorage:JEICraftingTransferHandler#transferRecipe");
    }

    @Inject(method = "transferRecipe", at = @At("RETURN"))
    private void fmp$clearRecipeMaterials(CraftingMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
                                          Player player, boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> info) {
        var verdict = info.getReturnValue();
        int missing = 0;
        try {
            for (var ingredient : recipe.value().getIngredients()) if (!dev.scathiard.feedmepackages.compat.fxntstorage.PreviewDiagnostic.covered(player, ingredient)) missing++;
        } catch (Throwable ignored) { }
        CompatLog.compat(dev.scathiard.feedmepackages.compat.fxntstorage.PreviewDiagnostic.verdictLine(verdict == null ? null : verdict.getType().name(), missing));
        FxntContext.clear();
    }
}