package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.PalletScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class PalletAttackMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void fmp$pauseMiningDuringSelection(boolean pressed, CallbackInfo ci) {
        var mc = (Minecraft) (Object) this;
        if (pressed && mc.screen instanceof PalletScreen) {
            // Keep native release cleanup: it resets missTime after leaving a Screen.
            // Outside the picker, holding the tool must not suppress normal attack or mining.
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            ci.cancel();
        }
    }
}
