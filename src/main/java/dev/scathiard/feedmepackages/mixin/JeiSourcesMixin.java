package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.jei.JeiCacheSources;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.library.transfer.BasicRecipeTransferHandler", remap = false)
public abstract class JeiSourcesMixin {
    @Inject(method = "transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At(value = "INVOKE", target = "Lmezz/jei/library/transfer/BasicRecipeTransferHandler;getInventoryState(Ljava/util/Collection;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/recipe/transfer/IRecipeTransferInfo;)Lmezz/jei/library/transfer/BasicRecipeTransferHandler$InventoryState;"),
            cancellable = true, remap = false)
    private void fmp$cacheSources(AbstractContainerMenu menu, Object recipe, IRecipeSlotsView slots, Player player,
                                  boolean maximum, boolean transfer, CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (JeiCacheSources.fill(menu, recipe, player, maximum, transfer)) cir.setReturnValue(null);
    }
}
