package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queue pending settings before vanilla sends the server's container-close packet. */
@Mixin(AbstractContainerScreen.class)
public abstract class CellSettingsCloseMixin {
    @Inject(method="onClose",at=@At("HEAD"))
    private void fmp$saveSettingsBeforeClose(CallbackInfo ci) {
        LogisticsPanel.beforeContainerClose((AbstractContainerScreen<?>)(Object)this);
    }
}
