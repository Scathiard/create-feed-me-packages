package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.network.*;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual wheel + Escape with the previous acknowledgement delayed on the client thread. */
public final class SettingsCloseClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed,scenario;
    private static final int[] DELAYS={0,4,20};
    private static final List<PanelPackets.Snapshot> held=new ArrayList<>();
    private static Consumer<PanelPackets.Snapshot> original;
    private static CompletableFuture<Void> work;
    private static int expected;
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        switch(phase){
            case 0 -> {
                work=MC.getSingleplayerServer().submit(()->{
                    var player=MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                    ConsumptionTests.seed(player,0,new ItemStack(Items.STONE),8);
                });next();
            }
            case 1 -> {MC.setScreen(new InventoryScreen(MC.player));next();}
            case 2 -> {
                if(!LogisticsPanel.recipeReady() || TemplateClientReview.snapshot()==null)return false;
                var dot=TemplateClientReview.hostLayout().cells().getFirst().dot();
                TemplateClientReview.click(dot.x()+2,dot.y()+2);
                var field=PanelNetwork.class.getDeclaredField("clientReceiver");field.setAccessible(true);
                @SuppressWarnings("unchecked") var receiver=(Consumer<PanelPackets.Snapshot>)field.get(null);
                original=receiver;held.clear();PanelNetwork.receiveOnClient(held::add);
                wheel();expected=TemplateClientReview.snapshot().cells().getFirst().batch()+2;next();
            }
            case 3 -> {
                var field=LogisticsPanel.class.getDeclaredField("waiting");field.setAccessible(true);
                if(field.getInt(null)==0 || held.stream().noneMatch(p->p.acknowledged()!=0))return false;
                wheel();
                if(!MC.screen.keyPressed(256,0,0) || MC.screen!=null)throw new AssertionError("Native Escape did not close inventory");
                next();
            }
            case 4 -> {
                if(ticks-changed<DELAYS[scenario])return false;
                for(var packet:List.copyOf(held))original.accept(packet);
                held.clear();PanelNetwork.receiveOnClient(original);next();
            }
            case 5 -> {
                work=MC.getSingleplayerServer().submit(()->{
                    var player=MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                    var cell=CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().getFirst();
                    if(cell.settings().batch()!=expected)throw new AssertionError("Wheel + Escape lost newest settings: expected="+expected+" actual="+cell.settings().batch());
                    if(cell.amount()!=8)throw new AssertionError("Closing settings altered stock");
                });next();
            }
            case 6 -> {
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_SETTINGS_CLOSE_CLIENT_PASSED replyDelayTicks={} latestBatch={}",DELAYS[scenario],expected);
                if(++scenario==DELAYS.length)return true;
                phase=1;changed=ticks;
            }
            default -> throw new IllegalStateException("Unknown close phase");
        }
        return false;
    }
    private static void wheel() throws ReflectiveOperationException {
        var rect=CellControlGeometry.field(TemplateClientReview.hostLayout().slider(),0);
        TemplateClientReview.move(rect.x()+8,rect.y()+8);
        var method=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);method.setAccessible(true);
        method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0d,1d);
    }
    private static void next(){phase++;changed=ticks;}
}
