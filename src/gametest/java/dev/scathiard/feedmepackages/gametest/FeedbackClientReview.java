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
    private static boolean arrivalShot,requestFirst,requestSecond,departureShot;
    private static long frameStart,lastFrame;
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
                NeoForge.EVENT_BUS.unregister(PRE);NeoForge.EVENT_BUS.unregister(POST);return true;
            }
        }
        return false;
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
        if(event.getScreen()!=MC.screen || !(MC.screen instanceof InventoryScreen))return;
        try{
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
