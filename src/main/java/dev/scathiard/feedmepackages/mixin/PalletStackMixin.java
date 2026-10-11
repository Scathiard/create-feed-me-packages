package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.*;
import dev.scathiard.feedmepackages.item.PocketPalletItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ItemStack.class)
public abstract class PalletStackMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void fmp$selectedMaterial(UseOnContext context, CallbackInfoReturnable<InteractionResult> ci) {
        if (((ItemStack) (Object) this).getItem() instanceof PocketPalletItem) ci.setReturnValue(PalletService.use(context));
    }
    @Inject(method = "consume", at = @At("HEAD"), cancellable = true)
    private void fmp$cacheEvenInCreative(int amount, LivingEntity entity, CallbackInfo ci) {
        var stack = (ItemStack) (Object) this;
        if (WorldPlacement.material(stack)) { stack.shrink(amount); ci.cancel(); }
    }
}
