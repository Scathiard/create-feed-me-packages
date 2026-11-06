package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.*;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real client interaction packets, including the client-side maid/item interaction code absent from GameTests. */
final class MaidHandoverClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed,maidId;
    private static UUID cache;
    private static CompletableFuture<Void> work;
    static boolean tick() {
        ticks++;
        if(ticks-changed>600)throw new IllegalStateException("Maid handover timeout phase="+phase);
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        if(ticks-changed<25)return false;
        switch(phase) {
            case 0 -> {server(MaidHandoverClientReview::setup);next();}
            case 1,3,5 -> {
                var maid=MC.level.getEntity(maidId);if(maid==null||!PackageItem.isPackage(MC.player.getMainHandItem()))return false;
                MC.setScreen(null);
                var at=MC.gameMode.interactAt(MC.player,maid,new net.minecraft.world.phys.EntityHitResult(maid,maid.position()),InteractionHand.MAIN_HAND);
                if(!at.consumesAction())MC.gameMode.interact(MC.player,maid,InteractionHand.MAIN_HAND);
                next();
            }
            case 2 -> {server(p->{check(p,16,false);give(p,16,true);});next();}
            case 4 -> {server(p->{check(p,32,false);give(p,100,true);});next();}
            case 6 -> {
                server(p->check(p,128,true));next();
            }
            case 7 -> {
                FeedMePackages.LOGGER.info("FMP_MAID_HANDOVER_CLIENT_PASSED nativeRightClick=true unsigned=16 signed=16 partial=96 residual=4 handEmpty=true");
                return true;
            }
        }
        return false;
    }
    private static TamableAnimal maid(ServerPlayer p){return (TamableAnimal)p.serverLevel().getEntity(maidId);}
    private static void setup(ServerPlayer p) {
        p.closeContainer();p.getInventory().clearContent();ConsumptionTests.seed(p,0,new ItemStack(Items.BREAD),0);
        cache=AccessGate.resolve(p).handle().cacheId();
        var pendant=TestPlayers.necklace(p).getStackInSlot(0);TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);
        var maid=(TamableAnimal)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(p.serverLevel());
        maid.setTame(true,false);maid.setOwnerUUID(p.getUUID());maid.setNoAi(true);maid.setNoGravity(true);
        maid.setPos(p.position().add(1,0,0));require(p.serverLevel().addFreshEntity(maid),"Maid spawn failed");maidId=maid.getId();
        var curios=CuriosApi.getCuriosInventory(maid).orElseThrow();curios.reset();
        curios.getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0,pendant);
        require(MaidAccess.resolve(maid).active(),"Maid did not get original cache");give(p,16,false);
    }
    private static void give(ServerPlayer p,int amount,boolean signed) {
        var maid=maid(p);var box=PackageItem.containing(List.of(new ItemStack(Items.BREAD,amount)));
        var address=MaidAccess.address(maid);PackageItem.addAddress(box,address);
        if(signed){var ledger=CacheLedger.get(p.getServer());var record=ledger.find(cache);
            var key=ItemVariantKey.of(new ItemStack(Items.BREAD),p.registryAccess());
            ParcelAuthentication.apply(ledger,p.registryAccess(),box,new ParcelSeal(UUID.randomUUID(),cache,maid.getUUID(),
                    UUID.randomUUID(),key.encoded(),record.state().cells().get(0).filterRevision(),address,false,""));}
        p.getInventory().selected=0;p.setItemInHand(InteractionHand.MAIN_HAND,box);p.inventoryMenu.broadcastChanges();
    }
    private static void check(ServerPlayer p,long amount,boolean residual) {
        var record=CacheLedger.get(p.getServer()).find(cache);
        require(record.state().cells().get(0).amount()==amount,"Handover stock differs: "+record.state().cells().get(0).amount());
        require(p.getMainHandItem().isEmpty(),"Consumed parcel remained in hand");
        require(record.anyResidual()==residual,"Handover residual differs");
        if(residual){var box=record.residuals().values().iterator().next();
            var contents=PackageItem.getContents(box);int left=0;
            for(int slot=0;slot<contents.getSlots();slot++){var stack=contents.getStackInSlot(slot);
                require(stack.isEmpty()||stack.is(Items.BREAD),"Residual changed item identity");left+=stack.getCount();}
            require(left==4,"Partial remainder lost goods: "+left);}
    }
    private static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id)));}
    private static void next(){phase++;changed=ticks;FeedMePackages.LOGGER.info("FMP_MAID_HANDOVER_PHASE {}",phase);}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
