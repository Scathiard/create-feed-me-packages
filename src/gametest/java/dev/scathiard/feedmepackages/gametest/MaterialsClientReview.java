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
    static boolean tick() throws Exception {
        ticks++;
        if(work!=null) { if(!work.isDone()) return false;work.join();work=null; }
        if(ticks-changed>600) throw new IllegalStateException("Material client timeout phase="+phase);
        int elapsed=ticks-changed;
        switch(phase) {
            case 0 -> { server(MaterialsClientReview::setup);next(); }
            case 1 -> {
                if(elapsed<25 || PalletClient.candidates().size()!=3) break;
                PalletClient.mode(false);button(2,true);next();
            }
            case 2 -> { if(elapsed<8)break;select(m->m.is(Items.BREAD));next(); }
            case 3 -> { if(elapsed<15)break;require(PalletClient.selected().is(Items.BREAD),"Bread selection payload failed");button(1,true);next(); }
            case 4 -> {
                if(elapsed<8)break;
                require(MC.player.isUsingItem() && MC.player.getUseItem().is(Items.BREAD)
                        && MC.player.getInventory().getItem(0).is(FmpRegistries.POCKET_PALLET.get()),"Timed client hand lost native food or real tool");
                TemplateClientReview.capture("materials-eat-native");button(1,false);next();
            }
            case 5 -> { if(elapsed<12)break;server(p->require(stock(p,0)==8 && !p.isUsingItem(),"Early release spent food or kept eating"));button(1,true);next(); }
            case 6 -> { if(elapsed<40)break;button(1,false);next(); }
            case 7 -> {
                if(elapsed<12)break;
                server(p->require(stock(p,0)==7 && p.getFoodData().getFoodLevel()>8 && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()),"Native held food did not complete exactly once"));
                button(2,true);next();
            }
            case 8 -> { if(elapsed<8)break;select(m->m.is(Items.POTION));next(); }
            case 9 -> { if(elapsed<15)break;button(1,true);next(); }
            case 10 -> { if(elapsed<40)break;button(1,false);next(); }
            case 11 -> {
                if(elapsed<12)break;
                server(p->require(stock(p,1)==1 && p.getInventory().countItem(Items.GLASS_BOTTLE)==1
                        && p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),"Native potion lost effect/remainder or charged twice"));
                button(2,true);next();
            }
            case 12 -> { if(elapsed<8)break;TemplateClientReview.capture("materials-fluid-selector");select(m->!m.fluid().isEmpty());next(); }
            case 13 -> { if(elapsed<15)break;require(!PalletClient.selected().fluid().isEmpty(),"Fluid selection packet failed");MC.player.setXRot(65);next(); }
            case 14 -> {
                if(elapsed<10)break;
                require(MC.hitResult instanceof BlockHitResult,"No native ground ray for fluid");
                var hit=(BlockHitResult)MC.hitResult;waterTarget=hit.getBlockPos().relative(hit.getDirection());
                button(1,true);next();
            }
            case 15 -> { if(elapsed<2)break;button(1,false);next(); }
            case 16 -> {
                if(elapsed<15)break;
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
            case 21 -> {return !net.neoforged.fml.ModList.get().isLoaded("ars_nouveau") || ArsClientReview.tick();}
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        p.closeContainer();p.getInventory().clearContent();TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        p.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        for(int x=5;x<=12;x++) for(int z=5;z<=12;z++) p.serverLevel().setBlock(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState(),3);
        p.teleportTo(p.serverLevel(),8.5,4,8.5,0,-85);p.getFoodData().setFoodLevel(8);p.getFoodData().setSaturation(0);
        ConsumptionTests.seed(p,0,new ItemStack(Items.BREAD),8);
        var potion=new ItemStack(Items.POTION);potion.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.FIRE_RESISTANCE));
        ConsumptionTests.seed(p,1,potion,2);InteractionTests.seedFluid(p,2,new FluidStack(Fluids.WATER,2500));
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
