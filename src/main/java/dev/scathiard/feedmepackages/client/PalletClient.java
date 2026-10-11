package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.PalletNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class PalletClient {
    private PalletClient() {}
    private static UUID cache;
    private static ItemStack selected = ItemStack.EMPTY;
    private static int sequence;
    private static boolean spiral;
    public static void register() {
        PalletService.clientUse(PalletClient::use);
        PalletNetwork.receiveOnClient(message -> {
            if (Minecraft.getInstance().player == null) return;
            if (Objects.equals(cache, message.cache()) && sequence == message.sequence())
                selected = message.encoded().isEmpty() ? ItemStack.EMPTY : ItemVariantKey.decode(message.encoded(), Minecraft.getInstance().player.registryAccess())
                        .stack(Minecraft.getInstance().player.registryAccess(), 1);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> sync());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { cache = null; selected = ItemStack.EMPTY; sequence = 0; });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || !PalletService.holding(mc.player) || !event.isAttack()) return;
            event.setCanceled(true); event.setSwingHand(false); sync();
            if (mc.screen == null) {
                if (ClientMaterials.active()) mc.setScreen(new PalletScreen(spiral));
                else mc.player.displayClientMessage(Component.translatable("message.create_feed_me_packages.pallet.no_access"), true);
            }
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            var mc = Minecraft.getInstance(); sync();
            if (mc.player == null || !PalletService.holding(mc.player) || selected.isEmpty()) return;
            var g = event.getGuiGraphics(); int x = Math.min(g.guiWidth() / 2 + 96, g.guiWidth() - 96), y = g.guiHeight() - 22;
            g.renderItem(selected, x, y);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(selected.getHoverName().getString(), g.guiWidth() - x - 22), x + 19, y + 5, 0xffeeeeee, true);
        });
    }
    public static void sync() {
        UUID next = ClientMaterials.cacheId();
        if (!Objects.equals(cache, next)) { cache = next; selected = ItemStack.EMPTY; }
    }
    public static UUID cache() { sync(); return cache; }
    public static ItemStack selected() { sync(); return selected.copy(); }
    public static boolean selected(ItemStack stack) { return ItemStack.isSameItemSameComponents(selected(), stack); }
    public static List<ItemStack> candidates() { return ClientMaterials.stacks().stream().filter(stack -> stack.getItem() instanceof BlockItem).map(stack -> stack.copyWithCount(1)).toList(); }
    public static void choose(ItemStack stack) {
        sync(); var mc = Minecraft.getInstance();
        if (cache == null || stack.isEmpty()) return;
        selected = stack.copyWithCount(1);
        PacketDistributor.sendToServer(new PalletNetwork.Selection(++sequence, cache, ItemVariantKey.of(selected, mc.player.registryAccess()).encoded()));
    }
    public static void mode(boolean value) { spiral = value; }
    private static InteractionResult use(UseOnContext context) {
        sync();
        if (!ClientMaterials.active() || selected.isEmpty() || candidates().stream().noneMatch(PalletClient::selected)) return InteractionResult.FAIL;
        var material = selected.copyWithCount(1); var hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        var result = material.useOn(new UseOnContext(context.getLevel(), context.getPlayer(), context.getHand(), material, hit));
        return result.consumesAction() ? result : InteractionResult.FAIL;
    }
}
