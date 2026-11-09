package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.network.*;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
public final class FluidBatchTests {
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("fluidlogistics")) return List.of();
        var tests=new ArrayList<>(List.of(test("settings",FluidBatchTests::settings),test("supply",FluidBatchTests::supply),
                test("transport_shrink",FluidBatchTests::transportShrink)));
        if(System.getProperty("fmp.legacyFluidLedger")!=null)tests.add(test("actual_legacy_save",FluidBatchTests::actualLegacySave));
        return tests;
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("fluid-batches","fmp_fluid_batches_"+name,FeedMePackages.MOD_ID+":empty",350,0,true,body);
    }
    private static SupplyTests.FluidSupply fixture(GameTestHelper h,int amount,CellSettings settings) {
        h.assertTrue(FluidPackages.status()==FluidPackages.Status.READY,"Native fluid API unavailable");
        var f=SupplyTests.fluidSupply(h,new FluidStack(Fluids.WATER,1),false);
        var before=f.record();var edit=before.fluids().state().edit();
        h.assertTrue(edit.insert(0,f.key(),amount)==amount,"Fluid fixture did not fit");edit.settings(0,settings);
        f.ledger().replace(f.handle(),before.state().revision(),before.withFluids(before.fluids().withState(edit.finish())));
        return f;
    }
    private static void settings(GameTestHelper h) {
        var f=fixture(h,1250,new CellSettings(3000,9000,1,-1));var p=f.player();
        var view=PanelNetwork.query(p,new PanelPackets.Query(UUID.randomUUID(),0,true));
        var controls=new CellSettings(3000,12000,8000,-1,8000);
        var change=new CacheActions.SettingsChange(0,view.cells().getFirst().filterRevision(),controls,true);
        var reply=PanelNetwork.settings(p,new PanelPackets.SettingsBatch(view.window(),view.session(),1,List.of(change)));
        h.assertTrue(reply.result()==CacheActions.Result.OK && reply.cells().getFirst().batch()==8000
                && reply.cells().getFirst().returnBatch()==8000 && reply.cells().getFirst().amount()==1250
                && reply.fluidPackageCapacity()==FluidPackages.capacity(),"Fluid batch edit or authoritative snapshot lost data");
        var wire=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),p.registryAccess());
        try {PanelPackets.Snapshot.CODEC.encode(wire,reply);h.assertTrue(reply.equals(PanelPackets.Snapshot.CODEC.decode(wire)),"Fluid limits lost on wire");}
        finally {wire.release();}
        var before=f.record();
        for(var bad:List.of(new CellSettings(3000,12000,1001,-1,8000),new CellSettings(3000,-1,11000,-1,8000),
                new CellSettings(3000,3000,8000,-1,8000),new CellSettings(3001,12000,8000,-1,8000))) {
            var invalid=new CacheActions.SettingsChange(0,change.filterRevision(),bad,true);
            h.assertTrue(CacheActions.settings(p,view.session(),List.of(invalid))==CacheActions.Result.INVALID_REQUEST
                    && f.record().equals(before),"Invalid batch edit changed the ledger");
        }
        var action=new CacheActions.Intent(view.session(),before.state().revision(),CacheActions.Action.THRESHOLDS,0,1,2,"");
        h.assertTrue(CacheActions.execute(p,action)==CacheActions.Result.OK
                && f.record().fluids().state().cells().getFirst().settings().equals(new CellSettings(8000,16000,8000,-1,8000)),
                "Legacy threshold command erased the new batch settings");
        before=f.record();var edit=before.fluids().state().edit();
        // This existing promise was sent before the player increased the minimum to eight buckets.
        edit.settings(0,new CellSettings(8000,16000,1,-1,1));edit.request(0,UUID.randomUUID(),p.getUUID(),6000,true);
        edit.settings(0,new CellSettings(8000,16000,8000,-1,8000));
        f.ledger().replace(f.handle(),before.state().revision(),before.withFluids(before.fluids().withState(edit.finish())));
        var saved=f.ledger().save(new CompoundTag(),p.registryAccess());
        var reloaded=CacheLedger.load(saved,p.registryAccess());
        h.assertTrue(reloaded.problem(f.handle().cacheId()).isEmpty() && reloaded.find(f.handle().cacheId()).equals(f.record()),
                "Batch settings, exact liquid or pending requests changed on reload");
        var old=saved.copy();var oldFluid=row(old,f.handle().cacheId()).getCompound("astra_fluids");oldFluid.putInt("version",2);
        for(var entry:oldFluid.getCompound("cells").getList("entries",Tag.TAG_COMPOUND)) {
            var c=((CompoundTag)entry).getCompound("volume_controls");c.remove("batch");c.remove("return_batch");
        }
        var upgraded=CacheLedger.load(old,p.registryAccess());var oldCell=upgraded.find(f.handle().cacheId()).fluids().state().cells().getFirst();
        h.assertTrue(upgraded.problem(f.handle().cacheId()).isEmpty() && oldCell.amount()==1250 && oldCell.settings().batch()==1
                && oldCell.settings().returnBatch()==1 && oldCell.settings().lower()==8000 && oldCell.settings().upper()==16000
                && upgraded.find(f.handle().cacheId()).fluids().state().pending(0)==6000,"Version 2 upgrade changed legacy stock, defaults or pending");
        var damaged=saved.copy();row(damaged,f.handle().cacheId()).getCompound("astra_fluids").getCompound("cells")
                .getList("entries",Tag.TAG_COMPOUND).getCompound(0).getCompound("volume_controls").putString("batch","bad");
        var refused=CacheLedger.load(damaged,p.registryAccess());
        h.assertTrue(!refused.problem(f.handle().cacheId()).isEmpty()
                && LedgerIsolationTests.sameRows(refused.save(new CompoundTag(),p.registryAccess()),damaged),"Damaged new fields were cleared instead of retained");
        FeedMePackages.LOGGER.info("FMP_FLUID_BATCH_SETTINGS_PASSED mB=1250 pending=6000 legacyV2=true wire=true invalidPreserved=true");
        h.succeed();
    }
    private static CompoundTag row(CompoundTag saved,UUID id) {
        for(var row:saved.getList("caches",Tag.TAG_COMPOUND)) if(((CompoundTag)row).getUUID("id").equals(id))return (CompoundTag)row;
        throw new AssertionError("Missing saved cache");
    }
    /** Explicit read-only input: never load or rewrite a user's world as the test server world. */
    private static void actualLegacySave(GameTestHelper h) {
        try {
            var path=java.nio.file.Path.of(System.getProperty("fmp.legacyFluidLedger"));
            h.assertTrue(java.nio.file.Files.size(path)<4*1024*1024,"Legacy fixture exceeds bounded input size");
            var original=java.nio.file.Files.readAllBytes(path);
            var old=NbtIo.readCompressed(path,NbtAccounter.create(4*1024*1024)).getCompound("data");
            var registry=h.getLevel().registryAccess();var loaded=CacheLedger.load(old,registry);
            int checked=0;long total=0;
            for(var entry:old.getList("caches",Tag.TAG_COMPOUND)) {
                var row=(CompoundTag)entry;var fluid=row.getCompound("astra_fluids");var id=row.getUUID("id");
                h.assertTrue(fluid.getInt("version")==2 && loaded.problem(id).isEmpty(),"Real old save was not a readable version 2 fluid record");
                var record=loaded.find(id);
                for(var raw:fluid.getCompound("cells").getList("entries",Tag.TAG_COMPOUND)) {
                    var cell=(CompoundTag)raw;var actual=record.fluids().state().cells().get(cell.getInt("slot"));
                    var controls=cell.getCompound("volume_controls");
                    h.assertTrue(actual.amount()==cell.getLong("amount") && actual.settings().lower()==controls.getInt("lower")
                            && actual.settings().upper()==controls.getInt("upper") && actual.settings().batch()==1 && actual.settings().returnBatch()==1
                            && (actual.filter()==null ? !cell.contains("filter") : actual.filter().encoded().equals(cell.getString("filter"))),"Actual old fluid content or controls changed");
                    checked++;total+=actual.amount();
                }
                h.assertTrue(record.fluids().state().orders().size()==fluid.getList("orders",Tag.TAG_COMPOUND).size(),"Old in-flight records changed");
            }
            h.assertTrue(checked>0,"Legacy fixture contained no actual fluid cells");
            var roundtrip=CacheLedger.load(loaded.save(new CompoundTag(),registry),registry);
            for(var entry:old.getList("caches",Tag.TAG_COMPOUND)) {
                var id=((CompoundTag)entry).getUUID("id");h.assertTrue(loaded.find(id).equals(roundtrip.find(id)),"Upgraded real record did not survive a second load");
            }
            h.assertTrue(Arrays.equals(original,java.nio.file.Files.readAllBytes(path)),"Read-only legacy evidence was modified");
            FeedMePackages.LOGGER.info("FMP_FLUID_BATCH_REAL_LEGACY_PASSED cells={} liquid={} sourceUntouched=true",checked,total);h.succeed();
        } catch(java.io.IOException failure) {throw new IllegalStateException("Cannot read the explicit legacy fixture",failure);}
    }
    private static void supply(GameTestHelper h) {
        var f=fixture(h,7999,new CellSettings(8000,16000,8000,-1,8000));
        var pos=new BlockPos(2,1,2);SupplyTests.placeFluidFactory(h,pos,1);int[] phase={0};
        h.onEachTick(()->{
            if(h.getTick()<5)return;
            if(phase[0]==0) {
                SupplyTests.fillSource(h,f,pos,7999);SupplyTests.connect(h,pos,f.network());SupplyService.tick(f.player());
                h.assertTrue(f.record().fluids().state().pending(0)==0 && SupplyTests.source(h,pos).getFluidInTank(0).getAmount()==7999
                        && SupplyTests.takeOutput(h,pos).isEmpty(),"Short source dispatched a batch below its minimum");
                SupplyTests.fillSource(h,f,pos,1);phase[0]=1;return;
            }
            if(phase[0]==1) {
                if(h.getTick()<35)return;SupplyService.tick(f.player());
                if(f.record().fluids().state().pending(0)==0)return;
                h.assertTrue(f.record().fluids().state().pending(0)==8000 && f.record().fluids().state().cells().getFirst().amount()==7999
                        && SupplyTests.source(h,pos).getFluidInTank(0).isEmpty(),"One mB shortage did not request exactly the 8-bucket batch");phase[0]=2;
            }
            var box=SupplyTests.takeOutput(h,pos);if(box.isEmpty())return;
            h.assertTrue(FluidPackages.read(box).getAmount()==8000 && ReceiveService.receive(f.player(),box) && box.isEmpty(),"Native fluid batch did not arrive intact");
            var state=f.record().fluids().state();
            h.assertTrue(state.cells().getFirst().amount()==15999 && state.pending(0)==0 && state.requestable(0)==0
                    && state.cells().getFirst().surplus()==0,"Refill created an immediate return or changed exact mB");
            FeedMePackages.LOGGER.info("FMP_FLUID_BATCH_SUPPLY_PASSED shortage=1 minimum=8000 shortSourceWaited=true final=15999");h.succeed();
        });
    }
    private static void transportShrink(GameTestHelper h) {
        h.assertTrue(FluidPackages.capacity()==10000,"This fixture needs the pinned 10-bucket native capacity");
        var f=fixture(h,0,new CellSettings(1000,-1,11000,-1,11000));
        var pos=new BlockPos(2,1,2);SupplyTests.placeFluidFactory(h,pos,2);
        h.runAfterDelay(5,()->{
            SupplyTests.fillSource(h,f,pos,16000);SupplyTests.connect(h,pos,f.network());SupplyService.tick(f.player());
            h.assertTrue(f.record().fluids().state().pending(0)==0 && SupplyTests.source(h,pos).getFluidInTank(0).getAmount()==16000,
                    "Over-capacity retained batch dispatched after transport configuration shrank");
            var view=PanelNetwork.query(f.player(),new PanelPackets.Query(UUID.randomUUID(),0,true));
            var c=new CacheActions.SettingsChange(0,view.cells().getFirst().filterRevision(),new CellSettings(1000,-1,8000,-1,11000),true);
            h.assertTrue(CacheActions.settings(f.player(),view.session(),List.of(c))==CacheActions.Result.OK,"Unchanged oversized return setting prevented reducing refill minimum");
            h.succeed();
        });
    }
}
