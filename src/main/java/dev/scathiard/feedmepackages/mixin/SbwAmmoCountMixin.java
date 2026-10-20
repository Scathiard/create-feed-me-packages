package dev.scathiard.feedmepackages.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import dev.scathiard.feedmepackages.compat.sbw.SbwMaterials;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.data.gun.GunData", remap = false)
public abstract class SbwAmmoCountMixin {
    @Inject(method = "countBackupAmmo(Lnet/minecraft/world/entity/Entity;)I", at = @At("HEAD"))
    private void fmp$freshOwner(Entity entity, CallbackInfoReturnable<Integer> ci) {
        SbwMaterials.refreshCount((GunData)(Object)this, entity);
    }
}
