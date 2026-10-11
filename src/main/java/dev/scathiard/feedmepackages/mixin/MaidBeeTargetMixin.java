package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidCouriers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.robo.VirtualRobo", remap = false)
public abstract class MaidBeeTargetMixin {
    @Inject(method = "updateTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$maidTarget(CallbackInfo ci) { if (MaidCouriers.beeTarget(this)) ci.cancel(); }
}
