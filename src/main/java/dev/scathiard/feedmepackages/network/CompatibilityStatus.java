package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.compat.FactoryLogistics;
import dev.scathiard.feedmepackages.compat.PhantomDispatch;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility;
import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import dev.scathiard.feedmepackages.compat.maid.MaidCouriers;
import dev.scathiard.feedmepackages.compat.maid.phantom.PhantomMaidCompat;
import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess;
import dev.scathiard.feedmepackages.compat.tacz.TaczMaterials;
import dev.scathiard.feedmepackages.compat.wand.WandMaterials;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Reports server-side optional mod adapter availability to connected clients so that
 * {@link dev.scathiard.feedmepackages.client.ClientCompatibility} can warn once if a mod is
 * installed and enabled in config but failed its bytecode/API gate.
 */
public final class CompatibilityStatus {
    private CompatibilityStatus() {}

    public enum State {
        ABSENT,
        READY,
        CONFIG_DISABLED,
        API_MISMATCH
    }

    public record Message(
            State tacz,
            State wand,
            State gadgets,
            State effortless,
            State fxnt,
            State emi,
            State sophisticated,
            State maid,
            State factory,
            State sbw,
            State craftingTweaks,
            State paper,
            State bee,
            State phantom,
            State biotech)
            implements CustomPacketPayload {

        public Message {
            Objects.requireNonNull(tacz);
            Objects.requireNonNull(wand);
            Objects.requireNonNull(gadgets);
            Objects.requireNonNull(effortless);
            Objects.requireNonNull(fxnt);
            Objects.requireNonNull(emi);
            Objects.requireNonNull(sophisticated);
            Objects.requireNonNull(maid);
            Objects.requireNonNull(factory);
            Objects.requireNonNull(sbw);
            Objects.requireNonNull(craftingTweaks);
            Objects.requireNonNull(paper);
            Objects.requireNonNull(bee);
            Objects.requireNonNull(phantom);
            Objects.requireNonNull(biotech);
        }

        public Message(
                State tacz,
                State wand,
                State gadgets,
                State effortless,
                State fxnt,
                State emi,
                State sophisticated,
                State maid,
                State factory,
                State sbw) {
            this(
                    tacz,
                    wand,
                    gadgets,
                    effortless,
                    fxnt,
                    emi,
                    sophisticated,
                    maid,
                    factory,
                    sbw,
                    State.ABSENT,
                    State.ABSENT,
                    State.ABSENT,
                    State.ABSENT,
                    State.ABSENT);
        }

        public Message(
                State tacz,
                State wand,
                State gadgets,
                State effortless,
                State fxnt,
                State emi,
                State sophisticated,
                State maid,
                State factory) {
            this(tacz, wand, gadgets, effortless, fxnt, emi, sophisticated, maid, factory, State.ABSENT);
        }

        public Message(
                State tacz,
                State wand,
                State gadgets,
                State effortless,
                State fxnt,
                State emi,
                State sophisticated,
                State maid) {
            this(tacz, wand, gadgets, effortless, fxnt, emi, sophisticated, maid, State.ABSENT);
        }

        public static final Type<Message> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "compatibility_status"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = StreamCodec.of(
                (b, m) -> {
                    b.writeByte(m.tacz.ordinal());
                    b.writeByte(m.wand.ordinal());
                    b.writeByte(m.gadgets.ordinal());
                    b.writeByte(m.effortless.ordinal());
                    b.writeByte(m.fxnt.ordinal());
                    b.writeByte(m.emi.ordinal());
                    b.writeByte(m.sophisticated.ordinal());
                    b.writeByte(m.maid.ordinal());
                    b.writeByte(m.factory.ordinal());
                    b.writeByte(m.sbw.ordinal());
                    b.writeByte(m.craftingTweaks.ordinal());
                    b.writeByte(m.paper.ordinal());
                    b.writeByte(m.bee.ordinal());
                    b.writeByte(m.phantom.ordinal());
                    b.writeByte(m.biotech.ordinal());
                },
                b -> {
                    PanelPackets.bound(b, 15);
                    var states = new State[15];
                    for (int i = 0; i < states.length; i++) {
                        int value = b.readUnsignedByte();
                        if (value >= State.values().length) {
                            throw new IllegalArgumentException("Unknown compatibility status");
                        }
                        states[i] = State.values()[value];
                    }
                    PanelPackets.end(b);
                    return new Message(
                            states[0],
                            states[1],
                            states[2],
                            states[3],
                            states[4],
                            states[5],
                            states[6],
                            states[7],
                            states[8],
                            states[9],
                            states[10],
                            states[11],
                            states[12],
                            states[13],
                            states[14]);
                });

        @Override
        public Type<Message> type() {
            return TYPE;
        }
    }

    private static final Map<ServerPlayer, Message> LAST = new WeakHashMap<>();
    private static final Map<ServerPlayer, UUID> SUSPENDED = new WeakHashMap<>();
    private static Consumer<Message> receiver = message -> {};

