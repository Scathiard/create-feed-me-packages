package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LedgerIsolationTests {
    @GameTest(template="empty")
    public static void brokenCacheDoesNotFreezeIndependentStock(GameTestHelper h) {
        var registry=h.getLevel().registryAccess(); var ledger=new CacheLedger();
        var good=seed(ledger,h,37); var bad=seed(ledger,h,19);
        var raw=ledger.save(new CompoundTag(),registry); var damaged=row(raw,bad);
        damaged.getList("cells",Tag.TAG_COMPOUND).getCompound(0).putString("filter","{count:1,id:\"missing_addon:material\"}");
        var original=damaged.copy(); var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null,"One unreadable filter froze independent stock");
        h.assertTrue(loaded.find(bad)==null && !loaded.ordinaryEntry(bad,null),"Bad cache became an empty usable replacement");
        var before=loaded.find(good); var edit=before.state().edit();edit.extract(0,1);
        loaded.replace(new CacheHandle(good,UUID.randomUUID(),null),before.state().revision(),before.withState(edit.finish()));
        var saved=loaded.save(new CompoundTag(),registry);
        h.assertTrue(row(saved,bad).equals(original),"Saving healthy work rewrote quarantined raw data");
        var again=CacheLedger.load(saved,registry);
        h.assertTrue(again.problem().isEmpty() && again.find(good).state().cells().getFirst().amount()==36
                && row(again.save(new CompoundTag(),registry),bad).equals(original),"Second load lost isolation or healthy changes");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void duplicateIdentityNeverPicksAWinningRow(GameTestHelper h) {
        var ledger=new CacheLedger();var good=seed(ledger,h,37);var bad=seed(ledger,h,19);
        var registry=h.getLevel().registryAccess();var raw=ledger.save(new CompoundTag(),registry);
        raw.getList("caches",Tag.TAG_COMPOUND).add(row(raw,bad).copy());
        var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null && loaded.find(bad)==null,
                "Duplicate cache authority escaped quarantine or froze unrelated stock");
        h.assertTrue(loaded.save(new CompoundTag(),registry).getList("caches",Tag.TAG_COMPOUND).size()==3,
                "Duplicate raw row silently discarded");h.succeed();
    }
    @GameTest(template="empty")
    public static void invalidHistoricalAddressStillOpensPanel(GameTestHelper h) {
        for (String raw : List.of("退".repeat(129), "x".repeat(50000), "bad" + (char)0xd800)) {
            var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());var handle=AccessGate.resolve(player).handle();
            var ledger=CacheLedger.get(player.getServer());ledger.setReturnAddress(handle.cacheId(),raw);
            var window=UUID.randomUUID();var reply=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true));
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),player.registryAccess());
            try {
                PanelPackets.Snapshot.CODEC.encode(buffer,reply);
                h.assertTrue(buffer.readableBytes()<8192,"Raw invalid address leaked into panel packet");
                var decoded=PanelPackets.Snapshot.CODEC.decode(buffer);
                h.assertTrue(decoded.status()==AccessGate.Status.ACTIVE && decoded.returnAddressPreserved() && decoded.returnAddress().isEmpty()
                        && raw.equals(ledger.returnAddress(handle.cacheId())),"Invalid address disabled inventory, lost its notice, or changed the original");
                var saved=CacheLedger.load(ledger.save(new CompoundTag(),player.registryAccess()),player.registryAccess());
                h.assertTrue(raw.equals(saved.returnAddress(handle.cacheId())),"Original invalid text changed during save/load");
                var rejected=PanelNetwork.command(player,new PanelPackets.Command(window,1,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(
                        reply.session(),reply.revision(),dev.scathiard.feedmepackages.interaction.CacheActions.Action.SET_RETURN_ADDRESS,-1,-1,-1,"x".repeat(129)),false,"",0));
                h.assertTrue(rejected.result()!=dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK && raw.equals(ledger.returnAddress(handle.cacheId())),"Rejected edit erased the old address");
                var fixed=PanelNetwork.command(player,new PanelPackets.Command(window,2,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(
                        reply.session(),reply.revision(),dev.scathiard.feedmepackages.interaction.CacheActions.Action.SET_RETURN_ADDRESS,-1,-1,-1,"退货站"),false,"",0));
                h.assertTrue(fixed.result()==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK && !fixed.returnAddressPreserved()
                        && "退货站".equals(ledger.returnAddress(handle.cacheId())),"Explicit valid edit did not repair the address");
            } finally {buffer.release();PanelNetwork.forget(player);}
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void redirectAndRetirementRecoverAsOneGroup(GameTestHelper h) {
        var ledger=new CacheLedger();var good=seed(ledger,h,37);var target=seed(ledger,h,19);
        var registry=h.getLevel().registryAccess();var key=ItemVariantKey.of(new ItemStack(Items.STONE),registry);
        var raw=ledger.save(new CompoundTag(),registry);UUID owner=UUID.randomUUID(),source=UUID.randomUUID(),receipt=UUID.randomUUID();
        row(raw,target).putUUID("owner",owner);
        var mapping=new CompoundTag();mapping.putString("variant",key.encoded());mapping.putLong("before",1);
        mapping.putLong("after",ledger.find(target).state().cells().getFirst().filterRevision());
        var variants=new ListTag();variants.add(mapping);
        var route=new CompoundTag();route.putUUID("source",source);route.putUUID("target",target);route.put("variants",variants);
        raw.getList("redirects",Tag.TAG_COMPOUND).add(route);
        var retired=retirement(owner,target,receipt);raw.getList("private_retirements",Tag.TAG_COMPOUND).add(retired);
        mapping.putString("variant","{count:1,id:\"missing_addon:material\"}");
        var brokenRoute=route.copy();var brokenRow=row(raw,target).copy();var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null && loaded.find(target)==null
                && !loaded.problem(source).isEmpty() && loaded.ownerIsolated(owner),"Redirect owner boundary was split");
        rejects(h,()->loaded.ordinaryForManufacturing(target));rejects(h,()->loaded.beginRetirement(owner));
        h.assertTrue(loaded.parcelTarget(source,key,1)==null,"Quarantined redirect still accepts signed incoming goods");
        var saved=loaded.save(new CompoundTag(),registry);
        h.assertTrue(row(saved,target).equals(brokenRow) && saved.getList("redirects",Tag.TAG_COMPOUND).getCompound(0).equals(brokenRoute)
                && saved.getList("private_retirements",Tag.TAG_COMPOUND).getCompound(0).equals(retired),"Associated raw rows changed");
        saved.getList("redirects",Tag.TAG_COMPOUND).getCompound(0).getList("variants",Tag.TAG_COMPOUND).getCompound(0).putString("variant",key.encoded());
        var restored=CacheLedger.load(saved,registry);
        h.assertTrue(restored.problem(target).isEmpty() && restored.ordinaryEntry(target,receipt) && !restored.ordinaryEntry(target,null)
                && restored.parcelTarget(source,key,1).cacheId().equals(target)
                && restored.find(target).state().cells().getFirst().amount()==19,"Restoration changed stock, redirect, or bearer authority");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void unreadableHeldCarrierRetainsCargoAndUncertainDebt(GameTestHelper h) {
        var ledger=new CacheLedger();var good=seed(ledger,h,37);var bad=seed(ledger,h,19);var registry=h.getLevel().registryAccess();
        var before=ledger.find(bad);var key=before.state().cells().getFirst().filter();
        var hold=new ReturnEscrow(UUID.randomUUID(),new CacheHandle(bad,UUID.randomUUID(),null),before.state().revision(),0,key,7,
                new ItemStack(Items.PAPER),7,-1,"phantom","warehouse",false);
        ledger.beginReturn(hold,before,registry);var raw=ledger.save(new CompoundTag(),registry);
        var held=raw.getList("return_handoffs",Tag.TAG_COMPOUND).getCompound(0);var carrier=held.getCompound("carrier").copy();
        held.getCompound("carrier").putString("id","missing_addon:carrier");var originalHold=held.copy();
        var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null && loaded.find(bad)==null && loaded.returnEscrow(bad)==null,
                "Unreadable held carrier exposed cargo or blocked independent stock");
        rejects(h,()->loaded.cancelRejectedReturn(hold));rejects(h,()->loaded.setReturnAddress(bad,"different"));
        var saved=loaded.save(new CompoundTag(),registry);
        h.assertTrue(saved.getList("return_handoffs",Tag.TAG_COMPOUND).getCompound(0).equals(originalHold)
                && row(saved,bad).equals(row(raw,bad)),"Failed handoff was refunded, removed, or rewritten");
        saved.getList("return_handoffs",Tag.TAG_COMPOUND).getCompound(0).put("carrier",carrier);
        var restored=CacheLedger.load(saved,registry);
        h.assertTrue(restored.problem(bad).isEmpty() && restored.returnEscrow(bad).id().equals(hold.id())
                && !restored.returnEscrow(bad).rejected() && restored.find(bad).state().cells().getFirst().amount()==19,
                "Dependency restoration guessed delivery or reissued uncertain cargo");h.succeed();
    }
    @GameTest(template="empty")
    public static void sharedReceiptQuarantinesBothClaims(GameTestHelper h) {
        var ledger=new CacheLedger();var good=seed(ledger,h,37);var one=seed(ledger,h,19);var two=seed(ledger,h,23);
        var registry=h.getLevel().registryAccess();var raw=ledger.save(new CompoundTag(),registry);var receipt=UUID.randomUUID();
        for(UUID id:List.of(one,two)) {var owner=UUID.randomUUID();row(raw,id).putUUID("owner",owner);
            raw.getList("private_retirements",Tag.TAG_COMPOUND).add(retirement(owner,id,receipt));}
        var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null && loaded.find(one)==null && loaded.find(two)==null,
                "Conflicting receipt selected one winning owner or froze independent stock");
        h.assertTrue(loaded.save(new CompoundTag(),registry).getList("private_retirements",Tag.TAG_COMPOUND).equals(raw.getList("private_retirements",Tag.TAG_COMPOUND)),
                "Duplicate authority raw evidence was discarded");h.succeed();
    }
    @GameTest(template="empty")
    public static void uncertainGlobalBoundaryStillPreservesWholeLedger(GameTestHelper h) {
        var ledger=new CacheLedger();var id=seed(ledger,h,19);var registry=h.getLevel().registryAccess();var original=ledger.save(new CompoundTag(),registry);
        for(int fault=0;fault<5;fault++) {
            var raw=original.copy();
            switch(fault) {
                case 0 -> raw.putInt("schema",Integer.MAX_VALUE);
                case 1 -> raw.putByteArray("integrity_key",new byte[3]);
                case 2 -> row(raw,id).remove("id");
                case 3 -> raw.put("redirects",StringTag.valueOf("not a table"));
                default -> {raw.putInt("schema",31003);row(raw,id).remove("crafting_enabled");
                    row(raw,id).getList("cells",Tag.TAG_COMPOUND).getCompound(0).putString("filter","broken");}
            }
            var loaded=CacheLedger.load(raw,registry);
            h.assertTrue(!loaded.problem().isEmpty() && loaded.save(new CompoundTag(),registry).equals(raw),"Ambiguous global/legacy boundary guessed or erased data: "+fault);
            rejects(h,loaded::createOrdinary);
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void emptyMissingFilterAndUnknownFieldsAreStillPreserved(GameTestHelper h) {
        var ledger=new CacheLedger();var good=seed(ledger,h,37);var bad=seed(ledger,h,19);var registry=h.getLevel().registryAccess();
        var raw=ledger.save(new CompoundTag(),registry);var damaged=row(raw,bad);var cell=damaged.getList("cells",Tag.TAG_COMPOUND).getCompound(0);
        cell.putInt("amount",0);cell.putString("filter","{count:1,id:\"missing_addon:material\"}");
        damaged.putString("unknown_preserved_field","do not discard");var original=damaged.copy();var loaded=CacheLedger.load(raw,registry);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(good)!=null && loaded.find(bad)==null
                && row(loaded.save(new CompoundTag(),registry),bad).equals(original),"Empty filter or unknown metadata was reset");h.succeed();
    }
    private static CompoundTag retirement(UUID owner,UUID cache,UUID receipt) {
        var row=new CompoundTag();row.putUUID("owner",owner);row.putUUID("cache",cache);row.putUUID("receipt",receipt);row.putBoolean("completed",true);return row;
    }
    private static void rejects(GameTestHelper h,Runnable action) {
        boolean rejected=false;try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected,"Operation bypassed preserved-data protection");
    }
    private static final UUID PROCESS=UUID.randomUUID();
    @GameTest(template="empty",templateNamespace="fmp_ledger_isolation")
    public static void optionalItemAcrossFiveProcesses(GameTestHelper h) {
        String phase=System.getProperty("fmp.ledgerLifecycle","");
        h.assertTrue(List.of("write","absent","absent-again","restore","verify").contains(phase),"Dedicated lifecycle required");
        var server=h.getLevel().getServer();var registry=server.registryAccess();
        var storage=server.overworld().getDataStorage();
        var probe=storage.computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(Probe::new,Probe::load),"fmp_ledger_isolation_probe");
        var ledger=CacheLedger.get(server);boolean installed=net.neoforged.fml.ModList.get().isLoaded("create_mobile_packages");
        h.assertTrue(installed==!phase.startsWith("absent"),"Wrong optional-mod presence for this lifecycle phase");
        net.minecraft.server.level.ServerPlayer good,bad;
        if(phase.equals("write")) {
            h.assertTrue(probe.data.isEmpty(),"Existing evidence must not be overwritten");
            good=TestPlayers.real(h,UUID.randomUUID());bad=TestPlayers.real(h,UUID.randomUUID());
            for(var player:List.of(good,bad))TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            var healthy=AccessGate.resolve(good).handle();var affected=AccessGate.resolve(bad).handle();
            ConsumptionTests.seed(good,0,new ItemStack(Items.STONE),37);
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("create_mobile_packages:robo_bee"));
            h.assertTrue(item!=Items.AIR,"Real optional carrier is missing");
            var sample=new ItemStack(item);sample.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("preserve exact optional item"));
            var key=ItemVariantKey.of(sample,registry);var before=ledger.find(affected.cacheId());var edit=before.state().edit();
            edit.filter(0,key);edit.thresholds(0,2,-1);edit.insert(0,key,1);edit.request(0,UUID.randomUUID(),bad.getUUID(),1,true);
            ledger.replace(affected,before.state().revision(),before.withState(edit.finish()));
            probe.data.putUUID("good_player",good.getUUID());probe.data.putUUID("bad_player",bad.getUUID());
            probe.data.putUUID("good_cache",healthy.cacheId());probe.data.putUUID("bad_cache",affected.cacheId());
            probe.data.put("original_bad",row(ledger.save(new CompoundTag(),registry),affected.cacheId()).copy());
        } else {
            String previous=switch(phase){case "absent"->"write";case "absent-again"->"absent";case "restore"->"absent-again";default->"restore";};
            h.assertTrue(!probe.data.isEmpty() && !PROCESS.equals(probe.data.getUUID("process")) && previous.equals(probe.data.getString("phase")),"Missing previous independent JVM checkpoint");
            good=TestPlayers.real(h,probe.data.getUUID("good_player"));bad=TestPlayers.real(h,probe.data.getUUID("bad_player"));
            for(var player:List.of(good,bad))h.assertTrue(server.getPlayerList().load(player).isPresent(),"Missing native player file");
            UUID goodId=probe.data.getUUID("good_cache"),badId=probe.data.getUUID("bad_cache");
            h.assertTrue(ledger.problem().isEmpty() && AccessGate.resolve(good).active(),"Optional item blocked independent player's cache");
            h.assertTrue(row(ledger.save(new CompoundTag(),registry),badId).equals(probe.data.getCompound("original_bad")),"Native save/reload changed optional stock, components or uncertain orders");
            h.assertTrue(TestPlayers.necklace(bad).getStackInSlot(0).get(FmpRegistries.IDENTITY.get()).equals(badId),"Unavailable pendant was assigned another cache");
            if(phase.startsWith("absent")) {
                h.assertTrue(AccessGate.resolve(bad).status()==AccessGate.Status.STORAGE_LOCKED && ledger.find(badId)==null,"Missing optional item was exposed as ordinary stock");
                if(phase.equals("absent")) {
                    var view=dev.scathiard.feedmepackages.interaction.CacheActions.snapshot(good);
                    var result=dev.scathiard.feedmepackages.interaction.CacheActions.execute(good,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(
                            view.session(),view.record().state().revision(),dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_INVENTORY,0,1,-1,""));
                    h.assertTrue(result==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK,"Independent native extraction failed");
                }
            } else {
                h.assertTrue(AccessGate.resolve(bad).active() && ledger.find(badId).state().cells().getFirst().amount()==1
                        && ledger.find(badId).state().pending(0)==1,"Reinstalled dependency changed identity, stock or unresolved order");
                var restored=ledger.find(badId).state().cells().getFirst().filter().stack(registry,1);
                h.assertTrue(restored.getHoverName().getString().equals("preserve exact optional item"),"Native optional item component lost");
            }
            h.assertTrue(ledger.find(goodId).state().cells().getFirst().amount()==36 && good.getInventory().countItem(Items.STONE)==1,
                    "Independent player's persisted extraction was lost or duplicated");
        }
        FoundationTests.saveNativePlayer(good);FoundationTests.saveNativePlayer(bad);
        probe.data.putString("phase",phase);probe.data.putUUID("process",PROCESS);probe.setDirty();ledger.setDirty();
        FeedMePackages.LOGGER.info("FMP_LEDGER_ISOLATION_PASSED phase={} actualPlayerFiles=true rawOptionalStockPreserved=true",phase);
        h.succeed();
    }
    private static final class Probe extends net.minecraft.world.level.saveddata.SavedData {
        private CompoundTag data=new CompoundTag();
        static Probe load(CompoundTag tag,net.minecraft.core.HolderLookup.Provider registry){var p=new Probe();p.data=tag.copy();return p;}
        @Override public CompoundTag save(CompoundTag tag,net.minecraft.core.HolderLookup.Provider registry){return data.copy();}
    }
    /** Root tables are maps encoded as lists; compare every row including duplicates, ignoring only table order. */
    /** Maps have no save ordering; cells, package contents and component payloads remain order-sensitive.
     * Quarantined rows are additionally compared byte-for-byte as NBT in the isolation tests above. */
    static boolean sameRows(CompoundTag one,CompoundTag two) {
        var a=one.copy();var b=two.copy();
        for(CompoundTag tag:List.of(a,b)) {
            for(var entry:tag.getList("caches",Tag.TAG_COMPOUND)) {
                var row=(CompoundTag)entry;sortMapRows(row,"orders");sortMapRows(row,"residuals");
                if(row.contains("astra_fluids",Tag.TAG_COMPOUND)) {
                    var fluids=row.getCompound("astra_fluids");sortMapRows(fluids,"orders");sortMapRows(fluids,"residuals");
                }
            }
            for(var entry:tag.getList("redirects",Tag.TAG_COMPOUND))sortMapRows((CompoundTag)entry,"variants");
            for(String key:List.of("caches","redirects","returnAddresses","private_retirements","return_handoffs","preferences"))sortMapRows(tag,key);
        }
        if(!a.equals(b))FeedMePackages.LOGGER.error("FMP_LEDGER_COMPARE {}",firstDifference(a,b,"root"));
        return a.equals(b);
    }
    private static void sortMapRows(CompoundTag tag,String key) {
        if(!tag.contains(key,Tag.TAG_LIST))return;
        var entries=new ArrayList<Tag>();((ListTag)tag.get(key)).forEach(entries::add);
        entries.sort(java.util.Comparator.comparing(Tag::toString));var sorted=new ListTag();entries.forEach(sorted::add);tag.put(key,sorted);
    }
    private static String firstDifference(Tag a,Tag b,String path) {
        if(Objects.equals(a,b))return "equal";
        if(a instanceof CompoundTag x && b instanceof CompoundTag y){var keys=new java.util.TreeSet<>(x.getAllKeys());keys.addAll(y.getAllKeys());for(var key:keys)if(!Objects.equals(x.get(key),y.get(key)))return firstDifference(x.get(key),y.get(key),path+"."+key);}
        if(a instanceof ListTag x && b instanceof ListTag y && x.size()==y.size()){for(int i=0;i<x.size();i++)if(!x.get(i).equals(y.get(i)))return firstDifference(x.get(i),y.get(i),path+"["+i+"]");}
        return path+": "+String.valueOf(a).substring(0,Math.min(200,String.valueOf(a).length()))+" != "+String.valueOf(b).substring(0,Math.min(200,String.valueOf(b).length()));
    }
    static UUID seed(CacheLedger ledger,GameTestHelper h,int amount) {
        UUID id=ledger.createOrdinary();var before=ledger.find(id);var edit=before.state().edit();
        var key=ItemVariantKey.of(new ItemStack(Items.STONE),h.getLevel().registryAccess());edit.filter(0,key);edit.insert(0,key,amount);
        ledger.replace(new CacheHandle(id,UUID.randomUUID(),null),before.state().revision(),before.withState(edit.finish()));return id;
    }
    static CompoundTag row(CompoundTag tag,UUID id) {
        for(var value:tag.getList("caches",Tag.TAG_COMPOUND))if(((CompoundTag)value).hasUUID("id") && ((CompoundTag)value).getUUID("id").equals(id))return (CompoundTag)value;
        throw new AssertionError("Missing original row "+id);
    }
}
