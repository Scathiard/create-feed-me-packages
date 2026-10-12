package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedMaterials;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.CraftingContainerRecipeTransferHandlerServer", remap = false)
public abstract class SophisticatedSourcesMixin {
    @WrapOperation(method = {"getSlotWithStack(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Ljava/lang/Iterable;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/inventory/Slot;", "addStack"}, at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;getSlot(I)Lnet/minecraft/world/inventory/Slot;"), remap = false)
    private static Slot fmp$stagedSource(@Coerce AbstractContainerMenu menu, int id, Operation<Slot> original) {
        var slot = original.call(menu, id); var scope = SophisticatedMaterials.current();
        return scope == null ? slot : scope.slot(slot);
    }
    @WrapOperation(method = {"clearAndPutItemsIntoGrid", "putItemIntoGrid"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;getSlot(I)Lnet/minecraft/world/inventory/Slot;"), remap = false)
    private static Slot fmp$stagedTarget(AbstractContainerMenu menu, int id, Operation<Slot> original) {
        var slot = original.call(menu, id); var scope = SophisticatedMaterials.current();
        return scope == null ? slot : scope.slot(slot);
    }
    @Inject(method = "getSlotWithStack(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/inventory/Slot;", at = @At("RETURN"), cancellable = true, remap = false)
    private static void fmp$cacheSource(@Coerce Object menu, ItemStack wanted, List<Integer> grid, List<Integer> inventory, CallbackInfoReturnable<Slot> cir) {
        var scope = SophisticatedMaterials.current();
        if (scope != null && cir.getReturnValue() == null) cir.setReturnValue(scope.fallback(wanted));
    }
    @WrapOperation(method = "putIntoInventory", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"), remap = false)
    private static boolean fmp$refuseOverflow(Inventory inventory, ItemStack stack, Operation<Boolean> original) {
        var scope = SophisticatedMaterials.current();
        if (scope == null) return original.call(inventory, stack);
        scope.overflow(); return true;
    }
    @WrapOperation(method = "lambda$clearAndPutItemsIntoGrid$0", at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedcore/common/gui/ICraftingContainer;setRecipeUsed(Lnet/minecraft/resources/ResourceLocation;)V"), remap = false)
    private static void fmp$deferRecipe(@Coerce Object crafting, ResourceLocation recipe, Operation<Void> original) {
        if (SophisticatedMaterials.current() == null) original.call(crafting, recipe);
    }
    @WrapOperation(method = "setItems", at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;broadcastChanges()V"), remap = false)
    private static void fmp$deferSync(@Coerce AbstractContainerMenu menu, Operation<Void> original) {
        if (SophisticatedMaterials.current() == null) original.call(menu);
    }
}
