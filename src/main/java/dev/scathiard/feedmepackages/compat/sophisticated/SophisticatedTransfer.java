package dev.scathiard.feedmepackages.compat.sophisticated;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.network.TransferOrigin;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/** The server derives every slot and item from its current native menu and registered recipe. */
public final class SophisticatedTransfer {
    private SophisticatedTransfer() {}
    public record Request(int menu, int upgrade, UUID cache, ResourceLocation recipe, boolean maximum,
                          TransferOrigin.Viewer viewer) implements CustomPacketPayload {
        public Request {
            Objects.requireNonNull(cache); Objects.requireNonNull(recipe); Objects.requireNonNull(viewer);
            if (menu < 0 || upgrade < 0 || upgrade > 512 || viewer == TransferOrigin.Viewer.UNKNOWN) throw new IllegalArgumentException("Invalid crafting request");
        }
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "sophisticated_crafting"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b, r) -> {
            b.writeVarInt(r.menu); b.writeVarInt(r.upgrade); b.writeUUID(r.cache); b.writeUtf(r.recipe.toString(), 256);
            b.writeBoolean(r.maximum); b.writeByte(r.viewer.ordinal());
        }, b -> {
            if (b.readableBytes() > 1056) throw new IllegalArgumentException("Oversized crafting request");
            int menu = b.readVarInt(), upgrade = b.readVarInt(); var cache = b.readUUID(); var recipe = ResourceLocation.parse(b.readUtf(256));
            int maximum = b.readUnsignedByte(), viewer = b.readUnsignedByte();
            if (maximum > 1 || viewer < 1 || viewer >= TransferOrigin.Viewer.values().length || b.isReadable()) throw new IllegalArgumentException("Malformed crafting request");
            return new Request(menu, upgrade, cache, recipe, maximum == 1, TransferOrigin.Viewer.values()[viewer]);
        });
        @Override public Type<Request> type() { return TYPE; }
    }
    private static final Map<ServerPlayer, long[]> BUDGETS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_NOTICE = new WeakHashMap<>();
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("astra-sophisticated-1").playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player && !accept(player, request)) {
                long tick = player.serverLevel().getGameTime(); Long previous = LAST_NOTICE.get(player);
                if (previous == null || tick < previous || tick - previous >= 20) {
                    LAST_NOTICE.put(player, tick);
                    player.sendSystemMessage(Component.translatable("gui.create_feed_me_packages.result.stale"));
                }
            }
        });
    }
    public static boolean accept(ServerPlayer player, Request request) {
        return apply(player, request) == SophisticatedMaterials.Transfer.Result.OK;
    }
    @SuppressWarnings("unchecked")
    public static SophisticatedMaterials.Transfer.Result apply(ServerPlayer player, Request request) {
        if (!player.getServer().isSameThread() || !SophisticatedAccess.enabled() || !request.viewer.enabled()) return SophisticatedMaterials.Transfer.Result.STALE;
        long tick = player.serverLevel().getGameTime(); var budget = BUDGETS.computeIfAbsent(player, p -> new long[]{tick, 0});
        if (tick < budget[0] || tick - budget[0] >= 20) { budget[0] = tick; budget[1] = 0; }
        if (++budget[1] > 16) return SophisticatedMaterials.Transfer.Result.STALE;
        var recipe = player.serverLevel().getRecipeManager().byKey(request.recipe).orElse(null);
        if (recipe == null || !(recipe.value() instanceof CraftingRecipe)) return SophisticatedMaterials.Transfer.Result.STALE;
        var transfer = SophisticatedMaterials.prepare(player, request.menu, request.upgrade, request.cache,
                (RecipeHolder<CraftingRecipe>) recipe, request.maximum);
        if (transfer == null) return SophisticatedMaterials.Transfer.Result.STALE;
        transfer.execute(); return transfer.result();
    }
}
