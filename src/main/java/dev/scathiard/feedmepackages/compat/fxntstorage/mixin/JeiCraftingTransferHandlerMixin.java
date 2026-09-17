package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntContext;
import dev.scathiard.feedmepackages.compat.fxntstorage.PreviewDiagnostic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create: Storage's JEI crafting transfer, CLIENT side. Their preview knows the RECIPE, so we first record its
 * materials as this call's borrow filter (HEAD), then wrap the handler local their own code reads (its LVT
 * type is IItemHandlerModifiable), and clear the filter when the call ends. Callback signatures are the exact
 * target descriptors on purpose - a widened parameter type would silently never match (the F-1 failure).
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler")
public abstract class JeiCraftingTransferHandlerMixin {
    @Inject(method = "transferRecipe", at = @At("HEAD"))
    private void fmp$recordRecipeMaterials(CraftingMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
                                           Player player, boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> info) {
        if (recipe != null && recipe.value() != null) {
            FxntContext.materials(recipe.value().getIngredients());
            PreviewDiagnostic.report(player, recipe);
        }
    }

    @ModifyVariable(method = "transferRecipe", at = @At("STORE"), ordinal = 0)
    private IItemHandlerModifiable fmp$presentCacheToTheirPreview(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:transferRecipe", "FMP compat: hooked transferRecipe (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:JEICraftingTransferHandler#transferRecipe");
    }

    @Inject(method = "transferRecipe", at = @At("RETURN"))
    private void fmp$clearRecipeMaterials(CraftingMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
                                          Player player, boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> info) {
        FxntContext.clear();
    }
}