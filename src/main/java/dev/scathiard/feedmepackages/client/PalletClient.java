package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.PalletNetwork;
import com.mojang.blaze3d.platform.InputConstants;
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
    private static double feedStarted = -1;
    private static long stockVersion = -1, feedReceipts;
    private static boolean cargo;
    private static PalletMaterial confirmed = PalletMaterial.EMPTY;
    private static dev.scathiard.feedmepackages.domain.PalletFeedPose interrupted;
    private static double time(float partial) {
        var level=Minecraft.getInstance().level;
        return level==null ? 0 : (level.getGameTime()+partial)/20.0;
    }
    public static void resetMotion() { feedStarted=-1; cargo=false; stockVersion=-1; interrupted=null; }
    public static long feedReceipts() { return feedReceipts; }
    public static PalletMaterial displayed() { return confirmed; }
    public static dev.scathiard.feedmepackages.domain.PalletFeedPose feedPose(float partial) {
        return dev.scathiard.feedmepackages.domain.PalletFeedPose.interrupted(interrupted,feedStarted<0 ? -1 : time(partial)-feedStarted,cargo);
    }
    private static void animate(boolean next) { interrupted=feedPose(0);cargo=next; stockVersion=version(); feedStarted=time(0); }
    private PalletClient() {}
    private static UUID cache;
    private static PalletMaterial selected = PalletMaterial.EMPTY;
    private static dev.scathiard.feedmepackages.network.PalletFluids.Stock fluids;
    private static long fluidVersion;
    public static long version() { return ClientMaterials.version()+fluidVersion; }
    private static int sequence;
    private static boolean spiral = true;
    public static void register() {
        PalletService.clientUse(PalletClient::use);
        dev.scathiard.feedmepackages.consumption.PalletUse.clientSelection(() -> selected().item());
        dev.scathiard.feedmepackages.network.PalletFluids.receive(stock -> { fluids=stock;fluidVersion++; });
        if(net.neoforged.fml.ModList.get().isLoaded("superbwarfare") && dev.scathiard.feedmepackages.mixin.OptionalMixins.sbwCompatible())
            dev.scathiard.feedmepackages.compat.sbw.SbwMaterials.clientSources(() -> ClientCompatibility.sbwSourcesAvailable()?ClientMaterials.stacks():List.of());
        PalletNetwork.receiveOnClient(message -> {
            if (Minecraft.getInstance().player == null) return;
            if (Objects.equals(cache, message.cache()) && sequence == message.sequence()) {
                selected = PalletMaterial.decode(message.encoded());
                if(!confirmed.equals(selected)) animate(!selected.isEmpty());
                confirmed=selected;
            }
        });
        PalletNetwork.receiveFeedOnClient(message -> {
            var mc=Minecraft.getInstance(); var choice=message.selection();
            if(mc.player==null || !PalletService.holding(mc.player) || !Objects.equals(cache,choice.cache()) || sequence!=choice.sequence()
                    || selected.isEmpty() || !selected.encoded().equals(choice.encoded())) return;
            feedReceipts++; animate(message.hasNext());
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { sync(); if(Minecraft.getInstance().player!=null) dev.scathiard.feedmepackages.consumption.PalletUse.tick(Minecraft.getInstance().player); });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { if(event.getPlayer()!=null) dev.scathiard.feedmepackages.consumption.PalletUse.forget(event.getPlayer()); cache = null; selected = confirmed = PalletMaterial.EMPTY; sequence = 0; fluids=null;fluidVersion++; resetMotion(); });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> {
            if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS &&
                    open(InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> {
            if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                open(InputConstants.getKey(event.getKey(), event.getScanCode()));
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) -> {
            if (event.getItemStack().getItem() instanceof dev.scathiard.feedmepackages.item.PocketPalletItem)
                event.getToolTip().add(Component.translatable("item.create_feed_me_packages.pocket_pallet.tip",
                        ClientBootstrap.PALLET_SELECT.getTranslatedKeyMessage(), Minecraft.getInstance().options.keyUse.getTranslatedKeyMessage()));
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            var mc = Minecraft.getInstance(); sync();
            if (mc.player == null || !PalletService.holding(mc.player) || selected.isEmpty()) return;
            var g = event.getGuiGraphics(); int x = Math.min(g.guiWidth() / 2 + 96, g.guiWidth() - 96), y = g.guiHeight() - 22;
            selected.render(g, x, y);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(selected.getHoverName().getString(), g.guiWidth() - x - 22), x + 19, y + 5, 0xffeeeeee, true);
        });
    }
    private static boolean open(InputConstants.Key key) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null
                || !PalletService.holding(mc.player) || !ClientBootstrap.PALLET_SELECT.isActiveAndMatches(key)) return false;
        sync();
        if (ClientMaterials.active()) mc.setScreen(new PalletScreen(spiral));
        else mc.player.displayClientMessage(Component.translatable("message.create_feed_me_packages.pallet.no_access"), true);
        releaseKey(key);
        return true;
    }
    static void releaseKey(InputConstants.Key key) {
        net.minecraft.client.KeyMapping.set(key, false);
        for (var mapping : Minecraft.getInstance().options.keyMappings)
            if (mapping.getKey().equals(key)) {
                mapping.setDown(false);
                while (mapping.consumeClick()) { /* This gesture must not replay as attack, use or pick block. */ }
            }
    }
    public static void sync() {
        UUID next = ClientMaterials.cacheId();
        if (!Objects.equals(cache, next)) { cache = next; selected = confirmed = PalletMaterial.EMPTY; resetMotion(); }
        var player=Minecraft.getInstance().player;
        if(player==null || !PalletService.holding(player) || !ClientMaterials.active()) { resetMotion(); return; }
        if(stockVersion!=version()) {
            boolean nextCargo=!selected.isEmpty() && candidates().contains(selected);
            if(!cargo && nextCargo && stockVersion>=0) { interrupted=feedPose(0);feedStarted=time(0); }
            cargo=nextCargo;stockVersion=version();
        }
    }
    public static UUID cache() { sync(); return cache; }
    public static PalletMaterial selected() { sync(); return selected; }
    public static boolean selected(PalletMaterial material) { return selected().equals(material); }
    public static List<PalletMaterial> candidates() {
        var all=new ArrayList<PalletMaterial>();
        ClientMaterials.stacks().stream().filter(dev.scathiard.feedmepackages.consumption.PalletUse::supported).map(PalletMaterial::of).forEach(all::add);
        if(fluids!=null && Objects.equals(fluids.cache(),ClientMaterials.cacheId()))
            fluids.templates().forEach(key -> all.add(PalletMaterial.decode("fluid:"+key)));
        return List.copyOf(all);
    }
    public static long amount(PalletMaterial material) {
        if(!material.fluid().isEmpty()) {
            if(fluids==null || !Objects.equals(fluids.cache(),ClientMaterials.cacheId())) return 0;
            int index=fluids.templates().indexOf(material.encoded().substring(6));
            return index<0 ? 0 : fluids.amounts().get(index);
        }
        return ClientMaterials.stacks().stream().filter(s->ItemStack.isSameItemSameComponents(s,material.item())).mapToLong(ItemStack::getCount).sum();
    }
    public static void choose(PalletMaterial stack) {
        sync(); var mc = Minecraft.getInstance();
        if (cache == null || mc.player == null || !ClientMaterials.active() || stack.isEmpty()) return;
        selected = stack;
        PacketDistributor.sendToServer(new PalletNetwork.Selection(++sequence, cache, selected.encoded()));
    }
    public static void mode(boolean value) { spiral = value; }
    private static InteractionResult use(UseOnContext context) {
        sync();
        if (!ClientMaterials.active() || selected.isEmpty() || candidates().stream().noneMatch(PalletClient::selected)) return InteractionResult.FAIL;
        if(!selected.fluid().isEmpty()) return InteractionResult.CONSUME;
        var material = selected.item(); var hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        var result = material.useOn(new UseOnContext(context.getLevel(), context.getPlayer(), context.getHand(), material, hit));
        return result.consumesAction() ? result : InteractionResult.FAIL;
    }
}
