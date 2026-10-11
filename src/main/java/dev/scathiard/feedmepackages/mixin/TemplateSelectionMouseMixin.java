package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the explicitly entered FMP selector; ordinary input and vanilla button bookkeeping are untouched. */
@Mixin(MouseHandler.class)
public abstract class TemplateSelectionMouseMixin {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void fmp$selectTemplate(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().getWindow() && LogisticsPanel.earlyTemplateInput(button, action)) ci.cancel();
    }
}
