package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Intake and take use real services/inputs. Request/departure drawing and the benchmark use explicitly synthetic server signals. */
public final class FeedbackClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static ReceiveTests.Fixture fixture;
    private static int stage,ticks,changed,warmFrames;
    private static CompletableFuture<Void> work;
    private static Throwable frameFailure;
    private static boolean arrivalShot,requestFirst,requestSecond,departureShot,affordancesChecked;
    private static long frameStart,lastFrame,bindingRevision;
    private static UUID bindingNetwork;
    private static BlockPos bindingPos;
    private static ItemStack unboundPendant;
    private static final List<Long> idleCpu=new ArrayList<>(),litCpu=new ArrayList<>(),idleFrames=new ArrayList<>(),litFrames=new ArrayList<>();
    private static final Consumer<ScreenEvent.Render.Pre> PRE=event->{
        if(event.getScreen()==MC.screen && MC.screen instanceof InventoryScreen)frameStart=System.nanoTime();
    };
    private static final Consumer<ScreenEvent.Render.Post> POST=FeedbackClientReview::rendered;
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(frameFailure!=null)throw new IllegalStateException("Feedback rendering probe failed",frameFailure);
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        if(stage<14 && ticks-changed<8)return false;
        switch(stage){
            case 0 -> {
                NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,ScreenEvent.Render.Pre.class,PRE);
                NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ScreenEvent.Render.Post.class,POST);
                server(FeedbackClientReview::setup);next();
            }
            case 1 -> {
                if(!ClientMaterials.active() || !Objects.equals(ClientMaterials.cacheId(),fixture.handle().cacheId()))return false;
                MC.setScreen(new InventoryScreen(MC.player));next();
            }
            case 2 -> {
                var snapshot=TemplateClientReview.snapshot();
                if(snapshot==null || LogisticsPanel.visibleCells(MC.screen).size()!=9)return false;
                next();
            }
            case 3 -> {
                if(!affordancesChecked)return false;
                require(active().isEmpty(),"Opening the panel created a pulse");TemplateClientReview.capture("feedback-idle");
                server(p->require(ReceiveService.receive(p,fixture.box(16)),"Native intake failed"));next();
            }
            case 4 -> { if(!arrivalShot)return false;require(TemplateClientReview.snapshot().cells().getFirst().amount()==80,"Arrival display quantity differs");next(); }
            case 5 -> { if(!active().isEmpty())return false;TemplateClientReview.cellClick(0,0);next(); }
            case 6 -> { if(MC.player.containerMenu.getCarried().isEmpty())return false;TemplateClientReview.nativeSlotClick(9);next(); }
            case 7 -> {
                if(TemplateClientReview.snapshot().cells().getFirst().amount()!=16 || !MC.player.containerMenu.getCarried().isEmpty())return false;
                require(MC.player.getInventory().getItem(9).is(Items.STONE) && MC.player.getInventory().getItem(9).getCount()==64 && active().isEmpty(),"Native local take changed logistics feedback or failed conservation");
                server(p->LogisticsSignals.publish(fixture.handle().cacheId(),0,LogisticsSignal.Kind.REQUEST));next();
            }
            case 8 -> {
                if(!requestFirst || !requestSecond || !active().isEmpty())return false;
                server(p->LogisticsSignals.publish(fixture.handle().cacheId(),0,LogisticsSignal.Kind.DEPARTURE));next();
            }
            case 9 -> {
                if(!departureShot || !active().isEmpty())return false;
                MC.player.closeContainer();MC.setScreen(null);next();
            }
            case 10 -> { server(p->require(ReceiveService.receive(p,fixture.box(8)),"Closed-window native intake failed"));next(); }
            case 11 -> { MC.setScreen(new InventoryScreen(MC.player));next(); }
            case 12 -> {
                var snapshot=TemplateClientReview.snapshot();if(snapshot==null || snapshot.cells().getFirst().amount()!=24)return false;
                require(snapshot.cells().getFirst().signal().sequence()==0 && active().isEmpty(),"Reopening replayed closed-window events");
                TemplateClientReview.capture("feedback-reopened-quiet");server(FeedbackClientReview::expandForBenchmark);next();
            }
            case 13 -> { if(TemplateClientReview.snapshot().cells().size()!=36)return false;warmFrames=0;next(); }
            case 14 -> { if(warmFrames>=120){lastFrame=0;next();} }
            case 15 -> { if(idleCpu.size()>=120){lastFrame=0;warmFrames=0;next();} }
            case 16 -> {
                if(ticks%4==0)server(p->{for(int i=0;i<36;i++)LogisticsSignals.publish(fixture.handle().cacheId(),i,LogisticsSignal.Kind.ARRIVAL);});
                if(warmFrames>=120){lastFrame=0;next();}
            }
            case 17 -> {
                if(litCpu.size()<120){if(ticks%4==0)server(p->{for(int i=0;i<36;i++)LogisticsSignals.publish(fixture.handle().cacheId(),i,LogisticsSignal.Kind.ARRIVAL);});return false;}
                long idle=median(idleCpu),lit=median(litCpu),idleInterval=median(idleFrames),litInterval=median(litFrames);
                FeedMePackages.LOGGER.info("FMP_FEEDBACK_RENDER_BENCH visible={} samples=120 cpuIdleMs={} cpuLitMs={} frameIdleMs={} frameLitMs={} cpuIdleP95Ms={} cpuLitP95Ms={} frameIdleP95Ms={} frameLitP95Ms={}",LogisticsPanel.visibleCells(MC.screen).size(),idle/1e6,lit/1e6,idleInterval/1e6,litInterval/1e6,percentile95(idleCpu)/1e6,percentile95(litCpu)/1e6,percentile95(idleFrames)/1e6,percentile95(litFrames)/1e6);
                require(lit<=Math.max(idle*1.25,idle+1_000_000),"Lit border median render cost exceeded the declared 1ms/25% allowance");
                require(litInterval<=Math.max(idleInterval*1.15,idleInterval+2_000_000),"Lit border median frame interval exceeded the declared 2ms/15% allowance");
                NeoForge.EVENT_BUS.unregister(PRE);NeoForge.EVENT_BUS.unregister(POST);next();
            }
            case 18 -> {
                MC.player.closeContainer();MC.setScreen(null);MC.mouseHandler.grabMouse();
                org.lwjgl.glfw.GLFW.glfwFocusWindow(MC.getWindow().getWindow());
                server(p->{
                    var pendant=TestPlayers.necklace(p).getStackInSlot(0);TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);
                    pendant.remove(FmpRegistries.NETWORK.get());p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,pendant);
                    unboundPendant=pendant.copy();bindingRevision=fixture.record().state().revision();bindingPos=new BlockPos(8,5,10);
                    p.serverLevel().setBlockAndUpdate(bindingPos.below(),Blocks.STONE.defaultBlockState());
                    p.serverLevel().setBlockAndUpdate(bindingPos,com.simibubi.create.AllBlocks.STOCK_TICKER.get().defaultBlockState());
                    var link=com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(p.level(),bindingPos,
                            com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.TYPE);
                    require(link!=null,"Native stock ticker has no network behaviour");bindingNetwork=link.freqId;
                });next();
            }
            case 19 -> {
                if(!MC.player.getMainHandItem().is(FmpRegistries.PENDANT.get()))return false;
                MC.player.setYRot(0);MC.player.setXRot(0);next();
            }
            case 20 -> {
                if(!(MC.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || !hit.getBlockPos().equals(bindingPos))return false;
                worldUse(true);next();
            }
            case 21 -> {worldUse(false);next();}
            case 22 -> {
                if(!bindingNetwork.equals(MC.player.getMainHandItem().get(FmpRegistries.NETWORK.get())))return false;
                require(MC.screen==null,"Binding opened the stock ticker menu");MC.player.setXRot(-90);next();
            }
            case 23 -> {
                if(MC.hitResult.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)return false;
                worldUse(true);next();
            }
            case 24 -> {worldUse(false);next();}
            case 25 -> {
                server(p->require(bindingNetwork.equals(p.getMainHandItem().get(FmpRegistries.NETWORK.get())),"Native ordinary air use cleared binding"));
                sneak(true);next();
            }
            case 26 -> {if(!MC.player.isShiftKeyDown())return false;worldUse(true);next();}
            case 27 -> {worldUse(false);sneak(false);next();}
            case 28 -> {
                if(MC.player.getMainHandItem().has(FmpRegistries.NETWORK.get()))return false;
                server(p->require(ItemStack.isSameItemSameComponents(unboundPendant,p.getMainHandItem())
                        && fixture.record().state().revision()==bindingRevision,"Native clearing changed pendant identity or cache"));next();
            }
            case 29 -> {
                FeedMePackages.LOGGER.info("FMP_BINDING_CLIENT_PASSED nativeRightClickDevice=true noDeviceMenu=true plainAirRetained=true sneakAirCleared=true cachePreserved=true");return true;
            }
        }
        return false;
    }
    private static void worldUse(boolean down) throws ReflectiveOperationException {
        var method=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);
        method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),1,down?1:0,0);
    }
    private static void sneak(boolean down) throws ReflectiveOperationException {
        var method=net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);method.setAccessible(true);
        method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT,0,down?1:0,0);
    }
    private static void setup(ServerPlayer player){
        player.getInventory().clearContent();player.setGameMode(GameType.SURVIVAL);TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        var handle=AccessGate.resolve(player).handle();var ledger=CacheLedger.get(player.getServer());var before=ledger.find(handle.cacheId());
        var key=ItemVariantKey.of(new ItemStack(Items.STONE),player.registryAccess());var order=UUID.randomUUID();var edit=before.state().edit();
        edit.filter(0,key);edit.thresholds(0,2,-1);edit.request(0,order,player.getUUID(),64,false);edit.insert(0,key,64);
        ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
        fixture=new ReceiveTests.Fixture(player,ledger,handle,key,order);
        for(int x=4;x<=12;x++)for(int z=4;z<=12;z++)player.serverLevel().setBlockAndUpdate(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState());
        player.teleportTo(8.5,4,8.5);player.fallDistance=0;player.setHealth(player.getMaxHealth());
    }
    private static void expandForBenchmark(ServerPlayer player){
        var before=fixture.record();var state=before.state();for(int i=1;i<5;i++){var edit=state.edit();edit.upgrade();state=edit.finish();}
        var edit=state.edit();var blocks=BuiltInRegistries.ITEM.stream().filter(item->item instanceof BlockItem && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")).limit(36).toList();
        for(int i=1;i<36;i++){var key=ItemVariantKey.of(new ItemStack(blocks.get(i)),player.registryAccess());edit.filter(i,key);edit.insert(i,key,32);}
        state=edit.finish();state=new CacheState<>(state.id(),state.level(),before.state().revision()+1,state.cells(),state.orders());
        fixture.ledger().replace(fixture.handle(),before.state().revision(),before.withState(state));
    }
    private static void rendered(ScreenEvent.Render.Post event){
        if(frameFailure!=null || event.getScreen()!=MC.screen || !(MC.screen instanceof InventoryScreen))return;
        try{
            if(stage==3 && !affordancesChecked){verifyCellAffordances(event.getGuiGraphics());affordancesChecked=true;}
            // Flush queued UI draws in both samples; this measures submission, not GPU completion.
            if(stage>=14)event.getGuiGraphics().flush();
            long now=System.nanoTime();
            if(stage==14)warmFrames++;
            boolean allLit=stage>=16 && active().size()==LogisticsPanel.visibleCells(MC.screen).size();
            if(stage==16 && allLit)warmFrames++;
            if(stage==15 || stage==17 && allLit){
                var cpu=stage==15?idleCpu:litCpu;var frames=stage==15?idleFrames:litFrames;
                if(cpu.size()<120){cpu.add(now-frameStart);if(lastFrame!=0)frames.add(now-lastFrame);lastFrame=now;}
            }
            var pulse=active().get(0);if(pulse==null)return;
            var kind=(LogisticsSignal.Kind)field(pulse,"kind");long age=net.minecraft.Util.getMillis()-(long)field(pulse,"started");
            String shot=null;
            if(stage==4 && kind==LogisticsSignal.Kind.ARRIVAL && age>=280 && age<550 && !arrivalShot){arrivalShot=true;shot="feedback-arrival-mid";}
            if(stage==8 && kind==LogisticsSignal.Kind.REQUEST){
                if(age<100 && !requestFirst){requestFirst=true;shot="feedback-request-first";}
                else if(age>=200 && age<300 && !requestSecond){requestSecond=true;shot="feedback-request-second";}
            }
            if(stage==9 && kind==LogisticsSignal.Kind.DEPARTURE && age>=280 && age<550 && !departureShot){departureShot=true;shot="feedback-departure-mid";}
            if(shot!=null){event.getGuiGraphics().flush();TemplateClientReview.capture(shot);}
        }catch(Throwable failure){frameFailure=failure;}
    }
    /** Synthetic status inputs exercise the actual cell renderer, not a second arrow implementation. */
    private static void verifyCellAffordances(net.minecraft.client.gui.GuiGraphics g) throws ReflectiveOperationException {
        var snapshotField=LogisticsPanel.class.getDeclaredField("snapshot");snapshotField.setAccessible(true);
        var xField=LogisticsPanel.class.getDeclaredField("mouseX");xField.setAccessible(true);
        var yField=LogisticsPanel.class.getDeclaredField("mouseY");yField.setAccessible(true);
        var tooltipField=LogisticsPanel.class.getDeclaredField("tooltip");tooltipField.setAccessible(true);
        var draw=LogisticsPanel.class.getDeclaredMethod("renderCell",net.minecraft.client.gui.GuiGraphics.class,PanelLayout.CellBox.class);
        draw.setAccessible(true);
        var original=TemplateClientReview.snapshot();int mx=xField.getInt(null),my=yField.getInt(null);
        var originalTooltip=tooltipField.get(null);
        var cells=new ArrayList<>(original.cells());var base=cells.getFirst();
        int[] colors={-8206747,-7105653,-2058918,-6909823};
        var boxes=LogisticsPanel.visibleCells(MC.screen).subList(0,4);
        try {
            for(int i=0;i<4;i++)cells.set(i,new dev.scathiard.feedmepackages.network.PanelPackets.CellView(
                    base.template(),1,i<2?2:-1,i>=2?0:-1,i==0?64:0,0,64,false,i==2?ReturnService.RETURN_SENT:0));
            snapshotField.set(null,new dev.scathiard.feedmepackages.network.PanelPackets.Snapshot(
                    original.window(),original.session(),original.serial(),original.acknowledged(),original.result(),
                    original.status(),original.revision(),original.level(),original.capacity(),original.personal(),
                    original.cacheFirst(),original.bound(),original.address(),original.returnAddress(),original.residualItem(),
                    cells,original.terminals(),original.fluidPreview(),original.firstSlot(),original.totalSlots(),original.columnLimit()));
            xField.setInt(null,-1);yField.setInt(null,-1);
            for(var box:boxes)draw.invoke(null,g,box);
            g.flush();TemplateClientReview.capture("cell-arrows-four-statuses");
            try(var pixels=net.minecraft.client.Screenshot.takeScreenshot(MC.getMainRenderTarget())) {
                double scale=MC.getWindow().getGuiScale();var failures=new ArrayList<String>();
                for(int i=0;i<4;i++) {
                    var r=boxes.get(i).bounds();int color=colors[i];
                    int abgr=(color&0xFF00FF00)|((color&255)<<16)|((color>>>16)&255);
                    for(int row=0;row<5;row++)for(int col=0;col<5;col++) {
                        int downRow=i<2?row:4-row;
                        boolean expected=downRow==2 || downRow==3&&col>=1&&col<=3 || col==2;
                        int pixel=pixels.getPixelRGBA((int)((r.x()+1+col+.5)*scale),(int)((r.y()+1+row+.5)*scale));
                        if((pixel==abgr)!=expected)failures.add("status="+i+" x="+col+" y="+row+" expectedArrow="+expected);
                    }
                }
                require(failures.isEmpty(),"Arrow pixels differ: "+failures);
            }
            // The hint is shown on the item itself both before and after the last real item is removed.
            for(int amount:new int[]{1,0}) {
                cells.set(0,new dev.scathiard.feedmepackages.network.PanelPackets.CellView(base.template(),amount,-1,-1,0,0,64,false,0));
                snapshotField.set(null,new dev.scathiard.feedmepackages.network.PanelPackets.Snapshot(
                        original.window(),original.session(),original.serial(),original.acknowledged(),original.result(),
                        original.status(),original.revision(),original.level(),original.capacity(),original.personal(),
                        original.cacheFirst(),original.bound(),original.address(),original.returnAddress(),original.residualItem(),
                        cells,original.terminals(),original.fluidPreview(),original.firstSlot(),original.totalSlots(),original.columnLimit()));
                var box=boxes.getFirst();xField.setInt(null,box.bounds().x()+9);yField.setInt(null,box.bounds().y()+9);
                draw.invoke(null,g,box);
                @SuppressWarnings("unchecked") var lines=(List<net.minecraft.network.chat.Component>)tooltipField.get(null);
                require(lines.stream().anyMatch(c->c.getString().equals("无物品时 Ctrl + 右键解除绑定")),"Missing unbind hint with stock="+amount);
            }
            FeedMePackages.LOGGER.info("FMP_CELL_AFFORDANCES_PASSED downGreen=true downGray=true upRed=true upGray=true fivePixelShape=true unbindHintStockAndEmpty=true");
        } finally {snapshotField.set(null,original);xField.setInt(null,mx);yField.setInt(null,my);tooltipField.set(null,originalTooltip);}
    }
    @SuppressWarnings("unchecked") private static Map<Integer,?> active() throws ReflectiveOperationException {
        var f=Class.forName("dev.scathiard.feedmepackages.client.CellFeedback").getDeclaredField("ACTIVE");f.setAccessible(true);return (Map<Integer,?>)f.get(null);
    }
    private static Object field(Object value,String name) throws ReflectiveOperationException {var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static long median(List<Long> values){return values.stream().sorted().toList().get(values.size()/2);}
    private static long percentile95(List<Long> values){return values.stream().sorted().toList().get(Math.min(values.size()-1,(int)Math.ceil(values.size()*.95)-1));}
    private static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=CompletableFuture.runAsync(()->action.accept(server.getPlayerList().getPlayer(id)),server);}
    private static void next(){FeedMePackages.LOGGER.info("FMP_FEEDBACK_STAGE {} complete",stage);stage++;changed=ticks;}
    private static void require(boolean value,String text){if(!value)throw new IllegalStateException(text);}
}
