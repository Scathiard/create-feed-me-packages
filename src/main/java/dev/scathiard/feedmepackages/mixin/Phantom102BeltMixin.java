package dev.scathiard.feedmepackages.mixin;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomBeltLaunch;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Phantom 1.0.2, belt launch (0.3.0 T3b): the native launch gate refuses any target that is not an online
 * player - measured at {@code launchPackageCourier+266}: {@code PlayerList.getPlayer(target.playerId())} followed by
 * {@code ifnull} straight to {@code return false}. A maid has no player entry, so the courier stayed on the belt and
 * the belt dropped it at its end, which is exactly what the user saw with .10.
 *
 * <p>That gate cannot be "allowed": the check is a hard branch, and fabricating a player is off the table. So we
 * answer the launch ourselves for a maid-addressed parcel, reproducing the native happy path slot for slot. The
 * carrier item on the belt is consumed by the caller on {@code true} (verified in bytecode: the launch chain itself
 * never touches the transported stack), so we must not touch it either. Every condition that does not hold returns
 * without cancelling, and the native path runs exactly as before.
 *
 * <p>Static on purpose: the target method is static, and a non-static handler for a static target makes Mixin fail
 * the whole game at startup (hit once, hence the explicit note).
 */
@Pseudo
@Mixin(targets = "com.yision.phantom.logistics.courier.AirCourierBeltHooks", remap = false)
public abstract class Phantom102BeltMixin {

    @Inject(method = "launchPackageCourier", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$launchForMaid(BeltBlockEntity belt, TransportedItemStack transported,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!Phantom102Gate.usable(true)) return;
        if (!OptionalMixins.phantom102BeltCompatible()) return;
        if (!MaidPhantomBeltLaunch.launchIfMaid(belt, transported)) return;
        cir.setReturnValue(Boolean.TRUE);
    }
}
