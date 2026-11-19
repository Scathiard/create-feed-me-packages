package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.biotech.BiotechMaidFlight;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Biotech 2.0 moved the landing calculation from the task to a static routing helper. */
@Pseudo
@Mixin(targets = "com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierPortRoute", remap = false)
public abstract class BiotechRouteLandingMixin {
    @Inject(method = "landingTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$landing(@Coerce Object task, @Coerce Object port, ServerPlayer player, CallbackInfoReturnable<Vec3> cir) {
        if (port == null && BiotechMaidFlight.targetsMaid(task)) cir.setReturnValue(BiotechMaidFlight.position(task, false));
    }
}
