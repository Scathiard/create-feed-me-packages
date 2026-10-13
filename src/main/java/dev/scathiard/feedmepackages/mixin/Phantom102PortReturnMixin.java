package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomPortReturn;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Phantom 1.0.2, the wing station's carrier departure (0.3.0 T3c). A port that has to send a carrier back
 * resolves the addressee through {@code PlayerList.getPlayer} and gives up when there is no living player - which is
 * always the case for a maid - and then ejects the stored carrier as a dropped item. Measured, and seen on the real
 * machine.
 *
 * <p>For one of our maids we answer that departure ourselves (consume the port's carrier, create the native return task
 * addressed to her, register it) and report success, so the port never reaches its drop. Anything else - another UUID,
 * an unknown shape, a failed reflection, any doubt at all - returns without cancelling and the native path runs exactly
 * as before.
 *
 * <p>Not static on purpose: the target is an instance method of the port's return queue.
 */
@Pseudo
@Mixin(targets = "com.yision.phantom.block.phantomport.PhantomPortReturnQueue", remap = false)
public abstract class Phantom102PortReturnMixin {

    @Inject(method = "tryQueueStoredReturnCarrierToPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$portReturnToMaid(UUID playerId, CallbackInfoReturnable<Boolean> cir) {
        if (!Phantom102Gate.usable(true)) return;
        if (!OptionalMixins.phantom102PortReturnCompatible()) return;
        if (!MaidPhantomPortReturn.takeOver(this, playerId)) return;
        cir.setReturnValue(Boolean.TRUE);
    }
}
