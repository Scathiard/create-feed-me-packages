package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.PendantNetworkHighlight;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * R-020: Create highlights the network of the tuned stock link in the main hand. A bound pendant held there should show
 * the same thing, so for that one case we take over the tick: seed the handler's own {@code previouslyHeldFrequency}
 * (the factory-panel path reads it natively) and draw the outlines with Create's public API. Every other state - no
 * pendant, an unbound pendant, a pendant in the off hand or a curios slot, a real stock link - returns without
 * cancelling, so the native path is untouched.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedClientHandler", remap = false)
public abstract class PendantNetworkHighlightMixin {
    @Shadow
    private static UUID previouslyHeldFrequency;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$pendantHighlight(CallbackInfo info) {
        if (!OptionalMixins.pendantHighlightCompatible()) return;
        UUID frequency = PendantNetworkHighlight.boundNetwork();
        if (frequency == null) return;
        previouslyHeldFrequency = frequency;
        PendantNetworkHighlight.outline(frequency);
        info.cancel();
    }
}
