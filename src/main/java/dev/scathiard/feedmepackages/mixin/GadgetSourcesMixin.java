package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.compat.gadgets.GadgetMaterials;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Pseudo
@Mixin(targets = "com.direwolf20.buildinggadgets2.util.BuildingUtils", remap = false)
public abstract class GadgetSourcesMixin {
    @WrapOperation(method = "removeStacksFromInventory(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;ZLnet/minecraft/core/GlobalPos;Lnet/minecraft/core/Direction;)Z",
            at = @At(value = "INVOKE", target = "Lcom/direwolf20/buildinggadgets2/util/BuildingUtils;checkInventoryForItems(Lnet/minecraft/world/entity/player/Inventory;Ljava/util/List;Z)V"), remap = false)
    private static void fmp$remaining(Inventory inventory, List<ItemStack> remaining, boolean simulate, Operation<Void> original,
                                       @Local(argsOnly = true) Player player) {
        original.call(inventory, remaining, simulate);
        GadgetMaterials.fill(player, remaining, simulate);
    }
    @ModifyReturnValue(method = "countItemStacks(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)I", at = @At("RETURN"), remap = false)
    private static int fmp$count(int original, Player player, ItemStack prototype) {
        return GadgetMaterials.count(player, prototype, original);
    }
}
