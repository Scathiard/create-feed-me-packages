package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.TemplateBrowsers;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real browser selection and MouseHandler input; fixture stock changes are explicit, not transport evidence. */
public final class FluidClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int stage,ticks,changed,sourceX,sourceY,browserPages;
    private static CompletableFuture<Void> work;
    private static int dragX,dragY;
    /** Remaining input boundaries; no dependency on the earlier basic-fluid scenario's fixture state. */
    public static boolean inputTick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        require(ticks-changed<500,"Fluid input timeout at stage "+stage);
        if(ticks-changed<15)return false;
        var view=TemplateClientReview.snapshot();
        switch(stage){
            case 0 -> {
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),1280,800);
                server(p->{p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();
                    p.getInventory().setItem(0,new ItemStack(Items.WATER_BUCKET));p.getInventory().setItem(2,new ItemStack(Items.LAVA_BUCKET));
                    p.getInventory().setItem(3,new ItemStack(Items.BUCKET));p.inventoryMenu.broadcastChanges();});next();
            }
            case 1 -> {MC.setScreen(new InventoryScreen(MC.player));next();}
            case 2 -> {if(!ready(view))return false;inventorySlotClick(0);next();}
            case 3 -> {
                if(!MC.player.containerMenu.getCarried().is(Items.WATER_BUCKET))return false;
                next();
            }
            case 4 -> {
                windowModifier(true,true);
                try{require(net.minecraft.client.gui.screens.Screen.hasAltDown(),"Native window Alt did not reach GLFW key state");TemplateClientReview.cellClick(0,0);}
                finally{windowModifier(true,false);}next();
            }
            case 5 -> {
                if(!ready(view)||view.cells().get(0).amount()!=1000)return false;
                require(view.cells().get(0).fluid() && MC.player.containerMenu.getCarried().is(Items.BUCKET),"Alt deposit did not return exactly the empty bucket");
                inventorySlotClick(4);next();
            }
            case 6 -> {if(!MC.player.containerMenu.getCarried().isEmpty())return false;inventorySlotClick(2);next();}
            case 7 -> {if(!MC.player.containerMenu.getCarried().is(Items.LAVA_BUCKET))return false;TemplateClientReview.cellClick(1,0);next();}
            case 8 -> {
                if(!ready(view)||!MC.player.containerMenu.getCarried().isEmpty())return false;
                require(!view.cells().get(1).fluid() && view.cells().get(1).amount()==1,"Unmodified empty-cell deposit consumed the bucket's fluid");
                server(p->{require(amount(p)==1000,"Alt deposit was only a client prediction");var handle=AccessGate.resolve(p).handle();var ledger=CacheLedger.get(p.getServer());var before=ledger.find(handle.cacheId());
                    var edit=before.fluids().state().edit();edit.insert(0,FluidVariantKey.of(new FluidStack(Fluids.WATER,1),p.registryAccess()),9000);
                    ledger.replace(handle,before.state().revision(),before.withFluids(before.fluids().withState(edit.finish())));});next();
            }
            case 9 -> {if(!ready(view)||view.cells().get(0).amount()!=10000)return false;next();}
            case 10 -> {
                windowModifier(false,true);
                try{require(net.minecraft.client.gui.screens.Screen.hasShiftDown(),"Native window Shift did not reach GLFW key state");TemplateClientReview.cellClick(0,0);}
                finally{windowModifier(false,false);}next();
            }
            case 11 -> {
                if(!ready(view)||view.fluidPreview().amount()!=8000)return false;
                require(view.cells().get(0).amount()==10000 && view.cells().get(0).reserved()==8000,"Shift preview spent or duplicated fluid");
                inventorySlotClick(3);next();
            }
            case 12 -> {
                if(!ready(view)||view.cells().get(0).amount()!=9000)return false;
                require(view.fluidPreview().amount()==7000 && MC.player.getInventory().getItem(3).is(Items.WATER_BUCKET),"Real Shift fill lost remaining preview or container");
                TemplateClientReview.cellClick(0,0);next();
            }
            case 13 -> {if(!ready(view)||view.fluidPreview().amount()!=0)return false;openSlider();next();}
            case 14 -> {startSlider(false,0);next();}
            case 15 -> {moveSlider(1);next();}
            case 16 -> {TemplateClientReview.mouse(dragX,dragY,0,0);next();}
            case 17 -> {
                if(!ready(view)||view.cells().get(0).minimum()!=8000)return false;
                startSlider(true,view.groupCapacity());next();
            }
            case 18 -> {moveSlider(1);next();}
            case 19 -> {TemplateClientReview.mouse(dragX,dragY,0,0);next();}
            case 20 -> {
                if(!ready(view)||view.cells().get(0).maximum()!=8000)return false;
                require(view.cells().get(0).minimum()==8000 && view.cells().get(0).amount()==9000,"Fluid slider changed stock or wrong endpoint");
                TemplateClientReview.capture("fluid-input-scale2-thresholds");MC.options.guiScale().set(1);MC.resizeDisplay();next();
            }
            case 21 -> {if(!ready(view))return false;require(MC.getWindow().getGuiScale()==1,"GUI scale 1 fixture not applied");openSliderIfClosed();next();}
            case 22 -> {checkSliderBounds();TemplateClientReview.capture("fluid-input-scale1");MC.options.guiScale().set(3);MC.resizeDisplay();next();}
            case 23 -> {if(!ready(view))return false;require(MC.getWindow().getGuiScale()==3,"GUI scale 3 fixture not applied");openSliderIfClosed();next();}
            case 24 -> {checkSliderBounds();startSlider(true,1);next();}
            case 25 -> {moveSlider(2);next();}
            case 26 -> {TemplateClientReview.mouse(dragX,dragY,0,0);next();}
            case 27 -> {
                // The approved rightmost endpoint disables the upper threshold, including fluids.
                if(!ready(view)||view.cells().get(0).maximum()!=-1)return false;
                require(view.cells().get(0).minimum()==8000 && view.cells().get(0).amount()==9000,
                        "Dragging the upper endpoint to capacity changed the lower threshold or stock");
                TemplateClientReview.capture("fluid-input-scale3-thresholds");
                search(Fluids.LAVA);browserPages=0;next();
            }
            case 28 -> {
                if(!findFluid(Fluids.LAVA)){page();return false;}
                TemplateClientReview.mouse(sourceX,sourceY,0,1);next();
            }
            case 29 -> {TemplateClientReview.move(sourceX-6,sourceY+5);next();}
            case 30 -> {var target=LogisticsPanel.visibleCells(MC.screen).get(2).bounds();TemplateClientReview.move(target.x()+7,target.y()+9);next();}
            case 31 -> {var target=LogisticsPanel.visibleCells(MC.screen).get(2).bounds();TemplateClientReview.mouse(target.x()+7,target.y()+9,0,0);next();}
            case 32 -> {
                if(!ready(view)||!view.cells().get(2).fluid())return false;
                require(view.cells().get(2).amount()==0 && MC.player.containerMenu.getCarried().isEmpty()
                        && FluidVariantKey.decode(view.cells().get(2).template(),MC.level.registryAccess()).stack(MC.level.registryAccess(),1).getFluid()==Fluids.LAVA,
                        "Browser drag changed the fluid identity or generated stock");
                TemplateClientReview.capture("fluid-input-browser-drag");MC.player.closeContainer();MC.setScreen(null);
                server(p->{require(amount(p)==9000,"Input checks changed water stock");p.setGameMode(GameType.CREATIVE);p.getInventory().clearContent();
                    p.getInventory().setItem(0,new ItemStack(Items.WATER_BUCKET));p.getInventory().setItem(5,new ItemStack(Items.BUCKET));p.inventoryMenu.broadcastChanges();});next();
            }
            case 33 -> {
                if(MC.gameMode.getPlayerMode()!=GameType.CREATIVE)return false;
                MC.setScreen(new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(MC.player,MC.player.connection.enabledFeatures(),MC.options.operatorItemsTab().get()));next();
            }
            case 34 -> {
                if(!ready(view))return false;
                var select=net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab",net.minecraft.world.item.CreativeModeTab.class);
                select.setAccessible(true);select.invoke(MC.screen,net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.get(net.minecraft.world.item.CreativeModeTabs.INVENTORY));next();
            }
            case 35 -> {inventorySlotClick(0);next();}
            case 36 -> {if(!MC.player.containerMenu.getCarried().is(Items.WATER_BUCKET))return false;TemplateClientReview.cellClick(0,0);next();}
            case 37 -> {
                if(!ready(view)||view.cells().get(0).amount()!=10000||!MC.player.containerMenu.getCarried().is(Items.BUCKET))return false;
                server(p->require(amount(p)==10000 && p.containerMenu.getCarried().isEmpty(),"Creative deposit created a second server cursor or lost actual liquid"));
                inventorySlotClick(0);next();
            }
            case 38 -> {if(!MC.player.containerMenu.getCarried().isEmpty())return false;TemplateClientReview.cellClick(0,0);next();}
            case 39 -> {if(!ready(view)||view.fluidPreview().amount()!=1000)return false;inventorySlotClick(5);next();}
            case 40 -> {
                if(!ready(view)||view.cells().get(0).amount()!=9000||view.fluidPreview().amount()!=0)return false;
                require(MC.player.getInventory().getItem(5).is(Items.WATER_BUCKET) && MC.player.getInventory().getItem(0).is(Items.BUCKET)
                        && MC.player.containerMenu.getCarried().isEmpty(),"Creative wrapper targeted the wrong physical slot or duplicated a cursor");
                TemplateClientReview.capture("fluid-input-creative-wrapper");TemplateClientReview.cellClick(0,0);next();
            }
            case 41 -> {if(!ready(view)||view.fluidPreview().amount()!=1000)return false;MC.player.closeContainer();MC.setScreen(null);next();}
            case 42 -> {server(p->require(amount(p)==9000 && FluidActions.preview(p).amount()==0 && p.getInventory().countItem(Items.BUCKET)==1
                    && p.getInventory().countItem(Items.WATER_BUCKET)==1 && p.containerMenu.getCarried().isEmpty(),"Closing creative fluid input changed ownership"));next();}
            case 43 -> {return true;}
            default -> throw new IllegalStateException("Unknown fluid input phase");
        }
        return false;
    }
    private static boolean ready(dev.scathiard.feedmepackages.network.PanelPackets.Snapshot view){
        return view!=null && LogisticsPanel.recipeReady() && LogisticsPanel.visibleCells(MC.screen).size()>=3;
    }
    private static void inventorySlotClick(int inventoryIndex) throws ReflectiveOperationException {
        var screen=(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)MC.screen;
        var slot=screen.getMenu().slots.stream().filter(s->s.container==MC.player.getInventory() && s.getSlotIndex()==inventoryIndex && s.isActive()).findFirst().orElseThrow();
        TemplateClientReview.click(screen.getGuiLeft()+slot.x+8,screen.getGuiTop()+slot.y+8);
    }
    /** Same-frame window messages avoid GLFW's poll-time stuck-Shift repair; never inject global keys. */
    private static void windowModifier(boolean alt,boolean down){
        require(com.sun.jna.Platform.isWindows(),"Native modifier review requires the isolated Windows client");
        var hwnd=new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(MC.getWindow().getWindow())));
        long scan=alt?0x38:0x2a;long flags=1L|(scan<<16)|(down?0:0xc0000000L);
        com.sun.jna.platform.win32.User32.INSTANCE.SendMessage(hwnd,alt?(down?0x104:0x105):(down?0x100:0x101),
                new com.sun.jna.platform.win32.WinDef.WPARAM(alt?0x12:0x10),new com.sun.jna.platform.win32.WinDef.LPARAM(flags));
    }
    private static void openSlider() throws ReflectiveOperationException {var dot=TemplateClientReview.hostLayout().cells().getFirst().dot();TemplateClientReview.click(dot.x()+2,dot.y()+2);}
    private static void openSliderIfClosed() throws ReflectiveOperationException {if(TemplateClientReview.hostLayout().slider()==null)openSlider();}
    private static void checkSliderBounds() throws ReflectiveOperationException {
        var slider=TemplateClientReview.hostLayout().slider();require(slider!=null && slider.x()>=0 && slider.y()>=0
                && slider.x()+slider.width()<=MC.screen.width && slider.y()+slider.height()<=MC.screen.height,"Fluid slider escaped the scaled viewport");
    }
    private static void startSlider(boolean maximum,long value) throws ReflectiveOperationException {
        checkSliderBounds();var r=TemplateClientReview.hostLayout().slider();
        dragX=dev.scathiard.feedmepackages.client.CellControlGeometry.thumb(r,Math.toIntExact(value*8000),TemplateClientReview.snapshot().groupCapacity()*8000);
        dragY=r.y()+(maximum?33:41)+2;
        TemplateClientReview.mouse(dragX,dragY,0,1);
    }
    private static void moveSlider(long value) throws ReflectiveOperationException {
        var r=TemplateClientReview.hostLayout().slider();dragX=dev.scathiard.feedmepackages.client.CellControlGeometry.thumb(r,Math.toIntExact(value*8000),TemplateClientReview.snapshot().groupCapacity()*8000);
        TemplateClientReview.move(dragX,dragY);
    }
    private static void search(net.minecraft.world.level.material.Fluid fluid){
        String text=new FluidStack(fluid,1).getHoverName().getString();
        if(net.neoforged.fml.ModList.get().isLoaded("emi"))dev.emi.emi.api.EmiApi.setSearchText(text);
        else dev.scathiard.feedmepackages.compat.jei.FmpJeiPlugin.runtime().orElseThrow().getIngredientFilter().setFilterText(text);
    }
    private static void page() throws ReflectiveOperationException {
        require(++browserPages<9,"No exact fluid template in browser pages");boolean emi=net.neoforged.fml.ModList.get().isLoaded("emi");
        TemplateClientReview.click(MC.screen.width-(emi?10:18),emi?10:41);changed=ticks;
    }
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        if(ticks-changed<15)return false;
        var view=TemplateClientReview.snapshot();
        switch(stage){
            case 0 -> {
                server(p->{p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();
                    p.getInventory().setItem(0,new ItemStack(Items.WATER_BUCKET));p.getInventory().setItem(1,new ItemStack(Items.BUCKET,2));
                    p.inventoryMenu.broadcastChanges();}); next();
            }
            case 1 -> {MC.setScreen(new InventoryScreen(MC.player));next();}
            case 2 -> {
                if(view==null || LogisticsPanel.visibleCells(MC.screen).size()<1)return false;
                String search=new FluidStack(Fluids.WATER,1).getHoverName().getString();
                if(net.neoforged.fml.ModList.get().isLoaded("emi"))dev.emi.emi.api.EmiApi.setSearchText(search);
                else dev.scathiard.feedmepackages.compat.jei.FmpJeiPlugin.runtime().orElseThrow().getIngredientFilter().setFilterText(search);
                next();
            }
            case 3 -> {TemplateClientReview.cellClick(0,2);next();}
            case 4 -> {
                if(!findWater()) {
                    require(++browserPages<9,"No exact water template in the browser pages");
                    // Pinned layouts observed in review screenshots; click the real next-page control.
                    boolean emi=net.neoforged.fml.ModList.get().isLoaded("emi");
                    TemplateClientReview.click(MC.screen.width-(emi?10:18),emi?10:41);changed=ticks;return false;
                }
                TemplateClientReview.click(sourceX,sourceY);next();
            }
            case 5 -> {
                if(!view.cells().getFirst().fluid())return false;
                require(view.cells().getFirst().amount()==0 && MC.player.containerMenu.getCarried().isEmpty(),"Browser template created fluid or a carried item");
                TemplateClientReview.nativeSlotClick(36);next();
            }
            case 6 -> {if(!MC.player.containerMenu.getCarried().is(Items.WATER_BUCKET))return false;TemplateClientReview.cellClick(0,0);next();}
            case 7 -> {
                if(view.cells().getFirst().amount()!=1000 || !MC.player.containerMenu.getCarried().is(Items.BUCKET))return false;
                TemplateClientReview.nativeSlotClick(39);next();
            }
            case 8 -> {if(!MC.player.containerMenu.getCarried().isEmpty())return false;TemplateClientReview.cellClick(0,0);next();}
            case 9 -> {
                if(view.fluidPreview().amount()!=1000)return false;
                require(view.cells().getFirst().amount()==1000 && view.cells().getFirst().reserved()==1000 && MC.player.containerMenu.getCarried().isEmpty(),"Preview was not a stock alias");
                TemplateClientReview.capture("fluid-reserved-preview");TemplateClientReview.nativeSlotClick(37);next();
            }
            case 10 -> {
                if(view.fluidPreview().amount()!=0 || view.cells().getFirst().amount()!=0)return false;
                require(MC.player.getInventory().countItem(Items.WATER_BUCKET)==1 && MC.player.getInventory().countItem(Items.BUCKET)==2,"Native fill lost a container or duplicated fluid");
                server(p->{require(amount(p)==0,"Server retained spent fluid");var handle=AccessGate.resolve(p).handle();var ledger=CacheLedger.get(p.getServer());var before=ledger.find(handle.cacheId());
                    var edit=before.fluids().state().edit();var key=FluidVariantKey.of(new FluidStack(Fluids.WATER,1),p.registryAccess());edit.insert(0,key,2500);
                    ledger.replace(handle,before.state().revision(),before.withFluids(before.fluids().withState(edit.finish())));});next();
            }
            case 11 -> {if(view.cells().getFirst().amount()!=2500)return false;TemplateClientReview.cellClick(0,0);next();}
            case 12 -> {
                if(view.fluidPreview().amount()!=1000)return false;
                TemplateClientReview.mouse(5,5,1,1);TemplateClientReview.mouse(5,5,1,0);next();
            }
            case 13 -> {
                if(view.fluidPreview().amount()!=0)return false;
                require(view.cells().getFirst().amount()==2500,"Cancel deducted fluid");
                var dot=TemplateClientReview.hostLayout().cells().getFirst().dot();TemplateClientReview.click(dot.x()+2,dot.y()+2);next();
            }
            case 14 -> {
                require(TemplateClientReview.hostLayout().slider()!=null,"Fluid threshold slider did not open");
                TemplateClientReview.capture("fluid-cell-and-slider");TemplateClientReview.cellClick(0,0);next();
            }
            case 15 -> {if(view.fluidPreview().amount()!=1000)return false;MC.player.closeContainer();MC.setScreen(null);next();}
            case 16 -> {server(p->require(amount(p)==2500 && FluidActions.preview(p).amount()==0,"Closing inventory spent fluid or retained access"));next();}
            case 17 -> {return true;}
            default -> throw new IllegalStateException("Unknown fluid review phase");
        }
        return false;
    }
    private static boolean findWater() throws ReflectiveOperationException {
        return findFluid(Fluids.WATER);
    }
    private static boolean findFluid(net.minecraft.world.level.material.Fluid wanted) throws ReflectiveOperationException {
        var screen=MC.screen;
        for(int x=screen.width-9;x>=0;x-=9)for(int y=18;y<screen.height-24;y+=9){
            TemplateClientReview.move(x,y);var fluid=TemplateBrowsers.selection().fluid();
            if(!fluid.isEmpty() && fluid.getFluid()==wanted && fluid.getComponentsPatch().isEmpty()){sourceX=x;sourceY=y;return true;}
        }
        return false;
    }
    private static long amount(ServerPlayer p){return CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).fluids().state().cells().get(0).amount();}
    private static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=CompletableFuture.runAsync(()->action.accept(server.getPlayerList().getPlayer(id)),server);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void next(){FeedMePackages.LOGGER.info("FMP_FLUID_CLIENT_STAGE {} complete",stage);stage++;changed=ticks;}
}
