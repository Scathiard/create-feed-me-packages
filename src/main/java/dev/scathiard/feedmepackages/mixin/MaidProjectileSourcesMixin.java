package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid", remap = false)
public abstract class MaidProjectileSourcesMixin {
    @Inject(method = "getProjectile", at = @At("RETURN"), cancellable = true, remap = false)
    private void fmp$projectile(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
        cir.setReturnValue(AmmoService.maidCandidate((LivingEntity)(Object)this, weapon, cir.getReturnValue()));
    }
}
