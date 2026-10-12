package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidCouriers;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.kreidev.cmpackagecouriers.plane.CardboardPlane", remap = false)
public abstract class MaidPaperMixin {
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void fmp$trackMaid(MinecraftServer server, CallbackInfo ci) { MaidCouriers.paperTick(this, server); }
    @Inject(method = "onReachedTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$deliverMaid(MinecraftServer server, CallbackInfo ci) {
        if (MaidCouriers.paperArrive(this, server)) ci.cancel();
    }
}
