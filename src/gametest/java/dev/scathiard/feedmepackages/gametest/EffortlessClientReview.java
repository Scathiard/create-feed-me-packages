package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The author's preview and right-click entry send the real build packet to the integrated server. */
final class EffortlessClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final String ROOT=EffortlessTests.ROOT;
    private static final BlockPos SUPPORT=new BlockPos(0,-60,3), TARGET=SUPPORT.above();
    private static int stage,ticks,changed;
    private static CompletableFuture<Void> work;
    @FunctionalInterface private interface ServerAction {void run(ServerPlayer player) throws ReflectiveOperationException;}
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        require(ticks-changed<600,"Effortless client timeout stage "+stage);
        switch(stage) {
            case 0 -> {
                MC.player.closeContainer();MC.setScreen(null);
                singleMode();
                server(p->{
                    p.getInventory().clearContent();
                    var item=BuiltInRegistries.ITEM.stream().filter(i->i.getClass().getName().equals(ROOT+"item.RandomizerToolItem")).findFirst().orElseThrow();
                    var tool=new ItemStack(item);Class.forName(ROOT+"item.RandomizerToolData").getMethod("setConfiguration",ItemStack.class,List.class,List.class).invoke(null,tool,List.of(Items.STONE),List.of(1));
                    p.getInventory().setItem(0,tool);ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),3);
                    p.serverLevel().setBlockAndUpdate(SUPPORT,Blocks.STONE.defaultBlockState());p.serverLevel().removeBlock(TARGET,false);
                    p.connection.teleport(.5,-60,.5,0,12);p.inventoryMenu.broadcastChanges();
                });next();
            }
            case 1 -> {
                if(ticks-changed<40 || count()!=3 || !preview())return false;
                require(warnings("effortless_")==0,"Healthy adapter printed warning");
                server(p->require(ConsumptionTests.stock(p,0)==3 && p.serverLevel().isEmptyBlock(TARGET),"Native preview spent cache or changed world"));
                TemplateClientReview.capture("effortless-native-cache-preview");next();
            }
            case 2 -> {click();next();}
            case 3 -> {
                if(ticks-changed<30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE) || count()!=2)return false;
                server(p->{
                    require(ConsumptionTests.stock(p,0)==2,"Native build packet did not spend one cached stone");
                    FmpConfig.EFFORTLESS.set(false);FmpConfig.refreshServer();p.serverLevel().removeBlock(TARGET,false);
                    p.getInventory().setItem(9,new ItemStack(Items.STONE));p.inventoryMenu.broadcastChanges();
                });next();
            }
            case 4 -> {
                if(ticks-changed<60 || warnings("effortless_config_disabled")!=1 || count()!=1 || !preview())return false;
                TemplateClientReview.capture("effortless-disabled-warning");click();next();
            }
            case 5 -> {
                if(ticks-changed<30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE))return false;
                require(warnings("effortless_config_disabled")==1,"Disable warning repeated");
                server(p->{
                    require(ConsumptionTests.stock(p,0)==2 && p.getInventory().getItem(9).isEmpty(),"Disabled native fallback spent cache");
                    FmpConfig.EFFORTLESS.set(true);FmpConfig.refreshServer();
                });next();
            }
            case 6 -> {if(ticks-changed<40 || count()!=2)return false;return true;}
            default -> throw new IllegalStateException("Unexpected Effortless stage "+stage);
        }
        return false;
    }
    static boolean mismatchTick() throws ReflectiveOperationException {
        ticks++;require(EffortlessTests.mismatchFixture(),"Wrong mismatch runtime fixture");
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        require(ticks-changed<600,"Effortless mismatch timeout stage "+stage);
        switch(stage) {
            case 0 -> {
                MC.player.closeContainer();MC.setScreen(null);singleMode();
                server(p->{
                    require(dev.scathiard.feedmepackages.network.CompatibilityStatus.effortless()==dev.scathiard.feedmepackages.network.CompatibilityStatus.State.API_MISMATCH,"Server did not identify actual API mismatch");
                    p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.STONE));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),3);
                    p.serverLevel().setBlockAndUpdate(SUPPORT,Blocks.STONE.defaultBlockState());p.serverLevel().removeBlock(TARGET,false);
                    p.connection.teleport(.5,-60,.5,0,12);p.inventoryMenu.broadcastChanges();
                });next();
            }
            case 1 -> {
                if(ticks-changed<60 || count()!=1 || warnings("effortless_server_mismatch")!=1)return false;
                require(!dev.scathiard.feedmepackages.mixin.OptionalMixins.effortlessCompatible(),"Client loaded partial incompatible adapter");
                TemplateClientReview.capture("effortless40-actual-mismatch-warning");click();next();
            }
            case 2 -> {
                if(ticks-changed<30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE))return false;
                server(p->require(p.getMainHandItem().isEmpty() && ConsumptionTests.stock(p,0)==3
                        && dev.scathiard.feedmepackages.service.AccessGate.resolve(p).active(),"Old native builder lost inventory fallback or normal cache access"));next();
            }
            case 3 -> {
                if(ticks-changed<60)return false;
                require(count()==0 && warnings("effortless_server_mismatch")==1,"Mismatch leaked cached materials or repeated warning");return true;
            }
            default -> throw new IllegalStateException("Unexpected mismatch stage "+stage);
        }return false;
    }
    private static void singleMode() throws ReflectiveOperationException {
        var modes=Class.forName(ROOT+"buildmode.BuildModes");var mode=Class.forName(ROOT+"buildmode.BuildModeEnum");
        modes.getMethod("setBuildMode",mode).invoke(modes.getField("CLIENT").get(null),mode.getField("SINGLE").get(null));
        var settings=Class.forName(ROOT+"buildmode.BuildSettings");var replace=Class.forName(ROOT+"buildmode.BuildSettings$ReplaceMode");
        settings.getMethod("setReplaceMode",replace).invoke(settings.getField("CLIENT").get(null),replace.getField("ONLY_AIR").get(null));
    }
    private static void click() throws ReflectiveOperationException {Class.forName(ROOT+"buildpipeline.BuildPipelineClient").getMethod("handleRightClick",Minecraft.class).invoke(null,MC);}
    private static int count() throws ReflectiveOperationException {
        return (int)Class.forName(ROOT+"utilities.InventoryHelper").getMethod("findTotalItemsInInventory",Player.class,Item.class).invoke(null,MC.player,Items.STONE);
    }
    private static boolean preview() throws ReflectiveOperationException {
        var type=Class.forName(ROOT+"buildpipeline.BuildPipelineClient");
        var preview=type.getMethod("getPreviewBlocks",Minecraft.class).invoke(null,MC);
        if(!(preview instanceof Map<?,?> blocks) || !blocks.containsKey(TARGET))return false;
        var usage=type.getField("ITEM_USAGE").get(null);
        return ((Map<?,?>)usage.getClass().getField("inInventory").get(usage)).get(Items.STONE) instanceof Integer amount && amount==count()
                && !(boolean)usage.getClass().getMethod("hasMissing").invoke(usage);
    }
    private static int warnings(String suffix) throws ReflectiveOperationException {
        var chat=MC.gui.getChat();var field=chat.getClass().getDeclaredField("allMessages");field.setAccessible(true);int count=0;
        for(var item:(List<?>)field.get(chat))if(((net.minecraft.client.GuiMessage)item).content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().startsWith("gui.create_feed_me_packages.compat."+suffix))count++;
        return count;
    }
    private static void server(ServerAction action) {
        var id=MC.player.getUUID();var server=MC.getSingleplayerServer();
        work=server.submit(()->{try{action.run(server.getPlayerList().getPlayer(id));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}});
    }
    private static void next(){stage++;changed=ticks;FeedMePackages.LOGGER.info("FMP_EFFORTLESS_CLIENT_STAGE {}",stage);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
