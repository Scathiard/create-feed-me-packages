package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.PalletService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class PalletAttackMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void fmp$noMining(boolean pressed, CallbackInfo ci) {
        var mc = (Minecraft) (Object) this;
        if (pressed && mc.player != null && PalletService.holding(mc.player)) {
            // Keep native release cleanup: it resets missTime after leaving a Screen.
            // Canceling the false branch would prevent opening this picker a second time.
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            ci.cancel();
        }
    }
}
