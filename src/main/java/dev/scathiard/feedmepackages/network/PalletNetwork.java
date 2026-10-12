package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

/** Selection packets carry no materials and never place blocks; placement remains a native use packet. */
public final class PalletNetwork {
    private PalletNetwork() {}
    private static Consumer<Selection> client = selection -> {};
    private static final Map<ServerPlayer, long[]> LIMITS = new WeakHashMap<>();
    public static void receiveOnClient(Consumer<Selection> receiver) { client = Objects.requireNonNull(receiver); }
    public record Selection(int sequence, UUID cache, String encoded) implements CustomPacketPayload {
        public Selection {
            Objects.requireNonNull(cache); Objects.requireNonNull(encoded);
            if (sequence <= 0 || encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) throw new IllegalArgumentException("Invalid pallet selection");
        }
        public static final Type<Selection> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "pallet_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Selection> CODEC = StreamCodec.of((b, m) -> {
            b.writeVarInt(m.sequence); b.writeUUID(m.cache); PanelPackets.text(b, m.encoded, 1024);
        }, b -> {
            if (b.readableBytes() > 1100) throw new IllegalArgumentException("Oversized pallet selection");
            var m = new Selection(b.readVarInt(), b.readUUID(), PanelPackets.text(b, 1024)); PanelPackets.end(b); return m;
        });
        @Override public Type<Selection> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("astra-pallet-1").playBidirectional(Selection.TYPE, Selection.CODEC, (message, context) -> {
            if (context.flow().isClientbound()) { client.accept(message); return; }
            if (!(context.player() instanceof ServerPlayer player)) return;
            var limit = LIMITS.computeIfAbsent(player, ignored -> new long[]{player.tickCount, 0});
            if (player.tickCount - limit[0] >= 20 || player.tickCount < limit[0]) { limit[0] = player.tickCount; limit[1] = 0; }
            // Never silently retain another block when this selection was rejected: a following
            // native right-click packet must fail, not place the player's previous material.
            if (++limit[1] > 8 || !PalletService.choose(player, message.cache, message.encoded))
                PalletService.cancelChoice(player, message.cache);
            var choice = PalletService.choice(player);
            PacketDistributor.sendToPlayer(player, new Selection(message.sequence, message.cache,
                    choice != null && choice.cache().equals(message.cache) ? choice.key().encoded() : ""));
        });
    }
}
