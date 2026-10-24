package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Actual selection/mouse/held use/packet checks; server calls only arrange and inspect fixtures. */
final class MaterialsClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed;
    private static CompletableFuture<Void> work;
    private static BlockPos waterTarget;
    private static boolean firstUseFrame,secondUseFrame,reloadSeen;
    private static long receiptsBefore;
    private static double reversedFrom;
    private static PalletMaterial bread, water, lava;
    private static boolean emptyMealFrame;
    static boolean tick() throws Exception {
        ticks++;
        if(work!=null) { if(!work.isDone()) return false;work.join();work=null; }
        if(ticks-changed>600) throw new IllegalStateException("Material client timeout phase="+phase);
        int elapsed=ticks-changed;
        switch(phase) {
            case 0 -> { server(MaterialsClientReview::setup);next(); }
            case 1 -> {
                if(elapsed<25 || PalletClient.candidates().size()!=4) break;
                bread=PalletClient.candidates().stream().filter(m->m.is(Items.BREAD)).findFirst().orElseThrow();
                water=PalletClient.candidates().stream().filter(m->m.fluid().getFluid()==Fluids.WATER).findFirst().orElseThrow();
                lava=PalletClient.candidates().stream().filter(m->m.fluid().getFluid()==Fluids.LAVA).findFirst().orElseThrow();
                for(String name:List.of("create_body_fixed","feed_socket_fixed","rails_fixed","head_fixed","fluid_pipe","fluid_head","fluid_glass"))
                    require(MC.getModelManager().getModel(PalletRenderer.modelId(name))!=MC.getModelManager().getMissingModel(),"r011 missing model "+name);
                PalletClient.mode(false);button(2,true);next();
            }
            case 2 -> { if(elapsed<8)break;select(m->m.is(Items.BREAD));next(); }
            case 3 -> { if(elapsed<15)break;require(PalletClient.selected().is(Items.BREAD),"Bread selection payload failed");receiptsBefore=PalletClient.feedReceipts();button(1,true);next(); }
            case 4 -> {
                if(elapsed<8)break;
                require(MC.player.isUsingItem() && MC.player.getUseItem().is(Items.BREAD)
                        && MC.player.getInventory().getItem(0).is(FmpRegistries.POCKET_PALLET.get()),"Timed client hand lost native food or real tool");
                TemplateClientReview.capture("materials-eat-native");button(1,false);next();
            }
            case 5 -> { if(elapsed<12)break;require(PalletClient.feedReceipts()==receiptsBefore,"Cancelled meal triggered feed");verifyCargoPose(false);TemplateClientReview.capture("materials-eat-cancelled");server(p->require(stock(p,0)==8 && !p.isUsingItem(),"Early release spent food or kept eating"));button(1,true);next(); }
            case 6 -> {
                if(PalletClient.feedReceipts()>receiptsBefore && !reloadSeen && PalletClient.feedPose(0).tray()<-8) {
                    reloadSeen=true;TemplateClientReview.capture("materials-eat-refeed");
                    FeedMePackages.LOGGER.info("FMP_EAT_REFEED receipts={} pose={}",PalletClient.feedReceipts()-receiptsBefore,PalletClient.feedPose(0));
                }
                if(elapsed>=4 && !firstUseFrame){verifyCargoPose(true);TemplateClientReview.capture("materials-eat-cargo-a");firstUseFrame=true;}
                if(elapsed>=12 && !secondUseFrame){verifyCargoPose(true);TemplateClientReview.capture("materials-eat-cargo-b");secondUseFrame=true;}
                if(elapsed<40)break;button(1,false);next();
            }
            case 7 -> {
                if(elapsed<12)break;require(PalletClient.feedReceipts()==receiptsBefore+1 && reloadSeen,"Completed meal did not visibly refeed exactly once: receipts="+(PalletClient.feedReceipts()-receiptsBefore)+" reload="+reloadSeen);verifyCargoPose(false);firstUseFrame=false;secondUseFrame=false;
                server(p->require(stock(p,0)==7 && p.getFoodData().getFoodLevel()>8 && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()),"Native held food did not complete exactly once"));
                button(2,true);next();
            }
            case 8 -> { if(elapsed<8)break;select(m->m.is(Items.POTION));next(); }
            case 9 -> { if(elapsed<15)break;receiptsBefore=PalletClient.feedReceipts();reloadSeen=false;button(1,true);next(); }
            case 10 -> {
                if(PalletClient.feedReceipts()>receiptsBefore && !reloadSeen && PalletClient.feedPose(0).tray()<-8) {
                    reloadSeen=true;TemplateClientReview.capture("materials-drink-refeed");
                }
                if(elapsed>=4 && !firstUseFrame){verifyCargoPose(true);TemplateClientReview.capture("materials-drink-cargo-a");firstUseFrame=true;}
                if(elapsed>=12 && !secondUseFrame){verifyCargoPose(true);TemplateClientReview.capture("materials-drink-cargo-b");secondUseFrame=true;}
                if(elapsed<40)break;button(1,false);next();
            }
            case 11 -> {
                if(elapsed<12)break;require(reloadSeen && PalletClient.feedReceipts()==receiptsBefore+1,"Drink did not refeed exactly once");verifyCargoPose(false);
                server(p->require(stock(p,1)==1 && p.getInventory().countItem(Items.GLASS_BOTTLE)==1
                        && p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),"Native potion lost effect/remainder or charged twice"));
                button(2,true);next();
            }
            case 12 -> { if(elapsed<8)break;TemplateClientReview.capture("materials-fluid-selector");select(m->!m.fluid().isEmpty());next(); }
            case 13 -> {
                if(elapsed<8)break;require(!PalletClient.selected().fluid().isEmpty(),"Fluid selection packet failed");
                require(PalletClient.modePose(0).progress()>0 && PalletClient.modePose(0).progress()<1,"Mode switch must animate");
                TemplateClientReview.capture("pallet-mode-item-retract");MC.player.setXRot(65);next();
            }
            case 14 -> {
                if(elapsed<5)break;require(PalletClient.modePose(0).progress()<1,"Fluid-use fixture must exercise use during morph");
                require(MC.hitResult instanceof BlockHitResult,"No native ground ray for fluid");
                var hit=(BlockHitResult)MC.hitResult;waterTarget=hit.getBlockPos().relative(hit.getDirection());
                button(1,true);next();
            }
            case 15 -> { if(elapsed<2)break;button(1,false);next(); }
            case 16 -> {
                if(elapsed<20)break;require(PalletClient.modePose(0).progress()==1,"Fluid mode never completed");TemplateClientReview.capture("pallet-mode-water-ready");
                server(p->{require(InteractionTests.fluidStock(p,2)==1500 && p.level().getFluidState(waterTarget).isSource()
                            && p.getInventory().countItem(Items.BUCKET)==0,"Pure fluid mouse use produced bucket or wrong source/debit");
                    p.getInventory().setItem(1,new ItemStack(Items.BUCKET,2));p.inventoryMenu.broadcastChanges();});
                MC.setScreen(new InventoryScreen(MC.player));next();
            }
            case 17 -> { if(elapsed<20 || LogisticsPanel.visibleCells(MC.screen).size()<3)break;TemplateClientReview.nativeSlotClick(37);next(); }
            case 18 -> {
                if(elapsed<10 || !MC.player.containerMenu.getCarried().is(Items.BUCKET))break;
                require(MC.player.containerMenu.getCarried().getCount()==2,"Cursor fixture did not pick two buckets");
                TemplateClientReview.cellClick(2,1);next();
            }
            case 19 -> {
                if(elapsed<20)break;
                require(MC.player.containerMenu.getCarried().is(Items.BUCKET) && MC.player.containerMenu.getCarried().getCount()==1
                        && MC.player.getInventory().countItem(Items.WATER_BUCKET)==1,"Client cursor fill did not reconcile one empty/one filled");
                server(p->require(InteractionTests.fluidStock(p,2)==500 && p.containerMenu.getCarried().getCount()==1
                        && p.getInventory().countItem(Items.WATER_BUCKET)==1,"Server cursor fill did not conserve containers/mB"));
                TemplateClientReview.capture("materials-cursor-fluid-filled");next();
            }
            case 20 -> {
                FeedMePackages.LOGGER.info("FMP_MATERIALS_CLIENT_PASSED selectorItemsAndFluids=true nativeFoodCancel=true nativeFoodFinish=true potionEffectRemainder=true fluidPlace=true cursorFill=true realToolPreserved=true");next();
            }
            case 21 -> {
                if(!net.neoforged.fml.ModList.get().isLoaded("jei")){MC.player.closeContainer();phase=23;changed=ticks;break;}
                MC.player.closeContainer();JeiClientReview.openSequence(FmpRegistries.POCKET_PALLET.get());next();
            }
            case 22 -> {if(elapsed<15)break;JeiClientReview.verifyGrowthPage();TemplateClientReview.capture("pallet-four-step-assembly-jei");MC.setScreen(null);next();}
            case 23 -> { MC.player.setXRot(-65);PalletClient.choose(lava);next(); }
            case 24 -> {
                if(elapsed<10)break;require(PalletClient.displayed().equals(lava) && PalletClient.modePose(0).progress()==1,"Changing fluid restarted the mechanical transform");
                verifyCargoPose(false);TemplateClientReview.capture("pallet-mode-lava-ready");
                MC.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.LEFT);MC.options.broadcastOptions();next();
            }
            case 25 -> {
                if(elapsed<5)break;verifyCargoPose(false);TemplateClientReview.capture("pallet-mode-fluid-left");
                MC.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);MC.options.broadcastOptions();
                PalletClient.choose(bread);next();
            }
            case 26 -> {
                if(elapsed<10)break;reversedFrom=PalletClient.modePose(0).progress();
                require(reversedFrom>0 && reversedFrom<1,"No reverse morph in progress");TemplateClientReview.capture("pallet-mode-fluid-retract");
                PalletClient.choose(water);next();
            }
            case 27 -> {
                if(elapsed<3)break;double progress=PalletClient.modePose(0).progress();
                require(progress>0 && progress<1 && Math.abs(progress-reversedFrom)<.3,"Mid-morph reversal snapped to an endpoint");next();
            }
            case 28 -> {
                if(elapsed<25)break;require(PalletClient.modePose(0).progress()==1 && PalletClient.displayed().equals(water),"Reverse did not restore fluid form");
                PalletClient.choose(bread);next();
            }
            case 29 -> {
                if(elapsed<35)break;require(PalletClient.modePose(0).itemReady() && PalletClient.feedPose(0).cargo(),"Return to item form lost material feed");
                TemplateClientReview.capture("pallet-mode-item-restored");
                server(p->{var ledger=CacheLedger.get(p.getServer());var handle=AccessGate.resolve(p).handle();var row=ledger.find(handle.cacheId());var edit=row.state().edit();edit.extract(0,6);ledger.replace(handle,row.state().revision(),row.withState(edit.finish()));p.getFoodData().setFoodLevel(8);});phase=40;changed=ticks;
            }
            case 30 -> {
                if(elapsed<15 || PalletClient.amount(bread)!=1)break;receiptsBefore=PalletClient.feedReceipts();button(1,true);next();
            }
            case 31 -> {
                if(elapsed>=8 && !emptyMealFrame) {verifyCargoPose(true);TemplateClientReview.capture("materials-last-food-held");emptyMealFrame=true;}
                if(elapsed<40)break;button(1,false);next();
            }
            case 32 -> {
                if(elapsed<12)break;require(PalletClient.feedReceipts()==receiptsBefore+1 && !PalletClient.hasCargo() && !PalletClient.feedPose(0).cargo(),"Last meal displayed a phantom refill");
                server(p->require(stock(p,0)==0,"Last meal debit was not exactly one"));TemplateClientReview.capture("materials-last-food-empty");next();
            }
            case 33 -> {
                button(1,true);next();
            }
            case 34 -> {
                if(elapsed<8)break;button(1,false);require(PalletClient.feedReceipts()==receiptsBefore+1,"Empty use triggered feed");
                server(p->{var handle=AccessGate.resolve(p).handle();var ledger=CacheLedger.get(p.getServer());var row=ledger.find(handle.cacheId());var edit=row.state().edit();edit.insert(0,row.state().cells().get(0).filter(),2);ledger.replace(handle,row.state().revision(),row.withState(edit.finish()));p.getFoodData().setFoodLevel(4);});next();
            }
            case 35 -> {
                if(elapsed<20 || PalletClient.amount(bread)!=2)break;require(PalletClient.feedPose(0).cargo(),"Restocked food never returned to jaws");
                receiptsBefore=PalletClient.feedReceipts();reloadSeen=false;emptyMealFrame=false;button(1,true);next();
            }
            case 36 -> {
                long count=PalletClient.feedReceipts()-receiptsBefore;
                if(count==1 && PalletClient.feedPose(0).tray()<-8 && !reloadSeen){reloadSeen=true;TemplateClientReview.capture("materials-continuous-refeed");}
                if(elapsed<85)break;button(1,false);require(reloadSeen && count==2,"Continuous eating lost/doubled feed receipts: "+count);next();
            }
            case 37 -> {
                if(elapsed<12)break;server(p->require(stock(p,0)==0 && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()),"Continuous meals did not conserve food/tool"));
                require(!PalletClient.feedPose(0).cargo(),"Continuous meals left a phantom third item");
                work=MC.reloadResourcePacks();next();
            }
            case 38 -> {
                if(elapsed<5)break;require(PalletClient.modePose(0).itemReady(),"Reload retained a stale morph");
                FeedMePackages.LOGGER.info("FMP_PALLET_R011_PASSED modeSwitch=true useDuringMorph=true reverseMidway=true actualFluids=true leftHand=true cancelNoFeed=true lastFoodCancelNoFeed=true foodFeedOnce=true drinkFeedOnce=true lastFood=true restock=true continuousMeals=true resourceReload=true");next();
            }
            case 39 -> {return !net.neoforged.fml.ModList.get().isLoaded("ars_nouveau") || ArsClientReview.tick();}
            case 40 -> {
                if(elapsed<15 || PalletClient.amount(bread)!=1)break;receiptsBefore=PalletClient.feedReceipts();button(1,true);next();
            }
            case 41 -> {
                if(elapsed<8)break;require(MC.player.isUsingItem(),"Last-food cancellation fixture never started");button(1,false);next();
            }
            case 42 -> {
                require(PalletClient.feedReceipts()==receiptsBefore && PalletClient.feedPose(0).tray()==0,"Returning an unconsumed last food replayed the feeding animation");
                if(elapsed<15)break;require(PalletClient.hasCargo() && PalletClient.amount(bread)==1,"Last-food cancellation lost the displayed material");
                server(p->require(stock(p,0)==1 && !p.isUsingItem(),"Last-food cancellation changed real stock"));
                TemplateClientReview.capture("materials-last-food-cancelled");phase=30;changed=ticks;
            }
        }
        return false;
    }
    private static void verifyCargoPose(boolean active) throws Exception {
        var method=PalletRenderer.class.getDeclaredMethod("usePose",net.minecraft.client.player.LocalPlayer.class,ItemStack.class,float.class);
        method.setAccessible(true);var cargo=PalletClient.displayed().item();
        var pose=(dev.scathiard.feedmepackages.domain.PalletUsePose)method.invoke(null,MC.player,cargo,.25f);
        require(active?!pose.equals(dev.scathiard.feedmepackages.domain.PalletUsePose.REST):pose.equals(dev.scathiard.feedmepackages.domain.PalletUsePose.REST),"Cargo consumption pose disagrees with native lifecycle: "+pose);
        if(active)require(method.invoke(null,MC.player,new ItemStack(Items.STONE),.25f).equals(dev.scathiard.feedmepackages.domain.PalletUsePose.REST),"Use animation leaked onto another selected material");
        var body=PalletClientReview.class.getDeclaredMethod("verifyHandBody");body.setAccessible(true);body.invoke(null);
        FeedMePackages.LOGGER.info("FMP_CARGO_USE_POSE active={} lift={} turn={} housingFixed=true",active,pose.lift(),pose.turn());
    }
    private static void setup(ServerPlayer p) {
        p.closeContainer();p.getInventory().clearContent();TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        p.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        for(int x=5;x<=12;x++) for(int z=5;z<=12;z++) p.serverLevel().setBlock(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState(),3);
        p.teleportTo(p.serverLevel(),8.5,4,8.5,0,-85);p.getFoodData().setFoodLevel(8);p.getFoodData().setSaturation(0);
        ConsumptionTests.seed(p,0,new ItemStack(Items.BREAD),8);
        var potion=new ItemStack(Items.POTION);potion.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.FIRE_RESISTANCE));
        ConsumptionTests.seed(p,1,potion,2);InteractionTests.seedFluid(p,2,new FluidStack(Fluids.WATER,2500));InteractionTests.seedFluid(p,3,new FluidStack(Fluids.LAVA,2000));
        p.getInventory().selected=0;p.getInventory().setItem(0,FmpRegistries.POCKET_PALLET.toStack());p.inventoryMenu.broadcastChanges();
    }
    private static long stock(ServerPlayer p,int index) { return CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId()).state().cells().get(index).amount(); }
    @SuppressWarnings("unchecked") private static void select(Predicate<PalletMaterial> match) throws Exception {
        require(MC.screen instanceof PalletScreen,"Native middle input did not open selector");
        var shown=PalletScreen.class.getDeclaredField("shown");shown.setAccessible(true);var choices=(List<PalletMaterial>)shown.get(MC.screen);
        int index=-1;for(int i=0;i<choices.size();i++)if(match.test(choices.get(i))){index=i;break;}
        require(index>=0,"Requested material missing in selector");
        var method=PalletScreen.class.getDeclaredMethod("layout");method.setAccessible(true);var layout=(dev.scathiard.feedmepackages.domain.PalletLayout.View)method.invoke(MC.screen);
        int wanted=index;var tile=layout.tiles().stream().filter(t->t.index()==wanted).findFirst().orElseThrow();
        TemplateClientReview.move(tile.x()+12,tile.y()+12);MC.mouseHandler.handleAccumulatedMovement();button(2,false);
    }
    private static void button(int button,boolean down) throws Exception { var method=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),button,down?1:0,0); }
    private static void server(Consumer<ServerPlayer> action) { var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id))); }
    private static void next() { FeedMePackages.LOGGER.info("FMP_MATERIALS_CLIENT_STAGE {} complete",phase);phase++;changed=ticks; }
    private static void require(boolean ok,String message) { if(!ok)throw new IllegalStateException(message); }
}
