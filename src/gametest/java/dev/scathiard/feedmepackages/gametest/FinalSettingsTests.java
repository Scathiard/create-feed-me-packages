package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.network.*;
import dev.scathiard.feedmepackages.client.ClosingSettings;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

@GameTestHolder("create_feed_me_packages")
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class FinalSettingsTests {
    private record Fixture(ServerPlayer player,PanelPackets.Snapshot view) {}
    private static Fixture fixture(GameTestHelper h) {
        var p=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),8);
        return new Fixture(p,PanelNetwork.query(p,new PanelPackets.Query(UUID.randomUUID(),0,true)));
    }
    private static PanelPackets.FinalSettings packet(Fixture f,int sequence,int batch) {
        return new PanelPackets.FinalSettings(0,new PanelPackets.SettingsBatch(f.view().window(),f.view().session(),sequence,List.of(
                new CacheActions.SettingsChange(0,f.view().cells().getFirst().filterRevision(),new CellSettings(64,128,batch,-1,1)))));
    }
    @GameTest(template="empty")
    public static void finalSettingsSurviveBudgetAndCloseWithoutReplay(GameTestHelper h) {
        var f=fixture(h);var p=f.player();
        for(int i=1;i<=18;i++)PanelNetwork.settings(p,packet(f,i,2).settings());
        var last=packet(f,19,3);var reply=PanelNetwork.finalSettings(p,last);
        h.assertTrue(reply.result()==CacheActions.Result.OK,"Final settings lost behind spent action budget");
        var record=CacheLedger.get(p.getServer()).find(AccessGate.resolve(p).handle().cacheId());
        h.assertTrue(record.state().cells().getFirst().settings().batch()==3,"Final draft not applied");
        PanelNetwork.query(p,new PanelPackets.Query(f.view().window(),0,false));
        h.assertTrue(PanelNetwork.finalSettings(p,last).equals(reply),"Duplicate after close lost its receipt");
        h.assertTrue(PanelNetwork.finalSettings(p,packet(f,20,4)).result()==CacheActions.Result.STALE,"Second final request reauthorized a closed session");
        h.assertTrue(record.equals(CacheLedger.get(p.getServer()).find(record.state().id())),"Replay changed inventory or revision");
        p.tickCount+=401;
        h.assertTrue(PanelNetwork.finalSettings(p,last).result()==CacheActions.Result.STALE,"Expired replay retained authority");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void changedFilterMenuPendantAndDimensionRejectFinalSettings(GameTestHelper h) {
        for(int kind=0;kind<5;kind++) {
            var f=fixture(h);var p=f.player();var last=packet(f,1,3);
            var ledger=CacheLedger.get(p.getServer());var handle=AccessGate.resolve(p).handle();
            if(kind==0){var r=ledger.find(handle.cacheId());var e=r.state().edit();e.extract(0,8);e.filter(0,null);
                e.filter(0,dev.scathiard.feedmepackages.item.ItemVariantKey.of(new ItemStack(Items.DIRT),p.registryAccess()));ledger.replace(handle,r.state().revision(),r.withState(e.finish()));}
            if(kind==1)TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);
            if(kind==2)CacheActions.close(p);
            if(kind==3)p.containerMenu=new net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3,7,p.getInventory(),new net.minecraft.world.SimpleContainer(27),3);
            if(kind==4)p.setServerLevel(p.getServer().getLevel(net.minecraft.world.level.Level.NETHER));
            var before=ledger.find(handle.cacheId());
            h.assertTrue(PanelNetwork.finalSettings(p,last).result()!=CacheActions.Result.OK,"Invalid context accepted: "+kind);
            h.assertTrue(before.equals(ledger.find(handle.cacheId())),"Rejected final changed stock/settings: "+kind);
            PanelNetwork.forget(p);
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void finalReceiptWireAndLateAcknowledgementsAreBounded(GameTestHelper h) {
        var f=fixture(h);var request=packet(f,8,3);
        var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),f.player().registryAccess());
        try {
            PanelPackets.FinalSettings.CODEC.encode(buf,request);
            h.assertTrue(buf.readableBytes()<PanelPackets.C2S_LIMIT && request.equals(PanelPackets.FinalSettings.CODEC.decode(buf)),"Final request wire mismatch");
            var reply=PanelNetwork.finalSettings(f.player(),request);buf.clear();PanelPackets.FinalSettingsReceipt.CODEC.encode(buf,reply);
            h.assertTrue(buf.readableBytes()<=64 && reply.equals(PanelPackets.FinalSettingsReceipt.CODEC.decode(buf)),"Receipt is not constant size");
            var tracker=new ClosingSettings();tracker.begin(request,0);
            var next=packet(new Fixture(f.player(),new PanelPackets.Snapshot(UUID.randomUUID(),UUID.randomUUID(),1,0,CacheActions.Result.OK,
                AccessGate.Status.ACTIVE,0,1,64,false,false,false,"","","",f.view().cells(),List.of())),1,4);
            tracker.begin(next,0);tracker.accept(reply);
            h.assertTrue(tracker.get(request.settings().window()).state()==ClosingSettings.State.APPLIED
                && tracker.get(next.settings().window()).state()==ClosingSettings.State.PENDING,"Late acknowledgement polluted new window");
            tracker.tick(101);h.assertTrue(tracker.get(next.settings().window()).state()==ClosingSettings.State.UNCONFIRMED,"Timeout marked successful");
            tracker.accept(new PanelPackets.FinalSettingsReceipt(next.settings().window(),next.settings().session(),1,CacheActions.Result.STALE));
            h.assertTrue(tracker.get(next.settings().window()).state()==ClosingSettings.State.REJECTED,"Refusal was lost");
        } finally{buf.release();}
        h.succeed();
    }
}
