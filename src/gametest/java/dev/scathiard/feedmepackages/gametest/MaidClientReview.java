package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native entity-interaction packet, maid's Curios button and actual inventory clicks. */
final class MaidClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int phase, ticks, changed, maidId;
    private static UUID cache;
    private static ItemStack browserTemplate;
    private static CompletableFuture<Void> work;
    static boolean tick() throws Exception {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed > 700) throw new IllegalStateException("Maid client timeout phase=" + phase);
        if (ticks - changed < 25) return false;
        switch (phase) {
            case 0 -> {
                // The author's tall screen exceeds the minimum window at GUI scale 2.
                MC.options.guiScale().set(1); MC.resizeDisplay(); server(MaidClientReview::setup); next();
            }
            case 1 -> {
                var maid = MC.level.getEntity(maidId); if (maid == null) break;
                MC.gameMode.interact(MC.player, maid, InteractionHand.MAIN_HAND); next();
            }
            case 2 -> {
                if (MC.screen == null) break;
                var button = MC.screen.children().stream().filter(w -> w.getClass().getName()
                        .equals("com.github.tartaricacid.touhoulittlemaid.compat.curios.client.CuriosButton"))
                        .map(AbstractWidget.class::cast).findFirst().orElse(null);
                if (button == null) break;
                require(button.active && button.visible, "Native maid Curios button unavailable");
                TemplateClientReview.click(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0); next();
            }
            case 3 -> {
                if (!MC.player.containerMenu.getClass().getName().endsWith(".CuriosContainer")) break;
                // Seed only inside this foreign menu: ordinary inventory ticks legitimately retire old keys.
                server(p -> { p.getInventory().setItem(1, FmpRegistries.PERSONAL_PENDANT.toStack()); p.containerMenu.broadcastChanges(); });
                click(playerSlot(0)); next();
            }
            case 4 -> { click(necklace()); next(); }
            case 5 -> {
                server(p -> { var a = MaidAccess.resolve(maid(p)); require(a.active() && a.handle().cacheId().equals(cache)
                        && !AccessGate.resolve(p).active() && stock(p) == 8, "Mouse-equipped ordinary pendant did not belong to the maid"); });
                TemplateClientReview.capture("74-maid-ordinary-equipped"); click(necklace()); next();
            }
            case 6 -> { click(playerSlot(0)); next(); }
            case 7 -> {
                server(p -> require(!MaidAccess.resolve(maid(p)).active() && stock(p) == 8
                        && p.getInventory().getItem(0).is(FmpRegistries.PENDANT.get()), "Mouse unequip left maid access active or lost the pendant"));
                click(playerSlot(1)); next();
            }
            case 8 -> { click(necklace()); next(); }
            case 9 -> {
                server(p -> {
                    var worn = CuriosApi.getCuriosInventory(maid(p)).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().getStackInSlot(0);
                    var legacy = p.containerMenu.getCarried();
                    require(worn.isEmpty() && legacy.is(FmpRegistries.PERSONAL_PENDANT.get())
                            && !legacy.has(FmpRegistries.OWNER.get()) && !legacy.has(FmpRegistries.IDENTITY.get())
                            && !MaidAccess.resolve(maid(p)).active() && stock(p) == 8,
                            "Retired item rejection: worn="+worn+" carried="+legacy+" access="+MaidAccess.resolve(maid(p)).status()+" originalStock="+stock(p));
                });
                TemplateClientReview.capture("75-maid-legacy-item-rejected"); next();
            }
            case 10 -> { click(playerSlot(1)); next(); }
            case 11 -> {
                server(p -> require(p.containerMenu.getCarried().isEmpty() && p.getInventory().getItem(0).getCount() == 1
                        && p.getInventory().getItem(1).getCount() == 1 && stock(p) == 8, "Maid UI failed item conservation")); next();
            }
            case 12 -> { click(playerSlot(0));next(); }
            case 13 -> { click(necklace());next(); }
            case 14 -> {
                var snapshot=TemplateClientReview.snapshot();
                if(snapshot==null || snapshot.cells().size()!=9 || snapshot.cells().getFirst().amount()!=8)break;
                require(snapshot.address().equals(MaidAccess.address((net.minecraft.world.entity.LivingEntity)MC.level.getEntity(maidId))),"Maid panel shows player address");
                assertNoNativeOverlap();TemplateClientReview.capture("maid-panel-unworn-owner");
                TemplateClientReview.cellClick(0,0);next();
            }
            case 15 -> {
                require(MC.player.containerMenu.getCarried().is(Items.BREAD) && MC.player.containerMenu.getCarried().getCount()==8,"Maid preview did not reach the real cursor");
                click(playerSlot(2),1);click(playerSlot(2),1);next();
            }
            case 16 -> {
                server(p->require(stock(p)==6 && p.getInventory().getItem(2).getCount()==2,"Partial native placement did not debit two maid items"));
                TemplateClientReview.cellClick(0,0);next();
            }
            case 17 -> {
                require(MC.player.containerMenu.getCarried().isEmpty(),"Returning the preview left a real stack");
                server(p->{p.getInventory().setItem(3,new ItemStack(Items.STONE,24));p.containerMenu.broadcastChanges();});
                next();
            }
            case 18 -> { click(playerSlot(3));next(); }
            case 19 -> { TemplateClientReview.cellClick(1,0);next(); }
            case 20 -> {
                var snapshot=TemplateClientReview.snapshot();
                require(snapshot.cells().get(1).amount()==24 && MC.player.containerMenu.getCarried().isEmpty(),"Maid deposit/filter failed");
                var cell=LogisticsPanel.visibleCells(MC.screen).stream().filter(c->c.slot()==1).findFirst().orElseThrow();
                TemplateClientReview.click(cell.dot().x()+2,cell.dot().y()+2);next();
            }
            case 21 -> {
                var slider=TemplateClientReview.hostLayout().slider();require(slider!=null,"Maid cell controls did not open");
                var r=dev.scathiard.feedmepackages.client.CellControlGeometry.field(slider,0);
                TemplateClientReview.move(r.x()+5,r.y()+5);
                var scroll=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);scroll.setAccessible(true);
                scroll.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0.0,3.0);next();
            }
            case 22 -> {
                require(TemplateClientReview.snapshot().cells().get(1).batch()==2,"Native maid setting wheel did not persist");
                TemplateClientReview.capture("maid-panel-controls");
                server(p->{TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());ConsumptionTests.seed(p,0,new ItemStack(Items.DIAMOND),13);});next();
            }
            case 23 -> {
                require(TemplateClientReview.snapshot().cells().getFirst().amount()==6,"Wearing own pendant replaced the maid panel");
                TemplateClientReview.cellClick(0,0);next();
            }
            case 24 -> {
                require(MC.player.containerMenu.getCarried().getCount()==6,"Maid cancel preview missing");MC.screen.onClose();next();
            }
            case 25 -> {
                require(MC.screen==null && MC.player.containerMenu.getCarried().isEmpty(),"Closing maid page retained preview");
                server(p->require(stock(p)==6 && p.getInventory().getItem(2).getCount()==2,"Maid close duplicated or lost stock"));
                MC.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(MC.player));next();
            }
            case 26 -> {
                var snapshot=TemplateClientReview.snapshot();
                if(snapshot==null || snapshot.cells().isEmpty())break;
                require(snapshot.cells().getFirst().amount()==13 && snapshot.address().startsWith("FMP@"),"Player page did not restore own cache");
                TemplateClientReview.capture("maid-panel-player-isolation");
                MC.screen.onClose();
                MC.gameMode.interact(MC.player,MC.level.getEntity(maidId),InteractionHand.MAIN_HAND);next();
            }
            case 27 -> {
                if(MC.screen==null)break;
                var button=MC.screen.children().stream().filter(w->w.getClass().getName().endsWith(".CuriosButton"))
                        .map(AbstractWidget.class::cast).findFirst().orElseThrow();
                TemplateClientReview.click(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0);next();
            }
            case 28 -> {
                if(TemplateClientReview.snapshot()==null || LogisticsPanel.visibleCells(MC.screen).isEmpty())break;
                assertNoNativeOverlap();
                if(dev.scathiard.feedmepackages.client.TemplateBrowsers.available()) { TemplateClientReview.cellClick(2,2);next(); }
                else { phase=31;changed=ticks; }
            }
            case 29 -> { browserTemplate=TemplateClientReview.clickBrowserSource(new ItemStack(Items.BREAD));next(); }
            case 30 -> {
                server(p->{
                    var cell=CacheLedger.get(p.getServer()).find(cache).state().cells().get(2);
                    require(cell.amount()==0 && dev.scathiard.feedmepackages.item.ItemVariantKey.of(browserTemplate,p.registryAccess()).equals(cell.filter()),
                            "Browser selection did not set only the maid's filter");
                    require(CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state().cells().getFirst().amount()==13,
                            "Maid browser selection changed owner's cache");
                });TemplateClientReview.capture("maid-panel-browser-template");next();
            }
            case 31 -> { MC.getWindow().setWindowed(1280,900);MC.options.guiScale().set(2);MC.resizeDisplay();next(); }
            case 32 -> { assertNoNativeOverlap();assertPanelBounds();TemplateClientReview.capture("maid-panel-scale2");
                MC.options.guiScale().set(3);MC.resizeDisplay();next(); }
            case 33 -> {
                assertNoNativeOverlap();assertPanelBounds();TemplateClientReview.capture("maid-panel-scale3");
                MC.options.guiScale().set(1);MC.resizeDisplay();next();
            }
            case 34 -> {
                require(!TemplateClientReview.snapshot().maidPickup(),"Maid pickup default not off");
                var r=TemplateClientReview.hostLayout().maidPickupButton();
                TemplateClientReview.click(r.x()+6,r.y()+6);next();
            }
            case 35 -> {
                require(TemplateClientReview.snapshot().maidPickup(),"Native toggle click did not sync on");
                server(p->require(dev.scathiard.feedmepackages.compat.maid.MaidPickup.enabled(maid(p)),"Toggle changed only the client"));
                TemplateClientReview.capture("maid-pickup-toggle-on");
                var r=TemplateClientReview.hostLayout().maidPickupButton();TemplateClientReview.click(r.x()+6,r.y()+6);next();
            }
            case 36 -> {
                require(!TemplateClientReview.snapshot().maidPickup(),"Native toggle click did not sync off");
                server(p->require(!dev.scathiard.feedmepackages.compat.maid.MaidPickup.enabled(maid(p)),"Toggle off changed only the client"));
                next();
            }
            case 37 -> {
                FeedMePackages.LOGGER.info("FMP_MAID_PICKUP_CLIENT_PASSED defaultOff=true nativeClicks=true serverState=true snapshotRoundtrip=true");
                FeedMePackages.LOGGER.info("FMP_MAID_CLIENT_PASSED nativeEntityInteraction=true nativeCuriosButton=true mouseEquip=true mouseUnequip=true legacyItemRejectedIntact=true unwornPanel=true partialPlacement=true depositFilter=true nativeWheel=true closePreview=true playerCacheIsolated=true browserTemplate={} guiScales=1,2,3",browserTemplate!=null);
                return true;
            }
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        p.closeContainer(); p.getInventory().clearContent(); ConsumptionTests.seed(p, 0, new ItemStack(Items.BREAD), 8);
        cache = AccessGate.resolve(p).handle().cacheId();
        var ordinary = TestPlayers.necklace(p).getStackInSlot(0); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
        p.getInventory().setItem(0, ordinary);
        p.getInventory().selected = 7; p.inventoryMenu.broadcastChanges();
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid"));
        var maid = (TamableAnimal) type.create(p.serverLevel()); require(maid != null, "Native maid absent");
        maid.setTame(true, false); maid.setOwnerUUID(p.getUUID()); maid.setNoAi(true); maid.setNoGravity(true);
        maid.setPos(p.position().add(2, 0, 0)); require(p.serverLevel().addFreshEntity(maid), "Maid spawn failed");
        CuriosApi.getCuriosInventory(maid).orElseThrow().reset(); maidId = maid.getId();
    }
    private static TamableAnimal maid(ServerPlayer p) { return (TamableAnimal) p.serverLevel().getEntity(maidId); }
    private static long stock(ServerPlayer p) { return CacheLedger.get(p.getServer()).find(cache).state().cells().get(0).amount(); }
    private static Slot playerSlot(int id) {
        return MC.player.containerMenu.slots.stream().filter(s -> s.container == MC.player.getInventory() && s.getContainerSlot() == id).findFirst().orElseThrow();
    }
    private static Slot necklace() throws ReflectiveOperationException {
        for (var slot : MC.player.containerMenu.slots) {
            if (slot.getClass().getName().endsWith(".MaidCurioSlot") && slot.getClass().getMethod("getIdentifier").invoke(slot).equals("necklace")) return slot;
        }
        throw new IllegalStateException("Native maid necklace slot absent");
    }
    private static void assertPanelBounds() throws ReflectiveOperationException {
        var r=TemplateClientReview.hostLayout().bounds();
        require(r.x()>=0 && r.y()>=0 && r.x()+r.width()<=MC.screen.width && r.y()+r.height()<=MC.screen.height,"Maid panel outside scaled screen");
    }
    private static void assertNoNativeOverlap() throws ReflectiveOperationException {
        var panel=TemplateClientReview.hostLayout().bounds();var nativeScreen=(AbstractContainerScreen<?>)MC.screen;
        for(var slot:MC.player.containerMenu.slots)require(!intersects(panel,nativeScreen.getGuiLeft()+slot.x,nativeScreen.getGuiTop()+slot.y,16,16),"Maid panel covers native slot");
        for(var child:MC.screen.children())if(child instanceof AbstractWidget widget && widget.visible)
            require(!intersects(panel,widget.getX(),widget.getY(),widget.getWidth(),widget.getHeight()),"Maid panel covers native button "+widget.getClass().getSimpleName());
    }
    private static boolean intersects(dev.scathiard.feedmepackages.client.PanelLayout.Rect r,int x,int y,int w,int h) {
        return x<r.x()+r.width() && x+w>r.x() && y<r.y()+r.height() && y+h>r.y();
    }
    private static void click(Slot slot,int button) throws ReflectiveOperationException {
        var screen=(AbstractContainerScreen<?>)MC.screen;double x=screen.getGuiLeft()+slot.x+8,y=screen.getGuiTop()+slot.y+8;
        TemplateClientReview.mouse(x,y,button,1);TemplateClientReview.mouse(x,y,button,0);
    }
    private static void click(Slot slot) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>) MC.screen;
        TemplateClientReview.click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> operation.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_MAID_CLIENT_PHASE {}", phase); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
