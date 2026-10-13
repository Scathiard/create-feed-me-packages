package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomLaunch;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Phantom 1.0.2, launch side (0.3.0 T3): the native resolver only knows players and ports, so a package
 * addressed to one of our maids resolved to nothing and never departed - the reason a factory or belt send to a
 * maid had no courier. We answer that resolution with a native player target carrying her UUID; the native
 * acceptance check that follows is the settlement bridge's job, already done.
 *
 * <p>Static on purpose: the target method is a static utility method, and a non-static handler for a static target
 * makes Mixin fail the whole game at startup (hit once, hence the explicit note). Fail-closed: the shape gate
 * refuses to apply this mixin unless the target method is exactly as measured, and any branch that does not
 * positively identify one of our maids returns without cancelling, leaving the player path byte-for-byte intact.
 */
@Pseudo
@Mixin(targets = "com.yision.phantom.logistics.courier.AirCourierDispatchService", remap = false)
public abstract class Phantom102LaunchMixin {

    @Inject(method = "resolvePackageTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$resolveMaidTarget(ServerLevel level, ItemStack box, Vec3 origin,
            @Coerce Object dimension, @Coerce Object portPos, CallbackInfoReturnable<Object> cir) {
        if (!Phantom102Gate.usable(true)) return;
        if (!OptionalMixins.phantom102FlightCompatible()) return;
        Object target = MaidPhantomLaunch.nativeTargetFor(level.getServer(), box);
        if (target != null) cir.setReturnValue(target);
    }
}
