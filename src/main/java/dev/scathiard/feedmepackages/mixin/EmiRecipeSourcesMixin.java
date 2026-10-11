package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.scathiard.feedmepackages.compat.emi.EmiSourcePreview;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Pseudo
@Mixin(targets = "dev.emi.emi.registry.EmiRecipeFiller", remap = false)
public abstract class EmiRecipeSourcesMixin {
    @WrapOperation(method = "getStacks", at = @At(value = "INVOKE", target = "Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;getInputSources(Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;"), remap = false)
    private static List<Slot> fmp$sources(StandardRecipeHandler<?> handler, AbstractContainerMenu menu, Operation<List<Slot>> original) {
        return EmiSourcePreview.append(handler, menu, original.call(handler, menu));
    }
    @WrapOperation(method = {"getStacks", "batchesAlreadyPresent"}, at = @At(value = "INVOKE", target = "Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;getCraftingSlots(Ldev/emi/emi/api/recipe/EmiRecipe;Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;"), remap = false)
    private static List<Slot> fmp$physicalGrid(StandardRecipeHandler<?> handler, dev.emi.emi.api.recipe.EmiRecipe recipe,
                                             AbstractContainerMenu menu, Operation<List<Slot>> original) {
        return EmiSourcePreview.physicalSlots(handler, menu, original.call(handler, recipe, menu));
    }
}
