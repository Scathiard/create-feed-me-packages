package dev.scathiard.feedmepackages.mixin;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomBeltLaunch;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cosmetic failure after admission must not leave a maid's return carrier on the belt for a second launch. */
@Pseudo
@Mixin(targets="com.yision.phantom.logistics.courier.AirCourierBeltHooks",remap=false)
public abstract class Phantom103CarrierBeltMixin {
    @Inject(method="launchReturningCarrierToPlayer",at=@At("HEAD"),cancellable=true,remap=false)
    private static void fmp$returnCarrier(BeltBlockEntity belt, TransportedItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Boolean sent=MaidPhantomBeltLaunch.launchCarrierIfMaid(belt,stack);
        if(sent!=null)cir.setReturnValue(sent);
    }
}
