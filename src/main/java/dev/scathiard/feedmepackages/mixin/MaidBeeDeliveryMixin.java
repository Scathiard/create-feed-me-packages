package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidCouriers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.entities.robo_entity.RoboBeeBehaviorController", remap = false)
public abstract class MaidBeeDeliveryMixin {
    @Inject(method = "handleDeliverPackage", at = @At("HEAD"), remap = false)
    private void fmp$deliverToMaid(@Coerce Object robo, CallbackInfo ci) { MaidCouriers.beeArrive(robo); }
}
