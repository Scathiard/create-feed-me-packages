package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real spell book, native right mouse button and Ars network cast; no direct resolver invocation. */
final class ArsClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed;
    private static BlockPos target;
    private static CompletableFuture<Void> work;
    static boolean tick() throws Exception {
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        int elapsed=ticks-changed;if(elapsed>600)throw new IllegalStateException("Ars client timeout phase="+phase);
        switch(phase) {
            case 0 -> {MC.player.closeContainer();MC.setScreen(null);server(ArsClientReview::setup);next();}
            case 1 -> {
                if(elapsed<40)break;
                require(MC.hitResult instanceof BlockHitResult,"Native spell needs a real ground target");var hit=(BlockHitResult)MC.hitResult;target=hit.getBlockPos().relative(hit.getDirection());
                button(true);next();
            }
            case 2 -> {button(false);next();}
            case 3 -> {
                if(elapsed<30)break;
                server(p->{require(p.level().getBlockState(target).is(Blocks.OAK_PLANKS) && ConsumptionTests.stock(p,0)==7,"Real spell book lost native hotbar priority");p.serverLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());});next();
            }
            case 4 -> {if(elapsed<25)break;button(true);next();}
            case 5 -> {button(false);next();}
            case 6 -> {
                if(elapsed<30)break;
                server(p->{require(p.level().getBlockState(target).is(Blocks.STONE) && ConsumptionTests.stock(p,0)==6,"Real right-click book did not take cached stone exactly once");
                    p.serverLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());var mana=com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry.getMana(p);mana.setMana(0);mana.setMaxMana(0);});
                TemplateClientReview.capture("ars-native-book-cache-placement");next();
            }
            case 7 -> {if(elapsed<25)break;button(true);next();}
            case 8 -> {button(false);next();}
            case 9 -> {
                if(elapsed<15)break;server(p->require(p.level().getBlockState(target).isAir() && ConsumptionTests.stock(p,0)==6,"Real book bypassed mana gate"));next();
            }
            case 10 -> {FeedMePackages.LOGGER.info("FMP_ARS_CLIENT_PASSED nativeBookMouse=true nativePacket=true physicalFirst=true cacheFallback=true manaGate=true");return true;}
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        p.getInventory().clearContent();TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        for(int x=5;x<=12;x++)for(int z=5;z<=12;z++)p.serverLevel().setBlockAndUpdate(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState());
        p.teleportTo(p.serverLevel(),8.5,4,8.5,0,60);p.getInventory().selected=0;
        ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),7);
        var book=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ars_nouveau:novice_spell_book")));require(!book.isEmpty(),"Native spell book missing");
        var spell=new com.hollingsworth.arsnouveau.api.spell.Spell(com.hollingsworth.arsnouveau.common.spell.method.MethodTouch.INSTANCE,com.hollingsworth.arsnouveau.common.spell.effect.EffectPlaceBlock.INSTANCE);
        new com.hollingsworth.arsnouveau.api.spell.SpellCaster().setSpell(spell).saveToStack(book);
        p.getInventory().setItem(0,book);p.getInventory().setItem(1,new ItemStack(Items.OAK_PLANKS));
        var mana=com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry.getMana(p);mana.setMaxMana(1000);mana.setMana(1000);p.inventoryMenu.broadcastChanges();
    }
    private static void button(boolean down) throws Exception {var method=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),1,down?1:0,0);}
    private static void server(Consumer<ServerPlayer> action) {var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id)));}
    private static void next() {FeedMePackages.LOGGER.info("FMP_ARS_CLIENT_STAGE {} complete",phase);phase++;changed=ticks;}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
