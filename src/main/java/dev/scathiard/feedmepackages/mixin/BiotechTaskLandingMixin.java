package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.biotech.BiotechMaidFlight;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierTask", "com.yision.allay.logistics.courier.AllayCourierTask"}, remap = false)
public abstract class BiotechTaskLandingMixin {
    @Inject(method = "landingTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$landing(@Coerce Object port, ServerPlayer player, CallbackInfoReturnable<Vec3> cir) {
        if (port == null && BiotechMaidFlight.targetsMaid(this)) cir.setReturnValue(BiotechMaidFlight.position(this, false));
    }
}
