package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedJei;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.jei.JeiCraftingContainerRecipeTransferHandlerBase", remap = false)
public abstract class SophisticatedJeiMixin {
    @Inject(method = "transferRecipe(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$largerNativeBatch(@Coerce AbstractContainerMenu menu, RecipeHolder<?> recipe, IRecipeSlotsView slots,
                                     Player player, boolean maximum, boolean transfer, CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (maximum && SophisticatedJei.augmentMaximum(menu, recipe, player, transfer)) cir.setReturnValue(null);
    }
    @Inject(method = "transferRecipe(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void fmp$missingSources(@Coerce AbstractContainerMenu menu, RecipeHolder<?> recipe, IRecipeSlotsView slots,
                                   Player player, boolean maximum, boolean transfer, CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (cir.getReturnValue() != null && SophisticatedJei.fallback(menu, recipe, player, maximum, transfer)) cir.setReturnValue(null);
    }
}
