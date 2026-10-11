package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Runtime port availability, not inventory authority or saved state. Server and client shapes can differ. */
public final class CompatibilityStatus {
    private CompatibilityStatus() {}
    public enum State { ABSENT, READY, CONFIG_DISABLED, API_MISMATCH }
    public record Message(State tacz, State wand, State gadgets, State effortless, State fxnt, State emi, State sophisticated, State maid) implements CustomPacketPayload {
        public Message { java.util.Objects.requireNonNull(tacz); java.util.Objects.requireNonNull(wand); java.util.Objects.requireNonNull(gadgets); java.util.Objects.requireNonNull(effortless); java.util.Objects.requireNonNull(fxnt); java.util.Objects.requireNonNull(emi); java.util.Objects.requireNonNull(sophisticated); java.util.Objects.requireNonNull(maid); }
        public static final Type<Message> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "compatibility_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = StreamCodec.of((b, m) -> { b.writeByte(m.tacz.ordinal()); b.writeByte(m.wand.ordinal()); b.writeByte(m.gadgets.ordinal()); b.writeByte(m.effortless.ordinal()); b.writeByte(m.fxnt.ordinal()); b.writeByte(m.emi.ordinal()); b.writeByte(m.sophisticated.ordinal()); b.writeByte(m.maid.ordinal()); }, b -> {
            PanelPackets.bound(b, 8); var states = new State[8];
            for (int i = 0; i < states.length; i++) { int value = b.readUnsignedByte(); if (value >= State.values().length) throw new IllegalArgumentException("Unknown compatibility status"); states[i] = State.values()[value]; }
            PanelPackets.end(b); return new Message(states[0], states[1], states[2], states[3], states[4], states[5], states[6], states[7]);
        });
        @Override public Type<Message> type() { return TYPE; }
    }
    private static final Map<ServerPlayer, Message> LAST = new WeakHashMap<>();
    private static Consumer<Message> receiver = message -> {};
    public static void receiveOnClient(Consumer<Message> value) { receiver = java.util.Objects.requireNonNull(value); }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("converged-compat-3");
        registrar.playToClient(Message.TYPE, Message.CODEC, (message, context) -> receiver.accept(message));
        registrar.playToServer(TransferOrigin.Message.TYPE, TransferOrigin.Message.CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player) TransferOrigin.accept(player, message);
        });

    }
    public static State classify(boolean installed, boolean configured, boolean shaped) {
        return !installed ? State.ABSENT : !configured ? State.CONFIG_DISABLED : !shaped ? State.API_MISMATCH : State.READY;
    }
    public static State fxnt() { return classify(ModList.get().isLoaded("fxntstorage"), FmpConfig.fxntMaterialsEnabled(), OptionalMixins.fxntTransferCompatible()); }
    public static State emi() { return classify(ModList.get().isLoaded("emi"), FmpConfig.emiCraftingEnabled(), OptionalMixins.emiTransferCompatible()); }
    public static State sophisticated() { return classify(ModList.get().isLoaded("sophisticatedbackpacks"), FmpConfig.sophisticatedEnabled(), dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.ready()); }
    public static State maid() { return classify(ModList.get().isLoaded("touhou_little_maid"), FmpConfig.maidEnabled(), dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.ready()); }
    public static State tacz() { return classify(ModList.get().isLoaded("tacz"), FmpConfig.taczAmmoEnabled(),
            dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.ready()); }
    public static State wand() { return classify(ModList.get().isLoaded("constructionwand"), FmpConfig.constructionWandEnabled(),
            dev.scathiard.feedmepackages.compat.wand.WandMaterials.ready()); }
    public static State effortless() { return classify(ModList.get().isLoaded("effortlessbuilding"), FmpConfig.effortlessEnabled(), OptionalMixins.effortlessCompatible()); }
    public static State gadgets() { return classify(ModList.get().isLoaded("buildinggadgets2"), FmpConfig.gadgetsEnabled(), OptionalMixins.gadgetsCompatible()); }
    public static void tick(ServerPlayer player) {
        if (player.tickCount % 20 != 0) return;
        var now = new Message(tacz(), wand(), gadgets(), effortless(), fxnt(), emi(), sophisticated(), maid());
        if (!now.equals(LAST.put(player, now))) PacketDistributor.sendToPlayer(player, now);

    }
    public static void forget(ServerPlayer player) { LAST.remove(player); TransferOrigin.forget(player); }
}
