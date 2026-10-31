package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native mouse actions with only the client acknowledgement delivery delayed. */
public final class PredictionClientReview {
    private record Case(String name,Item item,int stock,int carried,int button,int expected,boolean close) {}
    private static final Case[] CASES={
        new Case("right-single",Items.STONE,10,32,1,11,false),
        new Case("left-over-stack",Items.STONE,100,32,0,132,false),
        new Case("right-over-stack",Items.STONE,100,32,1,101,false),
        new Case("capacity",Items.STONE,250,32,0,256,false),
        new Case("stack16",Items.ENDER_PEARL,10,8,0,18,false),
        new Case("full-rejected",Items.STONE,256,32,0,256,false),
        new Case("preview-return",Items.STONE,100,0,1,37,false),
        new Case("close-late-ack",Items.STONE,10,32,1,11,true)
    };
    private static final Minecraft MC=Minecraft.getInstance();
    private static final List<PanelPackets.Snapshot> held=new ArrayList<>();
    private static Consumer<PanelPackets.Snapshot> original;
    private static CompletableFuture<Void> work;
    private static int phase,ticks,changed,index,shown;
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        var c=CASES[index];
        switch(phase) {
            case 0 -> {
                work=MC.getSingleplayerServer().submit(()->{
                    var p=MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                    p.getInventory().clearContent();p.containerMenu.setCarried(ItemStack.EMPTY);
                    TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
                    var handle=AccessGate.resolve(p).handle();var ledger=CacheLedger.get(p.getServer());var old=ledger.find(handle.cacheId());
                    var edit=old.state().edit();edit.upgrade();var key=ItemVariantKey.of(new ItemStack(c.item()),p.registryAccess());
                    edit.filter(0,key);edit.insert(0,key,c.stock());ledger.replace(handle,old.state().revision(),old.withState(edit.finish()));
                    p.getInventory().setItem(9,new ItemStack(c.item(),c.carried()));p.containerMenu.broadcastChanges();
                });next();
            }
            case 1 -> {MC.setScreen(new InventoryScreen(MC.player));next();}
            case 2 -> {
                var s=TemplateClientReview.snapshot();
                if(!LogisticsPanel.recipeReady()||s==null||s.cells().getFirst().amount()!=c.stock()||MC.player.getInventory().getItem(9).getCount()!=c.carried())return false;
                if(c.name().equals("preview-return")) {
                    var b=TemplateClientReview.hostLayout().cells().getFirst().bounds();TemplateClientReview.click(b.x()+8,b.y()+8);
                } else TemplateClientReview.nativeSlotClick(9);
                next();
            }
            case 3 -> {
                if(MC.player.containerMenu.getCarried().getCount()!=(c.name().equals("preview-return")?64:c.carried())||!LogisticsPanel.recipeReady())return false;
                // Give the native cursor packet time to reach the integrated server before the FMP click.
                if(ticks-changed<5)return false;
                var field=PanelNetwork.class.getDeclaredField("clientReceiver");field.setAccessible(true);
                @SuppressWarnings("unchecked") var receiver=(Consumer<PanelPackets.Snapshot>)field.get(null);
                original=receiver;held.clear();PanelNetwork.receiveOnClient(held::add);
                var b=TemplateClientReview.hostLayout().cells().getFirst().bounds();
                TemplateClientReview.mouse(b.x()+8,b.y()+8,c.button(),1);TemplateClientReview.mouse(b.x()+8,b.y()+8,c.button(),0);
                var prediction=field("predict");
                if(c.name().equals("preview-return")) {
                    if(prediction!=null)throw new AssertionError("Ambiguous reservation must await the server");
                    shown=TemplateClientReview.snapshot().cells().getFirst().amount()-TemplateClientReview.snapshot().cells().getFirst().reserved();
                } else {
                    if(prediction==null)throw new AssertionError("No display prediction for "+c.name());
                    var result=prediction.getClass().getDeclaredMethod("result");result.setAccessible(true);shown=(int)result.invoke(prediction);
                }
                next();
            }
            case 4 -> {
                if(ticks-changed<20||held.stream().noneMatch(p->p.acknowledged()!=0))return false;
                var ack=held.stream().filter(p->p.acknowledged()!=0).reduce((a,b)->b).orElseThrow();
                int actual=ack.cells().getFirst().amount()-ack.cells().getFirst().reserved();
                FeedMePackages.LOGGER.info("FMP_PREDICTION_OBSERVED case={} displayBeforeAck={} authoritative={} expected={} heldReplyTicks={}",c.name(),shown,actual,c.expected(),ticks-changed);
                TemplateClientReview.capture("prediction-"+c.name());
                if(c.name().equals("full-rejected")&&ack.result()!=dev.scathiard.feedmepackages.interaction.CacheActions.Result.NO_SPACE)throw new AssertionError("Expected server rejection");
                if(actual!=c.expected())throw new AssertionError("Server contract mismatch: "+c.name()+" actual="+actual);
                if(shown!=(c.name().equals("preview-return")?36:c.expected()))throw new AssertionError("Display prediction mismatch: "+c.name()+" shown="+shown+" actual="+actual);
                if(c.close()) {
                    if(!MC.screen.keyPressed(256,0,0)||MC.screen!=null)throw new AssertionError("Native close failed");
                    if(field("predict")!=null)throw new AssertionError("Close retained prediction");
                    MC.setScreen(new InventoryScreen(MC.player));
                }
                for(var packet:List.copyOf(held))original.accept(packet);
                held.clear();PanelNetwork.receiveOnClient(original);next();
            }
            case 5 -> {
                if(ticks-changed<5||!LogisticsPanel.recipeReady())return false;
                if(field("predict")!=null)throw new AssertionError("Ack or new window retained old prediction");
                var s=TemplateClientReview.snapshot();if(s==null||s.cells().getFirst().amount()!=(c.name().equals("preview-return")?c.stock():c.expected()))return false;
                work=MC.getSingleplayerServer().submit(()->{
                    var p=MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID());
                    int cache=ConsumptionTests.stock(p,0),real=p.getInventory().countItem(c.item())+p.containerMenu.getCarried().getCount();
                    if(c.name().equals("preview-return")) {
                        if(cache!=100||p.containerMenu.getCarried().getCount()!=63)throw new AssertionError("Preview return changed actual stock");
                    } else if(cache!=c.expected()||cache+real!=c.stock()+c.carried())throw new AssertionError("Native stock conservation failed: "+c.name());
                    p.containerMenu.broadcastFullState();
                });next();
            }
            case 6 -> {
                if(ticks-changed<5)return false;
                MC.screen.onClose();
                FeedMePackages.LOGGER.info("FMP_PREDICTION_CASE_PASSED {}",c.name());
                if(++index==CASES.length)return true;
                phase=0;changed=ticks;
            }
            default -> throw new AssertionError("Unknown phase");
        }
        return false;
    }
    private static Object field(String name)throws ReflectiveOperationException{var f=LogisticsPanel.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void next(){phase++;changed=ticks;}
}
