package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in cursor diagnostics; default clients log neither stack objects nor container packets. */
@Mixin(ClientPacketListener.class)
public abstract class ClientContentDiagnosticMixin {
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void fmp$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (!Boolean.getBoolean("fmp.cursorDiagnostics") || !dev.scathiard.feedmepackages.client.LogisticsPanel.panelOnScreen()) return;
        var player = Minecraft.getInstance().player;
        FeedMePackages.LOGGER.info("FMP_CONTENT container={} carriedCount={} playerCarriedCount={}",
                packet.getContainerId(), packet.getCarriedItem().getCount(), player == null ? 0 : player.containerMenu.getCarried().getCount());
    }
}
