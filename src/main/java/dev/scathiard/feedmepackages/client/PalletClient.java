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
    private static boolean cargo, queuedFeed;
    private static final dev.scathiard.feedmepackages.domain.PalletMorph morph = new dev.scathiard.feedmepackages.domain.PalletMorph();
    private static PalletMaterial confirmed = PalletMaterial.EMPTY;
    private static final dev.scathiard.feedmepackages.domain.PalletFluidFeedback fluidFeedback = new dev.scathiard.feedmepackages.domain.PalletFluidFeedback();
    private static double cogFrom,cogSince,cogDuration;
    private static dev.scathiard.feedmepackages.domain.PalletFeedPose interrupted;
    private static double time(float partial) {
        var level=Minecraft.getInstance().level;
        return level==null ? 0 : (level.getGameTime()+partial)/20.0;
    }
    public static void resetMotion() { fluidFeedback.reset(); cogFrom=cogSince=cogDuration=0; feedStarted=-1; cargo=false; stockVersion=-1; interrupted=null; queuedFeed=false; morph.snap(!confirmed.fluid().isEmpty()); }
    public static dev.scathiard.feedmepackages.domain.PalletMorph.Pose modePose(float partial) { return morph.pose(time(partial)); }
    public static boolean hasCargo() { return cargo; }
    public static dev.scathiard.feedmepackages.domain.PalletFluidFeedback.Pose fluidPose(float partial) { return fluidFeedback.pose(time(partial)); }
    private static void cancelFluidFeedback() { fluidFeedback.cancel(time(0)); }
    public static float cogAngle(float partial) { return (float)(cogAt(time(partial))%360); }
    private static double cogAt(double now) {
        double t=cogDuration==0?0:Math.clamp((now-cogSince)/cogDuration,0,1);
        return -40*now+cogFrom-360*t*t*(3-2*t);
    }
    private static void turnCog(double duration) {
        double now=time(0);cogFrom=(cogAt(now)+40*now)%360;cogSince=now;cogDuration=duration;
    }
    public static long feedReceipts() { return feedReceipts; }
    public static PalletMaterial displayed() { return confirmed; }
    public static dev.scathiard.feedmepackages.domain.PalletFeedPose feedPose(float partial) {
        if(!modePose(partial).itemReady()) return dev.scathiard.feedmepackages.domain.PalletFeedPose.at(-1,false);
        return dev.scathiard.feedmepackages.domain.PalletFeedPose.interrupted(interrupted,feedStarted<0 ? -1 : time(partial)-feedStarted,cargo);
    }
    private static void animate(boolean next) {
        cargo=next; stockVersion=version();
        if(!confirmed.fluid().isEmpty()) { queuedFeed=false;feedStarted=-1;interrupted=null;return; }
        if(!modePose(0).itemReady()) { queuedFeed=true;feedStarted=-1;interrupted=null;return; }
        interrupted=feedPose(0);feedStarted=time(0);queuedFeed=false;turnCog(.35);
    }
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
                boolean changed=!confirmed.equals(selected);
                boolean modeChanged=confirmed.fluid().isEmpty()!=selected.fluid().isEmpty();
                confirmed=selected;
                morph.target(!confirmed.fluid().isEmpty(),time(0));
                if(changed) { cancelFluidFeedback(); animate(!confirmed.isEmpty() && amount(confirmed)>0); }
                if(modeChanged)turnCog(.3);
            }
        });
        PalletNetwork.receiveFeedOnClient(message -> {
            var mc=Minecraft.getInstance(); var choice=message.selection();
            if(mc.player==null || !PalletService.holding(mc.player) || !Objects.equals(cache,choice.cache()) || sequence!=choice.sequence()
                    || selected.isEmpty() || !selected.encoded().equals(choice.encoded())) return;
            feedReceipts++; animate(message.hasNext());
            if(!confirmed.fluid().isEmpty() && confirmed.equals(selected)) {
                fluidFeedback.success(time(0));
                turnCog(.24);
            }
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
            // The reserved last meal is still in the jaws. Cancelling its use returns
            // availability; it is not a newly delivered item and must not replay feed.
            boolean nextCargo=!confirmed.isEmpty() && (candidates().contains(confirmed) || usingCargo(player,confirmed.item()));
            if(!cargo && nextCargo && stockVersion>=0) animate(nextCargo);
            cargo=nextCargo;stockVersion=version();
        }
        if(queuedFeed && modePose(0).itemReady()) animate(cargo);
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
    static boolean usingCargo(net.minecraft.client.player.LocalPlayer player,ItemStack cargo) {
        if(player==null || !player.isUsingItem() || player.getUsedItemHand()!=net.minecraft.world.InteractionHand.MAIN_HAND) return false;
        var used=player.getUseItem();var animation=used.getUseAnimation();
        return (animation==UseAnim.EAT || animation==UseAnim.DRINK) && ItemStack.isSameItemSameComponents(used,cargo);
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
        if(!selected.equals(stack)) cancelFluidFeedback();
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
