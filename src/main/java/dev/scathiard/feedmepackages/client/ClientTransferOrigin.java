package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.network.TransferOrigin;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.concurrent.atomic.AtomicLong;

/** Brackets the original send, without changing its payload, arguments or return value. */
public final class ClientTransferOrigin {
    private ClientTransferOrigin() {}
    private static final AtomicLong SEQUENCE = new AtomicLong();
    public static void send(TransferOrigin.Viewer viewer, Runnable original) {
        var player = Minecraft.getInstance().player;
        if (player == null || !ClientCompatibility.fxntSourcesAvailable()) { original.run(); return; }
        long nonce = SEQUENCE.incrementAndGet(); int menu = player.containerMenu.containerId;
        PacketDistributor.sendToServer(new TransferOrigin.Message(nonce, menu, viewer, true));
        try { original.run(); }
        finally { PacketDistributor.sendToServer(new TransferOrigin.Message(nonce, menu, viewer, false)); }
    }
}
