package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.consumption.WorldPlacement;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Mixin(CommonHooks.class)
public abstract class PalletPlacementMixin {
    @WrapOperation(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private static InteractionResult fmp$observe(Item item, UseOnContext context, Operation<InteractionResult> original) {
        var result = original.call(item, context); WorldPlacement.observed(context, result); return result;
    }
    @WrapOperation(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;onBlockPlace(Lnet/minecraft/world/entity/Entity;Lnet/neoforged/neoforge/common/util/BlockSnapshot;Lnet/minecraft/core/Direction;)Z"))
    private static boolean fmp$single(Entity player, BlockSnapshot snapshot, Direction face, Operation<Boolean> original, @Local(argsOnly = true) UseOnContext context) {
        return WorldPlacement.veto(context, original.call(player, snapshot, face));
    }
    @WrapOperation(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;onMultiBlockPlace(Lnet/minecraft/world/entity/Entity;Ljava/util/List;Lnet/minecraft/core/Direction;)Z"))
    private static boolean fmp$multi(Entity player, List<BlockSnapshot> snapshots, Direction face, Operation<Boolean> original, @Local(argsOnly = true) UseOnContext context) {
        return WorldPlacement.veto(context, original.call(player, snapshots, face));
    }
    @WrapOperation(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;onPlayerDestroyItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)V"))
    private static void fmp$toolNotDestroyed(Player player, ItemStack copy, InteractionHand hand, Operation<Void> original, @Local(argsOnly = true) UseOnContext context) {
        // No physical hand item was destroyed: the actual held pallet is unchanged.
        if (!WorldPlacement.context(context)) original.call(player, copy, hand);
    }
}
