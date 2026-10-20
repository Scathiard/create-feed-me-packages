package dev.scathiard.feedmepackages.gametest;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native key, timed reload, held-gun switch cancellation and client/server ammunition accounting. */
final class SbwClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed;
    private static long retained;
    private static CompletableFuture<Void> work;
    static boolean tick() throws Exception {
        ticks++;
        if(work!=null) {if(!work.isDone())return false;work.join();work=null;}
        int elapsed=ticks-changed;
        if(elapsed>700)throw new IllegalStateException("SBW client timeout phase="+phase+" loaded="+loaded());
        switch(phase) {
            case 0 -> {server(p->{p.getInventory().clearContent();equip(p);ConsumptionTests.seed(p,0,Ammo.RIFLE.getItemStack(),64);p.inventoryMenu.broadcastChanges();});next();}
            case 1 -> {
                if(elapsed<60 || !ClientMaterials.active() || !ClientCompatibility.sbwSourcesAvailable())break;
                require(GunData.from(MC.player.getMainHandItem()).countBackupAmmo(MC.player)==64*Ammo.RIFLE.getItem().getAmmoToAdd(),"Native client ammo query missed cache-only reserve");
                reloadKey();next();
            }
            case 2 -> {if(elapsed<6)break;MC.player.getInventory().selected=1;next();}
            case 3 -> {
                if(elapsed<60)break;server(p->require(ConsumptionTests.stock(p,0)==64,"Canceled real keyboard reload spent cached ammunition"));
                MC.player.getInventory().selected=0;next();
            }
            case 4 -> {if(elapsed<30)break;reloadKey();next();}
            case 5 -> {
                if(elapsed<120 || loaded()!=30)break;
                server(p->{var gun=GunData.from(p.getMainHandItem());int per=Ammo.RIFLE.getItem().getAmmoToAdd();retained=ConsumptionTests.stock(p,0);
                    require(retained<64 && gun.ammo.get()+gun.virtualAmmo.get()+Ammo.RIFLE.get(p)+retained*per==64*per,"Native R reload lost conversion/remainder or charged twice");});
                TemplateClientReview.capture("sbw-native-cache-reload");next();
            }
            case 6 -> {
                server(p->{FmpConfig.SBW.set(false);FmpConfig.refreshServer();equip(p);p.getInventory().setItem(1,Ammo.RIFLE.getItemStack());p.inventoryMenu.broadcastChanges();});next();
            }
            case 7 -> {if(elapsed<60 || ClientCompatibility.sbwSourcesAvailable())break;reloadKey();next();}
            case 8 -> {
                if(elapsed<120 || loaded()==0)break;
                server(p->{require(ConsumptionTests.stock(p,0)==retained && p.getInventory().getItem(1).isEmpty(),"Disabled FMP broke native ammo or spent cache");FmpConfig.SBW.set(true);FmpConfig.refreshServer();});next();
            }
            case 9 -> {
                FeedMePackages.LOGGER.info("FMP_SBW_CLIENT_PASSED nativeReloadKey=true nativeDuration=true switchCancel=true ammoConversion=true serverExactDebit=true disabledPhysicalFallback=true");return true;
            }
        }
        return false;
    }
    private static void equip(ServerPlayer p) {
        p.getInventory().selected=0;var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("superbwarfare:ak_47")));
        var gun=GunData.from(stack);gun.ammo.set(0);gun.virtualAmmo.set(0);gun.save();Ammo.RIFLE.set(p,0);p.getInventory().setItem(0,stack);
    }
    private static int loaded() {var stack=MC.player.getMainHandItem();return stack.getItem() instanceof com.atsuishio.superbwarfare.item.gun.GunItem?GunData.from(stack).ammo.get():-1;}
    private static void reloadKey() throws Exception {
        var method=KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);method.setAccessible(true);
        int key=ModKeyMappings.RELOAD.getKey().getValue();method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,1,0);method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,0,0);
    }
    private static void server(Consumer<ServerPlayer> action) {var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id)));}
    private static void next() {FeedMePackages.LOGGER.info("FMP_SBW_CLIENT_STAGE {} complete",phase);phase++;changed=ticks;}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
