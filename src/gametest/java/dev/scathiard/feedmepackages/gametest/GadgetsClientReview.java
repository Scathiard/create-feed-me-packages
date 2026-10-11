package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.concurrent.CompletableFuture;

/** Native renderer preview, use-item packet, author queue and server debit in a fresh world. */
final class GadgetsClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final String ROOT="com.direwolf20.buildinggadgets2.";
    private static final BlockPos SUPPORT=new BlockPos(0,-60,3), TARGET=SUPPORT.above();
    private static int stage,ticks,changed;
    private static CompletableFuture<Void> work;
    @FunctionalInterface private interface ServerAction { void run(ServerPlayer p) throws ReflectiveOperationException; }
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null) { if(!work.isDone())return false;work.join();work=null; }
        require(ticks-changed<600,"Gadgets client timeout stage "+stage);
        switch(stage) {
            case 0 -> {
                MC.player.closeContainer();MC.setScreen(null);
                server(p->{
                    p.getInventory().clearContent();
                    var gadget=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("buildinggadgets2:gadget_building")));
                    require(!gadget.isEmpty(),"Native gadget missing");
                    var nbt=Class.forName(ROOT+"util.GadgetNBT");
                    nbt.getMethod("setGadgetBlockState",ItemStack.class,BlockState.class).invoke(null,gadget,Blocks.STONE.defaultBlockState());
                    nbt.getMethod("setToolRange",ItemStack.class,int.class).invoke(null,gadget,1);
                    var mode=Class.forName(ROOT+"util.modes.Surface").getConstructor(boolean.class).newInstance(false);
                    nbt.getMethod("setMode",ItemStack.class,Class.forName(ROOT+"util.modes.BaseMode")).invoke(null,gadget,mode);
                    gadget.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM).receiveEnergy(100000,false);
                    p.setItemInHand(InteractionHand.MAIN_HAND,gadget);ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),3);
                    p.serverLevel().setBlockAndUpdate(SUPPORT,Blocks.STONE.defaultBlockState());p.serverLevel().removeBlock(TARGET,false);
                    p.connection.teleport(.5,-60,.5,0,12);p.inventoryMenu.broadcastChanges();
                });next();
            }
            case 1 -> {
                if(ticks-changed<40 || count()!=3 || !preview())return false;
                require(chatCount("gadgets_")==0,"Healthy adapter printed warning");
                server(p->require(ConsumptionTests.stock(p,0)==3 && p.serverLevel().isEmptyBlock(TARGET),"Native preview spent materials/placed blocks"));
                TemplateClientReview.capture("gadgets-native-cache-preview");next();
            }
            case 2 -> { MC.gameMode.useItem(MC.player,InteractionHand.MAIN_HAND);next(); }
            case 3 -> {
                if(ticks-changed<30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE) || count()!=2)return false;
                server(p->{
                    require(ConsumptionTests.stock(p,0)==2,"Native use packet did not debit exactly one cached stone");
                    FmpConfig.GADGETS.set(false);FmpConfig.refreshServer();p.serverLevel().removeBlock(TARGET,false);
                    p.getInventory().setItem(9,new ItemStack(Items.STONE));p.inventoryMenu.broadcastChanges();
                });next();
            }
            case 4 -> {
                if(ticks-changed<60 || chatCount("gadgets_config_disabled")!=1 || count()!=1)return false;
                TemplateClientReview.capture("gadgets-disabled-warning");MC.gameMode.useItem(MC.player,InteractionHand.MAIN_HAND);next();
            }
            case 5 -> {
                if(ticks-changed<30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE))return false;
                require(chatCount("gadgets_config_disabled")==1,"Disabled warning repeated");
                server(p->{
                    require(ConsumptionTests.stock(p,0)==2 && p.getInventory().getItem(9).isEmpty(),"Disabled adapter changed cache/native fallback");
                    FmpConfig.GADGETS.set(true);FmpConfig.refreshServer();
                });next();
            }
            case 6 -> {
                if(ticks-changed<40 || count()!=2)return false;
                FeedMePackages.LOGGER.info("FMP_GADGETS_CLIENT_ACCOUNT initial=3 preview=3 placed=1 finalCache=2 disabledNative=1");return true;
            }
        }
        return false;
    }
    private static int count() throws ReflectiveOperationException {
        return (int)Class.forName(ROOT+"util.BuildingUtils").getMethod("countItemStacks",Player.class,ItemStack.class).invoke(null,MC.player,new ItemStack(Items.STONE));
    }
    private static boolean preview() throws ReflectiveOperationException {
        var field=Class.forName(ROOT+"client.renderer.VBORenderer").getDeclaredField("statePosCache");field.setAccessible(true);
        var list=(java.util.List<?>)field.get(null); if(list==null || list.isEmpty())return false;
        for(var item:list) if(item.getClass().getField("pos").get(item).equals(new BlockPos(0,1,0)))return true;
        return false;
    }
    private static int chatCount(String suffix) throws ReflectiveOperationException {
        var chat=MC.gui.getChat();var field=chat.getClass().getDeclaredField("allMessages");field.setAccessible(true);int count=0;
        for(Object value:(java.util.List<?>)field.get(chat)) {
            var content=((net.minecraft.client.GuiMessage)value).content().getContents();
            if(content instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().startsWith("gui.create_feed_me_packages.compat."+suffix))count++;
        }
        return count;
    }
    private static void server(ServerAction action) {
        var id=MC.player.getUUID();var server=MC.getSingleplayerServer();
        work=server.submit(()->{try {action.run(server.getPlayerList().getPlayer(id));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}});
    }
    private static void next() {stage++;changed=ticks;FeedMePackages.LOGGER.info("FMP_GADGETS_CLIENT_STAGE {}",stage);}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
