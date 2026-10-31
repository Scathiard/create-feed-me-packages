package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;
import java.util.UUID;

/** Characterizes native save boundaries, not a transactional durability guarantee.
 * Opt-in, fresh isolated worlds only; abrupt writer intentionally exits with code 74. */
@net.neoforged.neoforge.gametest.GameTestHolder(FeedMePackages.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class SaveBoundaryTests {
    private static final UUID PROCESS=UUID.randomUUID();
    @GameTest(template="empty",templateNamespace="fmp_save_boundary",timeoutTicks=400)
    public static void independentCheckpoints(GameTestHelper h) {
        String[] mode=System.getProperty("fmp.saveBoundary", "").split(":");
        h.assertTrue(mode.length==3,"Explicit isolated save-boundary scenario required");
        var server=h.getLevel().getServer();var ledger=CacheLedger.get(server);
        var data=server.overworld().getDataStorage();
        var probe=data.computeIfAbsent(new SavedData.Factory<>(Probe::new,Probe::load),"fmp_save_boundary_probe");
        if(mode[0].equals("write")) {
            h.assertTrue(probe.tag.isEmpty(),"Refusing to overwrite an existing checkpoint");
            var player=TestPlayers.real(h,UUID.randomUUID());
            TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            ConsumptionTests.seed(player,0,new ItemStack(Items.STONE),100);
            var handle=AccessGate.resolve(player).handle();
            probe.tag.putUUID("player",player.getUUID());probe.tag.putUUID("cache",handle.cacheId());
            probe.tag.putUUID("writer",PROCESS);probe.tag.putString("scenario",mode[1]);probe.setDirty();
            // Register the real ServerPlayer for the vanilla full-save loop; only its network is inert.
            TestPlayers.nativeSavePlayers(server).add(player);
            server.saveEverything(true,true,true);IOUtilities.waitUntilIOWorkerComplete();
            var view=CacheActions.open(player);
            var result=CacheActions.execute(player,new CacheActions.Intent(view.session(),view.record().state().revision(),
                    CacheActions.Action.TAKE_INVENTORY,0,7,-1,""));
            h.assertTrue(result==CacheActions.Result.OK && player.getInventory().countItem(Items.STONE)==7
                    &&ledger.find(handle.cacheId()).state().cells().getFirst().amount()==93,"Native transfer fixture failed");
            switch(mode[1]) {
                case "normal" -> { h.succeed(); return; } // Normal GameTest stop uses vanilla save.
                case "unsaved" -> { }
                case "ledger-only" -> data.save();
                case "player-only" -> FoundationTests.saveNativePlayer(player);
                default -> throw new AssertionError("Unexpected boundary");
            }
            IOUtilities.waitUntilIOWorkerComplete();
            // No shutdown hooks and no normal stop save. This is process death, not physical power loss.
            System.out.println("FMP_SAVE_BOUNDARY_CUT scenario="+mode[1]+" batch="+mode[2]+" expectedExit=74 pid="+ProcessHandle.current().pid());
            System.out.flush();System.err.flush();Runtime.getRuntime().halt(74);
            throw new AssertionError("Halt unexpectedly returned");
        }
        h.assertTrue(mode[0].equals("read")&&!probe.tag.isEmpty()&&!PROCESS.equals(probe.tag.getUUID("writer"))
                &&mode[1].equals(probe.tag.getString("scenario")),"Missing matching independent writer JVM");
        var player=TestPlayers.real(h,probe.tag.getUUID("player"));
        h.assertTrue(server.getPlayerList().load(player).isPresent(),"Native player file missing");
        var access=AccessGate.resolve(player);
        h.assertTrue(ledger.problem().isEmpty()&&access.active()&&access.handle().cacheId().equals(probe.tag.getUUID("cache")),"Cache identity did not survive");
        int cache=ledger.find(access.handle().cacheId()).state().cells().getFirst().amount();
        int backpack=player.getInventory().countItem(Items.STONE);
        int expectedCache=mode[1].equals("normal")||mode[1].equals("ledger-only")?93:100;
        int expectedBackpack=mode[1].equals("normal")||mode[1].equals("player-only")?7:0;
        h.assertTrue(cache==expectedCache&&backpack==expectedBackpack,"Unexpected checkpoint pair: cache="+cache+" backpack="+backpack);
        FeedMePackages.LOGGER.info("FMP_SAVE_BOUNDARY_OBSERVED scenario={} cache={} backpack={} total={} originalTotal=100 independentJVM=true",
                mode[1],cache,backpack,cache+backpack);
        h.succeed();
    }
    private static final class Probe extends SavedData {
        private CompoundTag tag=new CompoundTag();
        static Probe load(CompoundTag tag,HolderLookup.Provider registry){var p=new Probe();p.tag=tag.copy();return p;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registry){return this.tag.copy();}
    }
}
