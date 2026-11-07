package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PalletPersistenceTests {
    private static final UUID PROCESS=UUID.randomUUID();
    @GameTest(template="empty")
    public static void savedPalletChoicesCrossRealServerRestartWithoutSpendingStock(GameTestHelper h) {
        var server=h.getLevel().getServer();
        var probe=server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Probe::new,Probe::load),"fmp_pallet_choice_probe");
        boolean prior=probe.data.hasUUID("writer");
        h.assertTrue(prior || !Boolean.getBoolean("fmp.requirePriorPersistence"),"Prior pallet JVM evidence required");
        if(prior) h.assertTrue(!PROCESS.equals(probe.data.getUUID("writer")),"Pallet fixture did not restart its JVM");
        for(int i=0;i<2;i++) {
            var player=TestPlayers.real(h,prior?probe.data.getUUID("player"+i):UUID.randomUUID());
            if(prior) {
                h.assertTrue(server.getPlayerList().load(player).isPresent(),"Saved pallet player missing");
                var choice=PalletService.choice(player);
                h.assertTrue(choice!=null && choice.cache().equals(probe.data.getUUID("cache"+i))
                        && choice.encoded().equals(probe.data.getString("material"+i)),"JVM restart lost exact item/fluid choice");
                var row=CacheLedger.get(server).find(choice.cache());
                h.assertTrue(i==0?row.state().cells().getFirst().amount()==3:row.fluids().state().cells().getFirst().amount()==1000,
                        "Restoring selection spent stock");
            } else {
                TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
                player.setItemInHand(InteractionHand.MAIN_HAND,FmpRegistries.POCKET_PALLET.toStack());
                var access=AccessGate.resolve(player);var ledger=CacheLedger.get(server);String encoded;
                if(i==0) {
                    var item=new ItemStack(Items.CHEST);
                    item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Saved exact chest"));
                    var key=ItemVariantKey.of(item,player.registryAccess());var row=ledger.find(access.handle().cacheId());
                    var edit=row.state().edit();edit.filter(0,key);edit.insert(0,key,3);
                    ledger.replace(access.handle(),row.state().revision(),row.withState(edit.finish()));encoded=key.encoded();
                } else {
                    InteractionTests.seedFluid(player,0,new FluidStack(Fluids.WATER,1000));
                    encoded="fluid:"+ledger.find(access.handle().cacheId()).fluids().state().cells().getFirst().filter().encoded();
                }
                h.assertTrue(PalletService.choose(player,access.handle().cacheId(),encoded),"Cannot seed saved pallet selection");
                var players=TestPlayers.nativeSavePlayers(server);players.add(player);
                try { server.getPlayerList().saveAll(); } finally { players.remove(player); }
                probe.data.putUUID("player"+i,player.getUUID());probe.data.putUUID("cache"+i,access.handle().cacheId());
                probe.data.putString("material"+i,encoded);PalletService.forget(player);
            }
        }
        if(!prior) { probe.data.putUUID("writer",PROCESS);probe.setDirty(); }
        FeedMePackages.LOGGER.info("FMP_PALLET_PERSISTENCE priorJVM={} exactItem=true fluid=true nativePlayerFiles=true",prior);h.succeed();
    }
    private static final class Probe extends SavedData {
        private CompoundTag data=new CompoundTag();
        static Probe load(CompoundTag tag,HolderLookup.Provider registries){var p=new Probe();p.data=tag.copy();return p;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){return data.copy();}
    }
}
