package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real mouse/key callbacks exercise the actual picker and the native use-on packet. */
public final class PalletClientReview {
    private static int stage, ticks, changed, moves;
    private static CompletableFuture<Void> work;
    private static UUID cache;
    private static List<Item> blocks;
    private static boolean highlightPending, highlightVerified;
    private static RuntimeException renderFailure;
    private static BlockPos placed, broken;
    private static long receipts;
    private static final Minecraft MC = Minecraft.getInstance();
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (renderFailure != null) throw renderFailure;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed < 12 && stage != 3 && stage != 17 && stage != 20 && stage != 26) return false;
        switch (stage) {
            case 0 -> {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                        false, net.neoforged.neoforge.client.event.ScreenEvent.Render.Post.class, PalletClientReview::rendered);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                        false,net.neoforged.neoforge.client.event.RenderTooltipEvent.Pre.class,PalletClientReview::tooltipRendered);
                server(PalletClientReview::setup); next();
            }
            case 1 -> {
                if (!ClientMaterials.active() || PalletClient.candidates().size() != 34 || !Objects.equals(cache, ClientMaterials.cacheId())) return false;
                MC.setScreen(null); MC.mouseHandler.grabMouse(); org.lwjgl.glfw.GLFW.glfwFocusWindow(MC.getWindow().getWindow());
                MC.player.setYRot(0); MC.player.setXRot(40);
                if(Boolean.getBoolean("fmp.palletQuietReview")){stage=39;changed=ticks;return false;}
                button(2, true); next();
            }
            case 2 -> {
                require(MC.screen instanceof PalletScreen && spiral(), "Default middle button did not open spiral mode");
                for(String name:List.of("whole","fixed","tray_slide","jaw_left","jaw_right","outlet_gate","fallback"))
                    require(MC.getModelManager().getModel(PalletRenderer.modelId(name))!=MC.getModelManager().getMissingModel(),"Missing pallet model "+name);
                TemplateClientReview.capture("pallet-arc-default");
                verifyVisualContract();
                // Prime the native post-grab first-move rule before starting the measured gesture.
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                moves = 0; next();
            }
            case 3 -> {
                // Native wheel reaches all items without dragging beyond a physical window edge.
                moves++;
                if(moves<=69){scroll(-1);return false;}
                if(moves==70){requireFocus(1);TemplateClientReview.capture("pallet-arc-forward-wraps");return false;}
                if(moves<=140){scroll(1);return false;}
                requireFocus(33);TemplateClientReview.capture("pallet-arc-reverse-wraps");next();
            }
            case 4 -> { button(2, false); next(); }
            case 5 -> {
                server(p -> require(PalletService.choice(p).key().stack(p.registryAccess(), 1).is(blocks.get(33)),
                        "Repeated forward and reverse head-tail wraps did not select item 33"));
                button(2, true); moves = 0; next();
            }
            case 6 -> {
                if (moves++ == 0) {
                    double cx = MC.getWindow().getGuiScaledWidth() / 2.0, cy = MC.getWindow().getGuiScaledHeight() / 2.0;
                    moveGui(cx,cy);moveGui(cx-pixelsPerCell()*.59,cy);requireFocus(0);
                    moveGui(cx-pixelsPerCell()*.50,cy);requireFocus(0);
                    highlightPending = true; changed = ticks; return false;
                }
                if (!highlightVerified) return false;
                button(2, false); next();
            }
            case 7 -> {
                require(PalletClient.selected().is(blocks.get(0)), "Release canceled the horizontal drag choice");
                server(p -> require(PalletService.choice(p).key().stack(p.registryAccess(), 1).is(blocks.get(0)),
                        "Horizontal detent release did not reach the server"));
                button(2, true);
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                moveGui(MC.getWindow().getGuiScaledWidth()/2.0-pixelsPerCell(),MC.getWindow().getGuiScaledHeight()/2.0);requireFocus(1);
                key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE); button(2, false);
                require(MC.screen == null && PalletClient.selected().is(blocks.get(0)), "Escape failed to cancel a different candidate");
                ClientBootstrap.PALLET_MODE.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_R));
                KeyMapping.resetMapping(); button(2, true); key(org.lwjgl.glfw.GLFW.GLFW_KEY_R); next();
            }
            case 8 -> {
                require(!spiral(), "Rebound mode key did not switch to grid while Middle was held");
                for (int i = 0; i < 12; i++) scroll(-1); next();
            }
            case 9 -> { TemplateClientReview.capture("pallet-grid-scrolled"); releaseTile(33); next(); }
            case 10 -> {
                server(p -> require(PalletService.choice(p).key().stack(p.registryAccess(), 1).is(blocks.get(33)), "Last grid item was inaccessible"));
                button(2, true); key(org.lwjgl.glfw.GLFW.GLFW_KEY_E); type("e");
                require(MC.screen instanceof PalletScreen, "Inventory key intercepted a search letter");
                key(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE); type("石头"); next();
            }
            case 11 -> {
                require(shown().size() >= 1 && shown().stream().allMatch(s -> s.getHoverName().getString().contains("石头")), "Native input did not filter grid names");
                TemplateClientReview.capture("pallet-search");
                int index = 0; while (index < shown().size() && !shown().get(index).is(Items.STONE)) index++;
                require(index < shown().size(), "Stone missing from search"); releaseTile(index); next();
            }
            case 12 -> {
                require(PalletClient.selected().is(Items.STONE), "Grid search did not select stone");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_G));
                KeyMapping.resetMapping(); keyState(org.lwjgl.glfw.GLFW.GLFW_KEY_G, true); next();
            }
            case 13 -> {
                require(MC.screen instanceof PalletScreen, "Rebound keyboard key did not open the picker");
                key(org.lwjgl.glfw.GLFW.GLFW_KEY_R); require(spiral(), "Grid did not switch back to spiral");
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                keyState(org.lwjgl.glfw.GLFW.GLFW_KEY_G, false); next();
            }
            case 14 -> {
                require(MC.screen == null && PalletClient.selected().is(Items.STONE), "Keyboard release did not preserve the candidate at center");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(4));
                KeyMapping.resetMapping(); button(4, true); next();
            }
            case 15 -> {
                require(MC.screen instanceof PalletScreen && spiral(), "Rebound mouse key did not open retained spiral mode");
                button(4, false);
                require(MC.screen == null && PalletClient.selected().is(Items.STONE), "Rebound mouse release did not confirm");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(2));
                ClientBootstrap.PALLET_MODE.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB));
                KeyMapping.resetMapping(); next();
            }
            case 16 -> {
                MC.player.setYRot(0); MC.player.setXRot(40);
                require(MC.hitResult instanceof BlockHitResult, "Native ray missed placement support");
                var hit = (BlockHitResult) MC.hitResult; placed = hit.getBlockPos().relative(hit.getDirection());
                receipts=PalletClient.feedReceipts();button(1, true); next();
            }
            case 17 -> { button(1, false); next(); }
            case 18 -> {
                require(PalletClient.feedReceipts()==receipts+1,"Committed placement did not emit exactly one feed receipt");
                server(p -> {
                    require(p.level().getBlockState(placed).is(Blocks.STONE), "Native right click did not place selected stone");
                    var r = CacheLedger.get(p.getServer()).find(cache);
                    require(r.state().cells().getFirst().amount() == 6 && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()), "Placement lost stock or tool");
                    var edit = r.state().edit(); edit.extract(0, 6);
                    CacheLedger.get(p.getServer()).replace(AccessGate.resolve(p).handle(), r.state().revision(), r.withState(edit.finish()));
                }); TemplateClientReview.capture("pallet-native-placement"); next();
            }
            case 19 -> {
                if (PalletClient.candidates().stream().anyMatch(s -> s.is(Items.STONE))) return false;
                require(PalletClient.selected().is(Items.STONE), "Depleted selection was replaced");
                button(1, true); next();
            }
            case 20 -> { button(1, false); next(); }
            case 21 -> {
                require(PalletClient.feedReceipts()==receipts+1,"Empty placement emitted a fake feed receipt");
                require(!PalletClient.feedPose(0).cargo(),"Empty cache still displays cargo");
                server(p -> {
                    var r = CacheLedger.get(p.getServer()).find(cache);
                    require(r.state().cells().getFirst().amount() == 0 && r.state().cells().get(1).amount() == 7
                            && PalletService.choice(p).key().stack(p.registryAccess(), 1).is(Items.STONE), "Depletion spent replacement stock");
                    TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack()); AccessGate.resolve(p);
                }); next();
            }
            case 22 -> {
                if (Objects.equals(cache, ClientMaterials.cacheId())) return false;
                require(PalletClient.selected().isEmpty(), "Cache switch kept the old choice");
                button(2, true); next();
            }
            case 23 -> {
                require(MC.screen instanceof PalletScreen && shown().isEmpty(), "Empty cache did not open empty picker");
                server(p -> p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)); next();
            }
            case 24 -> {
                if (MC.screen != null) return false;
                button(2, false);
                server(p -> { p.setItemInHand(InteractionHand.MAIN_HAND, FmpRegistries.POCKET_PALLET.toStack()); p.setGameMode(GameType.CREATIVE); });
                next();
            }
            case 25 -> {
                require(MC.hitResult instanceof BlockHitResult && MC.player.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()), "Test pallet not restored");
                broken = ((BlockHitResult) MC.hitResult).getBlockPos(); button(0, true); next();
            }
            case 26 -> { button(0, false); next(); }
            case 27 -> {
                require(!(MC.screen instanceof PalletScreen), "Default left click still opened selector");
                server(p -> require(p.level().getBlockState(broken).isAir() && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()),
                        "Native mining remained blocked while holding the tool")); next();
            }
            case 28 -> {
                MC.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(MC.player));next();
            }
            case 29 -> {
                TemplateClientReview.capture("pallet-inventory-model");MC.setScreen(null);
                MC.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.LEFT);MC.options.broadcastOptions();next();
            }
            case 30 -> {
                require(MC.player.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT,"Left hand option did not reach the player");
                verifyHandBody();TemplateClientReview.capture("pallet-left-hand-model");
                MC.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);MC.options.broadcastOptions();
                MC.options.setCameraType(CameraType.THIRD_PERSON_FRONT);next();
            }
            case 31 -> {
                TemplateClientReview.capture("pallet-third-person-model");MC.options.setCameraType(CameraType.FIRST_PERSON);
                work=MC.reloadResourcePacks();next();
            }
            case 32 -> {
                for(String name:List.of("whole","fixed","tray_slide","jaw_left","jaw_right","outlet_gate","create_body_fixed","create_cog","feed_socket_fixed","rails_fixed","head_fixed","fluid_head","fluid_pipe","nozzle_collar","nozzle_middle","nozzle_tip"))
                    require(MC.getModelManager().getModel(PalletRenderer.modelId(name))!=MC.getModelManager().getMissingModel(),"Resource reload lost model "+name);
                require(!PalletClient.feedPose(0).cargo(),"Resource reload replayed stale cargo");
                TemplateClientReview.capture("pallet-after-resource-reload");next();
            }
            case 33 -> { server(PalletClientReview::setup);next(); }
            case 34 -> {
                if(PalletClient.candidates().size()!=34 || !Objects.equals(cache,ClientMaterials.cacheId())) return false;
                MC.options.guiScale().set(1);MC.resizeDisplay();button(2,true);next();
            }
            case 35 -> { verifyVisualContract();TemplateClientReview.capture("pallet-arc-scale1");key(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);next(); }
            case 36 -> {
                verifyGridBounds();TemplateClientReview.capture("pallet-grid-scale1");key(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
                MC.options.guiScale().set(3);MC.resizeDisplay();next();
            }
            case 37 -> { verifyVisualContract();TemplateClientReview.capture("pallet-arc-scale3");key(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);next(); }
            case 38 -> { verifyGridBounds();TemplateClientReview.capture("pallet-grid-scale3");key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);button(2,false);MC.options.guiScale().set(2);MC.resizeDisplay();next(); }
            case 39 -> {
                MC.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("FMP quiet-placement sentinel"),false);
                server(p -> {
                    var ledger=CacheLedger.get(p.getServer());var row=ledger.find(cache);var key=row.state().cells().getFirst().filter();
                    require(PalletService.choose(p,cache,key.encoded()),"Silent rejection fixture could not select stock");
                    p.serverLevel().setBlockAndUpdate(new BlockPos(8,4,10),Blocks.AIR.defaultBlockState());
                    p.serverLevel().setBlockAndUpdate(new BlockPos(8,3,10),Blocks.STONE.defaultBlockState());
                    var observed=new java.util.concurrent.atomic.AtomicBoolean();
                    Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> cancel=event -> {
                        if(event.getEntity()==p){observed.set(true);event.setCanceled(true);}
                    };
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
                    try {
                        var hit=new BlockHitResult(new net.minecraft.world.phys.Vec3(8.5,4,10.5),Direction.UP,new BlockPos(8,3,10),false);
                        require(p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit)==net.minecraft.world.InteractionResult.FAIL,
                                "Canceled native placement reported success");
                    } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel); }
                    require(observed.get(),"Native place cancellation was not reached");
                    require(p.serverLevel().getBlockState(new BlockPos(8,4,10)).isAir() && ledger.find(cache).state().cells().getFirst().amount()==7,
                            "Quiet failure changed world or stock");
                });next();
            }
            case 40 -> {
                require(overlay().equals("FMP quiet-placement sentinel"),"Native failed placement still sends action-bar text: "+overlay());
                server(p -> emitFailure(p,"no_placement"));next();
            }
            case 41 -> {
                require(overlay().equals("FMP quiet-placement sentinel"),"Uncommitted placement still sends action-bar text");
                server(p -> emitFailure(p,"no_selection"));next();
            }
            case 42 -> {
                require(overlay().equals(net.minecraft.network.chat.Component.translatable("message.create_feed_me_packages.pallet.no_selection").getString()),
                        "Removing failure text also hid selection guidance");
                MC.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(),false);
                FeedMePackages.LOGGER.info("FMP_PALLET_QUIET_FAILURE_PASSED nativeCancel=true stockUnchanged=true noPlacementSilent=true selectionGuidanceRetained=true");
                if(Boolean.getBoolean("fmp.palletQuietReview")) return true;
                FeedMePackages.LOGGER.info("FMP_PALLET_CLIENT_PASSED defaultMiddle=true defaultArc=true forwardSteps=69 reverseSteps=70 nativeWrap=true nativeReverse=true nativeBlur=true keyboardAndMouseRebind=true horizontalDrag=true hysteresis=true escapeCancel=true brassArcHighlight=true authoritativeFeed=true gridSearch=true nativePlace=true nativeMiningWithTool=true");
                FeedMePackages.LOGGER.info("FMP_PALLET_MODELS_CLIENT_PASSED bakedParts=16 inventory=true leftHand=true thirdPerson=true resourceReload=true fixedBody=true lowerHalf=true guiScales=1,2,3");
                return true;
            }
        }
        return false;
    }
    private static String overlay() throws ReflectiveOperationException {
        var field=net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");field.setAccessible(true);
        var message=(net.minecraft.network.chat.Component)field.get(MC.gui);return message==null?"":message.getString();
    }
    private static void emitFailure(ServerPlayer player,String reason) {
        try {
            var method=PalletService.class.getDeclaredMethod("failed",ServerPlayer.class,String.class);method.setAccessible(true);
            require(method.invoke(null,player,reason)==net.minecraft.world.InteractionResult.FAIL,"Failure hint changed the action result");
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static void tooltipRendered(net.neoforged.neoforge.client.event.RenderTooltipEvent.Pre event) {
        if(!(MC.screen instanceof PalletScreen)) return;
        try {
            if(spiral()) return;
            var method=PalletScreen.class.getDeclaredMethod("gridTarget",double.class,double.class);method.setAccessible(true);
            var target=(PalletMaterial)method.invoke(MC.screen,MC.mouseHandler.xpos()/MC.getWindow().getGuiScale(),MC.mouseHandler.ypos()/MC.getWindow().getGuiScale());
            require(!target.isEmpty(),"Grid showed the prior selection tooltip without hovering a tile");
        } catch(ReflectiveOperationException | RuntimeException failure) { renderFailure=new IllegalStateException("Grid tooltip hover check failed",failure); }
    }
    private static void verifyGridBounds() throws ReflectiveOperationException {
        require(!spiral(),"Expected grid after mode switch");
        var method=PalletScreen.class.getDeclaredMethod("layout");method.setAccessible(true);
        var grid=(PalletLayout.View)method.invoke(MC.screen);
        require(!grid.tiles().isEmpty(),"Resize lost the available candidates");
        for(var tile:grid.tiles()) require(tile.y()>=MC.screen.height/2 && tile.y()+24<MC.screen.height-22,"Grid escaped lower half");
        var field=PalletScreen.class.getDeclaredField("search");field.setAccessible(true);
        var search=(net.minecraft.client.gui.components.EditBox)field.get(MC.screen);
        require(search.getY()-4>=MC.screen.height/2,"Search escaped lower half");
        FeedMePackages.LOGGER.info("FMP_PALLET_GRID_CHECK scale={} tiles={} lowerHalf=true",MC.getWindow().getGuiScale(),grid.tiles().size());
    }
    /** Calls the native first-person path, then compares emitted fixed-body vertices. */
    private static void verifyVisualContract() throws ReflectiveOperationException {
        var method=PalletScreen.class.getDeclaredMethod("arcLayout");method.setAccessible(true);
        var arc=method.invoke(MC.screen);
        double outer=coordinate(arc,"outer"),inner=coordinate(arc,"inner"),cy=coordinate(arc,"cy"),unit=coordinate(arc,"unit");
        double top=cy-outer-7*unit;
        double thickness=(outer-inner)/(2*outer*Math.sin(.66));
        verifyHandBody();
        FeedMePackages.LOGGER.info("FMP_PALLET_LAYOUT_CHECK top={} midline={} bandWidthRatio={}",top,MC.screen.height/2.0,thickness);
        require(top>=MC.screen.height/2.0 && thickness<=.11,"Pallet geometry crosses midline or band is too thick");
    }
    private static void verifyHandBody() throws ReflectiveOperationException {
        var idle=handVertices(FmpRegistries.POCKET_PALLET.toStack(),0,0);
        var swing=handVertices(FmpRegistries.POCKET_PALLET.toStack(),.5f,0);
        var lowered=handVertices(FmpRegistries.POCKET_PALLET.toStack(),0,.75f);
        boolean stableSwing=sameVertices(idle,swing),stableUse=sameVertices(idle,lowered);
        FeedMePackages.LOGGER.info("FMP_PALLET_BODY_CHECK arm={} stableSwing={} stableUse={}",MC.player.getMainArm(),stableSwing,stableUse);
        require(stableSwing && stableUse,"Pallet fixed-body vertices moved during vanilla hand animation");
        require(!sameVertices(handVertices(new ItemStack(Items.STONE),0,0),handVertices(new ItemStack(Items.STONE),.5f,.75f)),
                "Native ordinary item animation was suppressed");
    }
    private static List<Float> handVertices(ItemStack stack,float swing,float equip) throws ReflectiveOperationException {
        var vertices=new ArrayList<Float>();
        var consumer=new com.mojang.blaze3d.vertex.VertexConsumer() {
            @Override public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x,float y,float z) {
                if(vertices.size()<72){vertices.add(x);vertices.add(y);vertices.add(z);}return this;
            }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r,int g,int b,int a){return this;}
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u,float v){return this;}
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u,int v){return this;}
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u,int v){return this;}
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x,float y,float z){return this;}
        };
        var method=net.minecraft.client.renderer.ItemInHandRenderer.class.getDeclaredMethod("renderArmWithItem",
                net.minecraft.client.player.AbstractClientPlayer.class,float.class,float.class,InteractionHand.class,float.class,
                ItemStack.class,float.class,com.mojang.blaze3d.vertex.PoseStack.class,net.minecraft.client.renderer.MultiBufferSource.class,int.class);
        method.setAccessible(true);
        method.invoke(MC.gameRenderer.itemInHandRenderer,MC.player,0f,0f,InteractionHand.MAIN_HAND,swing,stack,equip,
                new com.mojang.blaze3d.vertex.PoseStack(),(net.minecraft.client.renderer.MultiBufferSource)type->consumer,15728880);
        require(vertices.size()==72,"Native renderer did not emit fixed-body geometry");return vertices;
    }
    private static boolean sameVertices(List<Float> a,List<Float> b) {
        for(int i=0;i<a.size();i++)if(Math.abs(a.get(i)-b.get(i))>0.00001)return false;
        return true;
    }
    private static void setup(ServerPlayer p) {
        p.getInventory().clearContent();p.setGameMode(GameType.SURVIVAL);TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        var handle=AccessGate.resolve(p).handle();cache=handle.cacheId();var ledger=CacheLedger.get(p.getServer());var old=ledger.find(cache);
        var edit=old.state().edit();for(int i=1;i<5;i++)edit.upgrade();
        blocks=BuiltInRegistries.ITEM.stream().filter(item -> item instanceof BlockItem && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")).limit(35).toList();
        require(blocks.getFirst()==Items.STONE,"Unexpected vanilla registry order");
        for(int i=0;i<34;i++){var key=ItemVariantKey.of(new ItemStack(blocks.get(i)),p.registryAccess());edit.filter(i,key);edit.insert(i,key,7);}
        // Weapons remain excluded after materials/foods were admitted.
        var weapon=ItemVariantKey.of(new ItemStack(Items.BOW),p.registryAccess());edit.filter(34,weapon);edit.insert(34,weapon,7);
        edit.filter(35,ItemVariantKey.of(new ItemStack(blocks.get(34)),p.registryAccess()));
        var state=edit.finish();var frame=old;
        ledger.replace(handle,old.state().revision(),frame.withState(state));p.setItemInHand(InteractionHand.MAIN_HAND,FmpRegistries.POCKET_PALLET.toStack());
        // The template world is high above this fixture; give the survival player a real floor
        // and reset the fall accumulated before teleporting into the test area.
        for (int x=4;x<=12;x++) for(int z=4;z<=12;z++)
            p.serverLevel().setBlockAndUpdate(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState());
        p.teleportTo(8.5,4,8.5);p.fallDistance=0;p.setHealth(p.getMaxHealth());
    }
    private static void rendered(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        if (!highlightPending || !(event.getScreen() instanceof PalletScreen)) return;
        try {
            var navField=PalletScreen.class.getDeclaredField("navigation");navField.setAccessible(true);
            var nav=(PalletSelector)navField.get(MC.screen);
            // The selected frame fades while moving. Measure its real resting border,
            // not a pixel accidentally covered by the old oversized center pointer.
            if(Math.abs(nav.position()-nav.anchor())>0.00001) return;
            highlightPending = false;
            var method=PalletScreen.class.getDeclaredMethod("arcLayout");method.setAccessible(true);
            var view=method.invoke(MC.screen);
            double unit=coordinate(view,"unit"),outer=coordinate(view,"outer"),inner=coordinate(view,"inner"),cx=coordinate(view,"cx"),cy=coordinate(view,"cy");
            event.getGuiGraphics().flush();
            try(var pixels=Screenshot.takeScreenshot(MC.getMainRenderTarget())) {
                double scale=MC.getWindow().getGuiScale();
                double[][] points={{cx,cy-outer+1.5*unit},{cx,cy-inner-1.5*unit}};
                for(int i=0;i<points.length;i++) {
                    int actual=pixels.getPixelRGBA((int)(points[i][0]*scale),(int)(points[i][1]*scale));
                    require(actual==0xff7dc1df,"Arc brass border covered/dimmed at sample "+i+": "+Integer.toHexString(actual));
                }
            }
            TemplateClientReview.capture("pallet-brass-highlight");
            highlightVerified = true;
            FeedMePackages.LOGGER.info("FMP_PALLET_HIGHLIGHT_PASSED annularBrass=true samples=2");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            renderFailure = new IllegalStateException("Pallet highlight framebuffer check failed", failure);
        }
    }
    private static boolean spiral() throws ReflectiveOperationException {var f=PalletScreen.class.getDeclaredField("spiral");f.setAccessible(true);return f.getBoolean(MC.screen);}
    @SuppressWarnings("unchecked") private static List<PalletMaterial> shown() throws ReflectiveOperationException {var f=PalletScreen.class.getDeclaredField("shown");f.setAccessible(true);return (List<PalletMaterial>)f.get(MC.screen);}
    private static void releaseTile(int index) throws ReflectiveOperationException {
        var method=PalletScreen.class.getDeclaredMethod("layout");method.setAccessible(true);var layout=(PalletLayout.View)method.invoke(MC.screen);
        var tile=layout.tiles().stream().filter(t->t.index()==index).findFirst().orElseThrow();moveGui(tile.x()+12,tile.y()+12);button(2,false);
    }
    private static double coordinate(Object record,String name) throws ReflectiveOperationException {
        var method=record.getClass().getDeclaredMethod(name);method.setAccessible(true);return ((Number)method.invoke(record)).doubleValue();
    }
    private static double pixelsPerCell() throws ReflectiveOperationException {
        var method=PalletScreen.class.getDeclaredMethod("arcLayout");method.setAccessible(true);
        return coordinate(method.invoke(MC.screen),"pixelsPerCell");
    }
    private static void requireFocus(int index) throws ReflectiveOperationException {
        var field = PalletScreen.class.getDeclaredField("focus"); field.setAccessible(true);
        require(((PalletMaterial) field.get(MC.screen)).is(blocks.get(index)), "Native spiral hover differs at index " + index);
    }
    private static void keyState(int key, boolean down) throws ReflectiveOperationException {
        var method = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key, 0, down ? 1 : 0, 0);
    }
    private static void moveGui(double x,double y) throws ReflectiveOperationException {
        var method=MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);method.setAccessible(true);
        method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),x*MC.getWindow().getGuiScale(),y*MC.getWindow().getGuiScale());
        // Client ticks can run several times in one render frame. Drain the native movement dispatcher
        // per sample so it cannot coalesce the starting arc; do not call Screen or PalletSpiral directly.
        MC.mouseHandler.handleAccumulatedMovement();
    }
    private static void button(int button,boolean down) throws ReflectiveOperationException {var method=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),button,down?1:0,0);}
    private static void scroll(double y) throws ReflectiveOperationException {var method=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0.0,y);}
    private static void key(int key) throws ReflectiveOperationException {var method=KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,1,0);method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,0,0);}
    private static void type(String text) throws ReflectiveOperationException {var method=KeyboardHandler.class.getDeclaredMethod("charTyped",long.class,int.class,int.class);method.setAccessible(true);for(int point:text.codePoints().toArray())method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),point,0);}
    private static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=CompletableFuture.runAsync(()->action.accept(server.getPlayerList().getPlayer(id)),server);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void next(){FeedMePackages.LOGGER.info("FMP_PALLET_STAGE {} complete",stage);stage++;changed=ticks;}
}
