package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.*;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Scoped reuse of the prior native scroll/take/put review, with configuration instead of plugins. */
public final class CapacityClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int stage,ticks,changed,round,target,oldSlots,oldGroups;
    private static CompletableFuture<Void> work;
    private static Map<Integer,Integer> before;
    private static boolean restored;
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        if(ticks-changed<18)return false;
        if(ticks-changed>400)throw new IllegalStateException("Capacity review timed out stage="+stage+" snapshot="+snapshot());
        switch(stage) {
            case 0 -> {server(p->{
                oldSlots=FmpConfig.SLOTS.getFirst().get();oldGroups=FmpConfig.GROUPS.getFirst().get();
                FmpConfig.SLOTS.getFirst().set(50);FmpConfig.GROUPS.getFirst().set(32);FmpConfig.refreshServer();
                var access=AccessGate.resolve(p);var ledger=CacheLedger.get(p.getServer());var record=ledger.find(access.handle().cacheId());var edit=record.state().edit();
                p.getInventory().clearContent();p.containerMenu.setCarried(ItemStack.EMPTY);
                for(int i=0;i<50;i++){var item=new ItemStack(Items.STONE);item.set(DataComponents.CUSTOM_NAME,Component.literal("scroll-"+i));
                    var key=ItemVariantKey.of(item,p.registryAccess());edit.filter(i,key);edit.insert(i,key,i+1);}
                ledger.replace(access.handle(),record.state().revision(),record.withState(edit.finish()));
            });next();}
            case 1 -> {MC.setScreen(new InventoryScreen(MC.player));next();}
            case 2 -> {
                if(!ready(0))return false;require(snapshot().grid().columns()==(round==0?6:4),"Unexpected physical viewport width");verifyPage();before=positions();TemplateClientReview.capture("capacity-"+round+"-top");
                if(round==0){
                    var cell=LogisticsPanel.visibleCells(MC.screen).getFirst();var dot=cell.dot();TemplateClientReview.click(dot.x()+1,dot.y()+2);
                    var slider=TemplateClientReview.hostLayout().slider();require(slider!=null,"Cell controls did not open");
                    var batch=CellControlGeometry.field(slider,0);wheel(batch.x()+2,batch.y()+2,1);
                }
                scroll(-1);next();
            }
            case 3 -> {
                if(!ready(round==0?6:4))return false;verifyPage();var after=positions();
                for(var entry:before.entrySet())if(entry.getKey()/6>0)
                    require(Objects.equals(after.get(entry.getKey()-6),entry.getValue()),"Scrolling reordered a physical row");
                server(p->require(CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state().cells().get(0).settings().batch()==2,
                        "Changing page lost the immediately preceding control edit"));
                TemplateClientReview.capture("capacity-"+round+"-down");var cell=LogisticsPanel.visibleCells(MC.screen).getFirst();
                target=snapshot().grid().storageSlot(cell.slot());TemplateClientReview.cellClick(cell.slot(),0);next();
            }
            case 4 -> {
                if(!LogisticsPanel.recipeReady() || MC.player.containerMenu.getCarried().isEmpty())return false;
                require(MC.player.containerMenu.getCarried().getHoverName().getString().equals("scroll-"+target),"Scrolled click took another item");
                nativeSlot(10);next();
            }
            case 5 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                server(p->{var state=CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state();
                    require(state.cells().get(target).amount()==0 && p.getInventory().countItem(Items.STONE)==target+1,"Scrolled take debited another cell");});
                nativeSlot(10);next();
            }
            case 6 -> {
                if(MC.player.containerMenu.getCarried().isEmpty())return false;
                TemplateClientReview.cellClick(snapshot().grid().localSlot(target),0);next();
            }
            case 7 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                verifyPage();scroll(1);next();
            }
            case 8 -> {
                if(!ready(0))return false;require(positions().equals(before),"Scroll back changed the original layout");
                server(CapacityClientReview::verifyWholeStock);
                if(round++==0){MC.player.closeContainer();org.lwjgl.glfw.GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),960,720);MC.options.guiScale().set(3);MC.resizeDisplay();stage=1;changed=ticks;}
                else next();
            }
            case 9 -> {
                server(p->{FmpConfig.SLOTS.getFirst().set(9);FmpConfig.GROUPS.getFirst().set(1);FmpConfig.refreshServer();AccessGate.resolve(p);});next();
            }
            case 10 -> {
                if(!LogisticsPanel.recipeReady() || snapshot().cells().stream().allMatch(PanelPackets.CellView::writable))return false;
                for(int i=0;i<60;i++)scroll(-1);next();
            }
            case 11 -> {
                if(!LogisticsPanel.recipeReady())return false;var page=snapshot();int local=page.grid().localSlot(49);
                if(local<0 || LogisticsPanel.visibleCells(MC.screen).stream().noneMatch(c->c.slot()==local))return false;
                require(page.totalSlots()==50 && !page.cells().get(local).writable() && page.cells().get(local).amount()==50,"Reduced capacity hid or changed the last slot");
                var box=LogisticsPanel.visibleCells(MC.screen).stream().filter(c->c.slot()==local).findFirst().orElseThrow();
                TemplateClientReview.move(box.bounds().x()+8,box.bounds().y()+8);TemplateClientReview.capture("capacity-readonly-last-cell");
                TemplateClientReview.cellClick(local,0);next();
            }
            case 12 -> {
                if(!LogisticsPanel.recipeReady() || MC.player.containerMenu.getCarried().isEmpty())return false;
                nativeSlot(10);next();
            }
            case 13 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                server(p->{var state=CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state();
                    require(state.cells().get(49).amount()==0 && p.getInventory().countItem(Items.STONE)==50,"Readonly native withdrawal lost or copied stock");
                    FmpConfig.SLOTS.getFirst().set(50);FmpConfig.GROUPS.getFirst().set(32);FmpConfig.refreshServer();AccessGate.resolve(p);});next();
            }
            case 14 -> {
                if(!LogisticsPanel.recipeReady() || !snapshot().cells().get(snapshot().grid().localSlot(49)).writable())return false;
                nativeSlot(10);next();
            }
            case 15 -> {
                if(MC.player.containerMenu.getCarried().isEmpty())return false;
                TemplateClientReview.cellClick(snapshot().grid().localSlot(49),0);next();
            }
            case 16 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                verifyPage();TemplateClientReview.cellClick(snapshot().grid().localSlot(49),0);next();
            }
            case 17 -> {
                if(!LogisticsPanel.recipeReady() || MC.player.containerMenu.getCarried().isEmpty())return false;
                nativeSlot(10);next();
            }
            case 18 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                for(int i=0;i<60;i++)scroll(1);next();
            }
            case 19 -> {if(!ready(0))return false;nativeSlot(10);next();}
            case 20 -> {
                if(!LogisticsPanel.recipeReady() || MC.player.containerMenu.getCarried().isEmpty())return false;
                TemplateClientReview.cellClick(LogisticsPanel.visibleCells(MC.screen).getFirst().slot(),0);next();
            }
            case 21 -> {
                if(!LogisticsPanel.recipeReady() || !MC.player.containerMenu.getCarried().isEmpty())return false;
                server(p->{verifyWholeStock(p);FmpConfig.SLOTS.getFirst().set(oldSlots);FmpConfig.GROUPS.getFirst().set(oldGroups);FmpConfig.refreshServer();restored=true;});next();
            }
            case 22 -> {require(restored,"Config not restored");FeedMePackages.LOGGER.info("FMP_CAPACITY_CLIENT_PASSED widths=6,4 rowContinuity=true nativeTakePut=true draftBeforePage=true readonlyTake=true restoredWrite=true offPageDeposit=true stockConserved=true");return true;}
            default -> throw new IllegalStateException("Unknown capacity review stage "+stage);
        }
        return false;
    }
    private static void verifyWholeStock(ServerPlayer p){var state=CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state();
        for(int i=0;i<50;i++)require(state.cells().get(i).amount()==i+1,"Final stock differs at "+i);
        require(p.getInventory().isEmpty() && p.containerMenu.getCarried().isEmpty(),"Real inventory or cursor duplicated cached stock");}
    private static void verifyPage() throws ReflectiveOperationException {var page=snapshot();for(int local=0;local<page.cells().size();local++){
        int storage=page.grid().storageSlot(local);var cell=page.cells().get(local);
        require(cell.amount()==storage+1 && ItemVariantKey.decode(cell.template(),MC.player.registryAccess()).stack(MC.player.registryAccess(),1).getHoverName().getString().equals("scroll-"+storage),"Rendered page has wrong identity or stock at "+storage);}}
    private static Map<Integer,Integer> positions() throws ReflectiveOperationException {var out=new HashMap<Integer,Integer>();var host=TemplateClientReview.hostLayout();
        for(var cell:LogisticsPanel.visibleCells(MC.screen)){int row=(cell.bounds().y()-host.bounds().y()-PanelLayout.HEADER)/18;int col=(cell.bounds().x()-host.bounds().x()-PanelLayout.SIDE)/18;
            out.put(row*6+col,snapshot().grid().storageSlot(cell.slot()));}return out;}
    private static boolean ready(int first)throws ReflectiveOperationException{return MC.screen instanceof AbstractContainerScreen<?> && LogisticsPanel.recipeReady() && snapshot()!=null && snapshot().firstSlot()==first;}
    private static PanelPackets.Snapshot snapshot()throws ReflectiveOperationException{var field=LogisticsPanel.class.getDeclaredField("snapshot");field.setAccessible(true);return(PanelPackets.Snapshot)field.get(null);}
    private static void nativeSlot(int index)throws ReflectiveOperationException{var screen=(AbstractContainerScreen<?>)MC.screen;var slot=screen.getMenu().getSlot(index);TemplateClientReview.click(screen.getGuiLeft()+slot.x+8,screen.getGuiTop()+slot.y+8);}
    private static void scroll(double delta)throws ReflectiveOperationException{var box=TemplateClientReview.hostLayout().bounds();wheel(box.x()+3,box.y()+3,delta);}
    private static void wheel(double x,double y,double delta)throws ReflectiveOperationException{TemplateClientReview.move(x,y);var method=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0.0,delta);}
    private static void server(Consumer<ServerPlayer> action){var id=MC.player.getUUID();var server=MC.getSingleplayerServer();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id)));}
    private static void next(){stage++;changed=ticks;FeedMePackages.LOGGER.info("FMP_CAPACITY_CLIENT_STAGE {}",stage);}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
}
