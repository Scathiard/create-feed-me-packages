package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.tacz.TaczClientSources;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Lua animation loop has its own reserve query, separate from reload and HUD checks. */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.animation.statemachine.GunAnimationStateContext", remap = false)
public abstract class TaczAnimationSourcesMixin {
    @Shadow private ItemStack currentGunItem;
    @Inject(method = "hasAmmoToConsume()Z", at = @At("RETURN"), cancellable = true, remap = false)
    private void fmp$cachedReserve(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && TaczClientSources.animationAmmo(currentGunItem)) cir.setReturnValue(true);
    }
}
