package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.Map;
import java.util.WeakHashMap;

/** One-shot browser hint for a foreign packet that has no origin field. Never grants inventory access. */
public final class TransferOrigin {
    private TransferOrigin() {}
    public enum Viewer {
        UNKNOWN, JEI, EMI;
        public boolean enabled() {
            return switch (this) {
                case JEI -> FmpConfig.jeiCraftingEnabled();
                case EMI -> FmpConfig.emiCraftingEnabled();
                // Untagged callers may use cache only if neither browser is disabled.
                case UNKNOWN -> FmpConfig.jeiCraftingEnabled() && FmpConfig.emiCraftingEnabled();
            };
        }
    }
    public record Message(long nonce, int menu, Viewer viewer, boolean begin) implements CustomPacketPayload {
        public Message {
            if (nonce <= 0 || menu < 0 || viewer == null || viewer == Viewer.UNKNOWN)
                throw new IllegalArgumentException("Invalid transfer origin");
        }
        public static final Type<Message> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "transfer_origin"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = StreamCodec.of((b, m) -> {
            b.writeLong(m.nonce); b.writeInt(m.menu); b.writeByte(m.viewer.ordinal()); b.writeBoolean(m.begin);
        }, b -> {
            PanelPackets.bound(b, 14); long nonce = b.readLong(); int menu = b.readInt(); int viewer = b.readUnsignedByte(), phase = b.readUnsignedByte();
            PanelPackets.end(b);
            if (viewer < 1 || viewer >= Viewer.values().length || phase > 1) throw new IllegalArgumentException("Unknown transfer origin");
            return new Message(nonce, menu, Viewer.values()[viewer], phase == 1);
        });
        @Override public Type<Message> type() { return TYPE; }
    }
    private record Pending(long nonce, AbstractContainerMenu menu, Viewer viewer, long tick) {}
    private static final Map<ServerPlayer, Pending> PENDING = new WeakHashMap<>();
    public static void accept(ServerPlayer player, Message message) {
        if (!player.getServer().isSameThread()) return;
        if (message.begin()) {
            PENDING.remove(player);
            if (player.containerMenu.containerId == message.menu())
                PENDING.put(player, new Pending(message.nonce(), player.containerMenu, message.viewer(), player.serverLevel().getGameTime()));
        } else {
            var pending = PENDING.get(player);
            if (pending != null && pending.nonce() == message.nonce()) PENDING.remove(player);
        }
    }
    /** Capture before foreign enqueueWork; the later END cannot change this call's captured origin. */
    public static Viewer claim(ServerPlayer player) {
        if (!player.getServer().isSameThread()) return Viewer.UNKNOWN;
        var pending = PENDING.remove(player);
        if (pending == null || pending.menu() != player.containerMenu) return Viewer.UNKNOWN;
        long age = player.serverLevel().getGameTime() - pending.tick();
        return age >= 0 && age <= 20 ? pending.viewer() : Viewer.UNKNOWN;
    }
    public static void forget(ServerPlayer player) { PENDING.remove(player); }
}