    public static void receiveOnClient(Consumer<Message> value) {
        receiver = Objects.requireNonNull(value);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("compat-materials-8");
        registrar.playToClient(Message.TYPE, Message.CODEC, (message, context) -> receiver.accept(message));
        registrar.playToServer(TransferOrigin.Message.TYPE, TransferOrigin.Message.CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                TransferOrigin.accept(player, message);
            }
        });
    }

    public static State classify(boolean installed, boolean configured, boolean shaped) {
        if (!installed) return State.ABSENT;
        if (!configured) return State.CONFIG_DISABLED;
        if (!shaped) return State.API_MISMATCH;
        return State.READY;
    }

    public static State fxnt() {
        return classify(ModList.get().isLoaded("fxntstorage"), FmpConfig.fxntMaterialsEnabled(), OptionalMixins.fxntTransferCompatible());
    }

    public static State emi() {
        return classify(ModList.get().isLoaded("emi"), FmpConfig.emiCraftingEnabled(), OptionalMixins.emiTransferCompatible());
    }

    public static State sophisticated() {
        return classify(SophisticatedAccess.installed(), FmpConfig.sophisticatedEnabled(), SophisticatedAccess.ready());
    }

    public static State factory() {
        return classify(FactoryLogistics.installed(), FmpConfig.factoryLogisticsEnabled(), FactoryLogistics.ready());
    }

    public static State sbw() {
        return classify(ModList.get().isLoaded("superbwarfare"), FmpConfig.sbwEnabled(), OptionalMixins.sbwCompatible());
    }

    public static State maid() {
        return classify(ModList.get().isLoaded("touhou_little_maid"), FmpConfig.maidEnabled(), MaidCompatibility.ready());
    }

    public static State tacz() {
        return classify(ModList.get().isLoaded("tacz"), FmpConfig.taczAmmoEnabled(), TaczMaterials.ready());
    }

    public static State wand() {
        return classify(ModList.get().isLoaded("constructionwand"), FmpConfig.constructionWandEnabled(), WandMaterials.ready());
    }

    public static State effortless() {
        return classify(ModList.get().isLoaded("effortlessbuilding"), FmpConfig.effortlessEnabled(), OptionalMixins.effortlessCompatible());
    }

    public static State gadgets() {
        return classify(ModList.get().isLoaded("buildinggadgets2"), FmpConfig.gadgetsEnabled(), OptionalMixins.gadgetsCompatible());
    }

    public static State craftingTweaks() {
        return classify(ModList.get().isLoaded("craftingtweaks"), true, OptionalMixins.craftingTweaksCompatible());
    }

    public static State paper() {
        boolean maid = ModList.get().isLoaded("touhou_little_maid") && FmpConfig.maidEnabled();
        return classify(
                ModList.get().isLoaded("cmpackagecouriers"),
                true,
                TransportDispatch.paperReady() && (!maid || MaidCouriers.paperReady()));
    }

    public static State bee() {
        boolean maid = ModList.get().isLoaded("touhou_little_maid") && FmpConfig.maidEnabled();
        return classify(
                ModList.get().isLoaded("create_mobile_packages"),
                true,
                TransportDispatch.beeReady() && (!maid || MaidCouriers.beeReady()));
    }

    public static State phantom() {
        boolean maid = ModList.get().isLoaded("touhou_little_maid") && FmpConfig.maidEnabled();
        return classify(
                ModList.get().isLoaded("createphantom"),
                true,
                PhantomDispatch.ready() && (!maid || PhantomMaidCompat.ready()));
    }

    public static State biotech() {
        return classify(ModList.get().isLoaded("create_biotech"), true, BiotechCompatibility.ready());
    }

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 20 != 0) {
            return;
        }
        var now = new Message(
                tacz(),
                wand(),
                gadgets(),
                effortless(),
                fxnt(),
                emi(),
                sophisticated(),
                maid(),
                factory(),
                sbw(),
                craftingTweaks(),
                paper(),
                bee(),
                phantom(),
                biotech());
        if (!now.equals(LAST.put(player, now))) {
            PacketDistributor.sendToPlayer(player, now);
        }
        var access = AccessGate.resolve(player);
        var record = access.active() ? CacheLedger.get(player.getServer()).find(access.handle().cacheId()) : null;
        if (record != null && record.fluids().hasSuspendedResiduals()) {
            if (!record.state().id().equals(SUSPENDED.put(player, record.state().id()))) {
                player.sendSystemMessage(Component.translatable(
                        "message.create_feed_me_packages.compat.fluid_residual_suspended",
                        record.fluids().suspendedResiduals().size()));
            }
        } else {
            SUSPENDED.remove(player);
        }
    }

    public static void forget(ServerPlayer player) {
        LAST.remove(player);
        SUSPENDED.remove(player);
        TransferOrigin.forget(player);
    }
}
