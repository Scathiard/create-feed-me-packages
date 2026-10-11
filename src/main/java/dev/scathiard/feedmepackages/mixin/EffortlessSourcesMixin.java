package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.effortless.EffortlessMaterials;
import dev.scathiard.feedmepackages.compat.effortless.EffortlessOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="neoforge.nl.requios.effortlessbuilding.utilities.InventoryHelper",remap=false)
public abstract class EffortlessSourcesMixin {
    @ModifyReturnValue(method="findTotalItemsInInventory(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;)I",at=@At("RETURN"),remap=false)
    private static int fmp$count(int original,Player player,Item item) {return EffortlessMaterials.count(player,item,original);}
    @ModifyReturnValue(method="consumeItems(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;I)I",at=@At("RETURN"),remap=false)
    private static int fmp$take(int original,Player player,Item item,int requested) {return EffortlessMaterials.consume(player,item,requested,original);}
    @WrapOperation(method="consumeItems(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;I)I",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/ItemStack;shrink(I)V"),remap=false)
    private static void fmp$receipt(ItemStack stack,int amount,Operation<Void> original,@com.llamalad7.mixinextras.sugar.Local(argsOnly=true) Player player) {
        var before=stack.copy();original.call(stack,amount);
        int removed=before.getCount()-stack.getCount();
        if(removed>0 && (stack.isEmpty() || ItemStack.isSameItemSameComponents(before,stack)))EffortlessOperation.paidInventory(player,before.copyWithCount(removed));
    }
}
