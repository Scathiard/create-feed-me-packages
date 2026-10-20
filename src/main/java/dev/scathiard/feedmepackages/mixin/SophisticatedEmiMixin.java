package dev.scathiard.feedmepackages.mixin;
import dev.emi.emi.api.recipe.*;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedEmi;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.emi.EmiGridMenuInfo",remap=false)
public abstract class SophisticatedEmiMixin {
    @Shadow @Final private RecipeType<?> recipeType;
    @Inject(method="getInventory",at=@At("RETURN"),cancellable=true,remap=false)
    private void fmp$hints(AbstractContainerScreen<?> screen,CallbackInfoReturnable<EmiPlayerInventory> result) {
        if(recipeType==RecipeType.CRAFTING)result.setReturnValue(SophisticatedEmi.inventory(screen,result.getReturnValue()));
    }
    @Inject(method="canCraft",at=@At("RETURN"),cancellable=true,remap=false)
    private void fmp$can(EmiRecipe recipe,EmiCraftContext<?> context,CallbackInfoReturnable<Boolean> result) {
        if(recipeType==RecipeType.CRAFTING && !result.getReturnValueZ() && SophisticatedEmi.fill(recipe,context,false))result.setReturnValue(true);
    }
    @Inject(method="craft",at=@At("HEAD"),cancellable=true,remap=false)
    private void fmp$fill(EmiRecipe recipe,EmiCraftContext<?> context,CallbackInfoReturnable<Boolean> result) {
        if(recipeType==RecipeType.CRAFTING && SophisticatedEmi.fill(recipe,context,true))result.setReturnValue(true);
    }
}
