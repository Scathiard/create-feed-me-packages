package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.material.Fluids;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.*;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.service.PrivateCacheMerge;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.logistics.*;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.List;
import java.util.Map;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FoundationTests {
    /** The host seeds a real TOML before starting this fresh isolated server process. */
    @GameTest(template = "empty", templateNamespace = "fmp_config_startup")
    public static void actualConfigStartup(GameTestHelper h) {
        String scenario=System.getProperty("fmp.configStartup", "");
        h.assertTrue(List.of("missing","broken","illegal","custom").contains(scenario), "Dedicated startup probe required");
        var config=dev.scathiard.feedmepackages.FmpConfig.rules();
        h.assertTrue(dev.scathiard.feedmepackages.FmpConfig.SERVER_SPEC.isLoaded(), "Actual loader never loaded the server file");
        int slots=scenario.equals("custom")?50:scenario.equals("illegal")?dev.scathiard.feedmepackages.domain.CacheCapacity.MAX_SLOTS:9;
        int groups=scenario.equals("custom")?3:scenario.equals("illegal")?1:2;
        h.assertTrue(config.capacity(1).slots()==slots && config.capacity(1).groups()==groups, "Physical TOML did not yield expected safe capacity: "+config.capacity(1));
        h.assertTrue(dev.scathiard.feedmepackages.FmpConfig.taczAmmoEnabled()!=scenario.equals("custom"), "Actual compatibility flag did not load safely");
        var p=TestPlayers.real(h,UUID.randomUUID()); TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),17);
        var a=AccessGate.resolve(p); var ledger=CacheLedger.get(p.getServer()); var record=ledger.find(a.handle().cacheId());
        h.assertTrue(record.state().capacity().equals(config.capacity(1)) && record.state().cells().size()==slots
                && record.state().cells().get(0).amount()==17, "Runtime access ignored the loaded configuration or changed stock");
        var raw=ledger.save(new CompoundTag(),p.registryAccess());var decoded=CacheLedger.load(raw,p.registryAccess());
        h.assertTrue(decoded.problem().isEmpty() && decoded.find(a.handle().cacheId()).state().cells().get(0).amount()==17, "Config startup produced an invalid save");
        FeedMePackages.LOGGER.info("FMP_CONFIG_STARTUP_PASSED case={} slots={} groups={} actualLoader=true conserved=17",scenario,slots,groups);
        h.succeed();
    }

    /** Same physical world across four separate JVMs; not selected by the ordinary test namespace. */
    @GameTest(template = "empty", templateNamespace = "fmp_lifecycle")
    public static void optionalFluidRemovalAndRestoration(GameTestHelper h) {
        String[] mode=System.getProperty("fmp.fluidLifecycle","").split(":");
        h.assertTrue(mode.length==2,"Dedicated lifecycle invocation required");
        boolean residual=mode[0].equals("residual");String phase=mode[1];
        var server=h.getLevel().getServer();var registries=server.registryAccess();var storage=server.overworld().getDataStorage();
        var probe=storage.computeIfAbsent(new SavedData.Factory<>(PersistenceProbe::new,PersistenceProbe::load),"fmp_fluid_lifecycle_probe");
        var ledger=CacheLedger.get(server);var status=dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.status();
        if(phase.equals("write")) {
            h.assertTrue(probe.data.isEmpty(),"Lifecycle world already has evidence; do not overwrite it");
            h.assertTrue(status==dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.Status.READY,"Writer must have the real fluid mod installed");
            var player=TestPlayers.real(h,UUID.randomUUID());TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            var handle=AccessGate.resolve(player).handle();var before=ledger.find(handle.cacheId());
            var items=before.state().edit();var stone=ItemVariantKey.of(new ItemStack(Items.STONE),registries);items.filter(0,stone);items.insert(0,stone,37);
            var water=FluidVariantKey.of(new FluidStack(Fluids.WATER,1),registries);var fluids=before.fluids().state().edit();fluids.filter(1,water);
            var order=UUID.randomUUID();
            if(residual){fluids.thresholds(1,2,-1);fluids.request(1,order,player.getUUID(),125,false);}
            fluids.insert(1,water,residual?15950:2500);
            ledger.replace(handle,before.state().revision(),before.withStates(items.finish(),before.fluids().withState(fluids.finish())));
            if(residual) {
                var box=dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.create(new FluidStack(Fluids.WATER,125));
                PackageItem.addAddress(box,SupplyService.address(player));box.set(DataComponents.CUSTOM_NAME,Component.literal("survive optional mod removal"));
                var seal=new ParcelSeal(UUID.randomUUID(),handle.cacheId(),player.getUUID(),order,water.encoded(),
                        ledger.find(handle.cacheId()).fluids().state().cells().get(1).filterRevision(),SupplyService.address(player),false,"");
                ParcelAuthentication.applyFluid(ledger,registries,box,seal);
                h.assertTrue(ReceiveService.receive(player,box) && box.isEmpty()
                        && dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(ledger.find(handle.cacheId()).fluids().residual(water)).getAmount()==75,
                        "Native residual fixture did not retain exactly 75 mB");
            }
            player.getInventory().setItem(0,new ItemStack(Items.BUCKET));player.getInventory().setItem(13,new ItemStack(Items.DIAMOND,13));saveNativePlayer(player);
            probe.data.putUUID("player",player.getUUID());probe.data.putUUID("cache",handle.cacheId());
            probe.data.putString("case",mode[0]);probe.data.put("expected",row(ledger,handle.cacheId(),registries));
        } else {
            h.assertTrue(!probe.data.isEmpty() && !PROCESS.equals(probe.data.getUUID("writer")) && probe.data.getString("case").equals(mode[0]),"Same-world prior JVM evidence missing");
            String previous = switch(phase) { case "absent" -> "write"; case "absent-again" -> "absent"; default -> "absent-again"; };
            h.assertTrue(probe.data.getString("phase").equals(previous),"Lifecycle phases must run in order without overwriting evidence");
            var player=TestPlayers.real(h,probe.data.getUUID("player"));server.getPlayerList().load(player);
            h.assertTrue(player.getInventory().getItem(13).is(Items.DIAMOND) && player.getInventory().getItem(13).getCount()==13
                    && TestPlayers.necklace(player).getStackInSlot(0).get(FmpRegistries.IDENTITY.get()).equals(probe.data.getUUID("cache")),"Native player file lost pendant identity or inventory");
            h.assertTrue(lifecycleDigest(ledger.save(new CompoundTag(),registries)).equals(probe.data.getString("ledger_digest")),"Saved ledger changed while the process was stopped or during decode");
            if(phase.startsWith("absent")) {
                h.assertTrue(status==dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.Status.MISSING
                        && ledger.problem().isEmpty() && AccessGate.resolve(player).active(),"Optional fluid residual locked ordinary cache access");
                h.assertTrue(row(ledger,probe.data.getUUID("cache"),registries).equals(probe.data.getCompound("expected")),"Absent restart changed cache state");
                if(residual) lifecycleSuspension(h,player);
            }
            if(phase.equals("absent")) {
                h.assertTrue(status==dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.Status.MISSING,"Absence stage still has the mod installed");
                {
                    h.assertTrue(ledger.problem().isEmpty() && AccessGate.resolve(player).active(),"Optional fluid residual locked ordinary cache access");
                    h.assertTrue(row(ledger,probe.data.getUUID("cache"),registries).equals(probe.data.getCompound("expected")),"Fluid cache changed during removal");
                    ReceiveService.resume(player);
                    h.assertTrue(lifecycleDigest(ledger.save(new CompoundTag(),registries)).equals(probe.data.getString("ledger_digest")),"Unavailable residual was changed by background resumption");
                    lifecycleFill(h,player);
                    var bucket=player.getInventory().getItem(0);player.getInventory().setItem(0,ItemStack.EMPTY);player.containerMenu.setCarried(bucket);
                    var view=dev.scathiard.feedmepackages.interaction.CacheActions.snapshot(player);
                    h.assertTrue(dev.scathiard.feedmepackages.interaction.CacheActions.execute(player,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(),view.record().state().revision(),
                            dev.scathiard.feedmepackages.interaction.CacheActions.Action.DEPOSIT_FLUID,1,0,0,""),3)==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK,"Stored vanilla fluid cannot return without transport addon");
                    player.getInventory().setItem(0,player.containerMenu.getCarried());player.containerMenu.setCarried(ItemStack.EMPTY);
                    var plan=dev.scathiard.feedmepackages.consumption.MaterialTransaction.open(player).orElseThrow();
                    h.assertTrue(plan.takeCache(new ItemStack(Items.STONE),1) && plan.commit(),"Ordinary material consumption broke after optional mod removal");
                    player.getInventory().setItem(1,new ItemStack(Items.STONE));
                    var actual=ledger.find(probe.data.getUUID("cache"));
                    h.assertTrue(actual.fluids().state().cells().get(1).amount()==(residual?16000:2500) && actual.state().cells().get(0).amount()==36 && player.getInventory().getItem(0).is(Items.BUCKET),"Uninstalled fluid/container or item conservation failed");
                    probe.data.put("expected",row(ledger,probe.data.getUUID("cache"),registries));
                }
            } else if(phase.equals("restore")) {
                h.assertTrue(status==dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.Status.READY && ledger.problem().isEmpty(),"Reinstalled native addon did not unlock its original ledger");
                h.assertTrue(row(ledger,probe.data.getUUID("cache"),registries).equals(probe.data.getCompound("expected")),"Reinstall changed original state or metadata");
                var access=AccessGate.resolve(player);h.assertTrue(access.active() && access.handle().cacheId().equals(probe.data.getUUID("cache")),"Reinstalled pendant access points at another cache");
                if(residual) {
                    var water=FluidVariantKey.of(new FluidStack(Fluids.WATER,1),registries);var box=ledger.find(access.handle().cacheId()).fluids().residual(water);
                    h.assertTrue(box.get(DataComponents.CUSTOM_NAME).getString().equals("survive optional mod removal")
                            && ParcelAuthentication.validFluid(ledger,registries,box,box.get(FmpRegistries.FLUID_PARCEL_SEAL.get())),"Original residual identity or signature was lost");
                    lifecycleFill(h,player);ReceiveService.resume(player);var after=ledger.find(access.handle().cacheId());
                    h.assertTrue(after.fluids().residual(water).isEmpty()  &&  after.fluids().state().cells().get(1).amount()==15075
                             &&  player.getInventory().getItem(0).is(Items.WATER_BUCKET)
                             &&  after.state().cells().get(0).amount()==36,"Restored native remainder did not resume once with the correct stock");
                    ReceiveService.resume(player);h.assertTrue(ledger.find(access.handle().cacheId())==after,"Restored remainder settled twice");
                }
            }
            saveNativePlayer(player);
        }
        probe.data.putString("ledger_digest",lifecycleDigest(ledger.save(new CompoundTag(),registries)));
        probe.data.putString("phase",phase);probe.data.putUUID("writer",PROCESS);probe.setDirty();ledger.setDirty();
        FeedMePackages.LOGGER.info("FMP_FLUID_LIFECYCLE_PASSED case={} phase={} schema={} actualPlayerFiles=true locked={}",mode[0],phase,CacheLedger.SCHEMA,!ledger.problem().isEmpty());h.succeed();
    }
    /** Dedicated fresh world, four process boundaries; never selected by the ordinary namespace. */
    @GameTest(template="empty",templateNamespace="fmp_capacity")
    public static void configuredCapacityAcrossProcesses(GameTestHelper h) {
        String phase=System.getProperty("fmp.capacityLifecycle","");
        h.assertTrue(List.of("write","shrink","restore","verify").contains(phase),"Dedicated capacity lifecycle required");
        var server=h.getLevel().getServer();var registries=server.registryAccess();
        var probe=server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PersistenceProbe::new,PersistenceProbe::load),"fmp_capacity_probe");
        var ledger=CacheLedger.get(server);h.assertTrue(ledger.problem().isEmpty(),"Capacity world locked: "+ledger.problem());
        var slots=dev.scathiard.feedmepackages.FmpConfig.SLOTS.get(0);var groups=dev.scathiard.feedmepackages.FmpConfig.GROUPS.get(0);
        slots.set(phase.equals("shrink")?9:50);groups.set(phase.equals("shrink")?2:32);dev.scathiard.feedmepackages.FmpConfig.refreshServer();
        ServerPlayer player;
        if(phase.equals("write")) {
            h.assertTrue(probe.data.isEmpty(),"Capacity lifecycle world already holds evidence; use a new batch");
            player=TestPlayers.real(h,UUID.randomUUID());TestPlayers.necklace(player).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            var handle=AccessGate.resolve(player).handle();var before=ledger.find(handle.cacheId());var edit=before.state().edit();
            var stone=ItemVariantKey.of(new ItemStack(Items.STONE),registries);var iron=ItemVariantKey.of(new ItemStack(Items.IRON_INGOT),registries);
            edit.filter(0,stone);edit.insert(0,stone,1800);edit.settings(0,new dev.scathiard.feedmepackages.domain.CellSettings(1900,2000,64,128));
            edit.filter(49,iron);edit.insert(49,iron,11);ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            before=ledger.find(handle.cacheId());var fluids=before.fluids().state().edit();var water=FluidVariantKey.of(new FluidStack(Fluids.WATER,1),registries);
            fluids.filter(40,water);fluids.insert(40,water,24000);ledger.replace(handle,before.state().revision(),before.withFluids(before.fluids().withState(fluids.finish())));
            probe.data.putUUID("player",player.getUUID());probe.data.putUUID("cache",handle.cacheId());
        } else {
            String prior=switch(phase){case "shrink"->"write";case "restore"->"shrink";default->"restore";};
            h.assertTrue(!probe.data.isEmpty() && !PROCESS.equals(probe.data.getUUID("writer")) && probe.data.getString("phase").equals(prior),"Capacity evidence is not the prior JVM phase");
            h.assertTrue(row(ledger,probe.data.getUUID("cache"),registries).equals(probe.data.getCompound("expected")),"Stopped-world cache changed during persisted decode");
            player=TestPlayers.real(h,probe.data.getUUID("player"));h.assertTrue(server.getPlayerList().load(player).isPresent(),"Native player save missing");
            var access=AccessGate.resolve(player);h.assertTrue(access.active() && access.handle().cacheId().equals(probe.data.getUUID("cache")),"Config change lost pendant identity");
            var record=ledger.find(access.handle().cacheId());
            h.assertTrue(record.state().cells().size()==50 && record.state().cells().get(0).amount()==1800
                    && record.fluids().state().cells().get(40).amount()==24000,"Config change deleted stock or slot identity");
            if(phase.equals("shrink")) {
                h.assertTrue(!record.state().writable(0) && !record.state().writable(49) && !record.fluids().state().writable(40),"Shrink did not enforce readonly rights");
                var view=dev.scathiard.feedmepackages.interaction.CacheActions.snapshot(player);
                var result=dev.scathiard.feedmepackages.interaction.CacheActions.execute(player,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(),view.record().state().revision(),dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_INVENTORY,49,1,-1,""));
                h.assertTrue(result==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK,"Retained slot could not be safely taken");
            } else h.assertTrue(record.state().writable(0) && record.state().writable(49) && record.fluids().state().writable(40)
                    && record.state().cells().get(0).settings().lower()==1900 && record.state().cells().get(0).settings().upper()==2000,"Restored config did not recover rights and requested controls");
            h.assertTrue(ledger.find(access.handle().cacheId()).state().cells().get(49).amount()==10
                    && player.getInventory().countItem(Items.IRON_INGOT)==1,"Native player plus cache conservation failed");
        }
        saveNativePlayer(player);probe.data.put("expected",row(ledger,probe.data.getUUID("cache"),registries));
        probe.data.putString("phase",phase);probe.data.putUUID("writer",PROCESS);probe.setDirty();ledger.setDirty();
        FeedMePackages.LOGGER.info("FMP_CAPACITY_LIFECYCLE_PASSED phase={} schema={} slots=50 itemsAndFluids=true nativePlayer=true",phase,CacheLedger.SCHEMA);h.succeed();
    }

    private static void lifecycleSuspension(GameTestHelper h, ServerPlayer player) {
        var ledger=CacheLedger.get(player.getServer());var access=AccessGate.resolve(player);var before=ledger.find(access.handle().cacheId());
        var raw=before.fluids().suspendedResiduals();
        h.assertTrue(raw.size()==1 && before.anyResidual() && !before.blocksReplenishment() && before.fluids().residuals().isEmpty(),
                "Missing-addon parcel lost ownership or still blocked ordinary requests");
        raw.values().iterator().next().putString("external_mutation","must not enter storage");
        h.assertTrue(before.fluids().suspendedResiduals().values().stream().noneMatch(t->t.contains("external_mutation")),"Suspended NBT exposed mutable storage");
        var original=ledger.save(new CompoundTag(),player.registryAccess());
        for(String style:List.of("fluid_package","fluid_package_exposed","fluid_package_weathered","fluid_package_oxidized")) {
            var copy=original.copy();var rows=lifecycleResidualRows(copy,before.state().id());
            rows.getCompound(0).getCompound("package").putString("id","fluidlogistics:"+style);
            var decoded=CacheLedger.load(copy,player.registryAccess());
            h.assertTrue(decoded.problem().isEmpty() && decoded.find(before.state().id()).fluids().hasSuspendedResiduals()
                    && decoded.save(new CompoundTag(),player.registryAccess()).equals(copy),"Known native style was lost or changed: "+style);
        }
        for(int malformed=0;malformed<4;malformed++) {
            var copy=original.copy();var rows=lifecycleResidualRows(copy,before.state().id());var box=rows.getCompound(0).getCompound("package");
            if(malformed==0) box.putString("id","missing_foreign_mod:unknown_package");
            if(malformed==1) box.putInt("count",2);
            if(malformed==2) box.putString("components","not a valid component map");
            if(malformed==3) rows.add(rows.getCompound(0).copy());
            var decoded=CacheLedger.load(copy,player.registryAccess());
            h.assertTrue(!decoded.problem().isEmpty() && decoded.save(new CompoundTag(),player.registryAccess()).equals(copy),
                    "Unknown or malformed data was silently accepted or erased: "+malformed);
        }
        var detached=CacheLedger.load(original.copy(),player.registryAccess());
        var upgrading=detached.find(before.state().id());var upgrade=detached.prepareOrdinaryUpgrade(upgrading,2);upgrade.commit();
        var upgraded=detached.find(before.state().id());
        h.assertTrue(upgraded.state().level()==2 && upgraded.fluids().suspendedResiduals().equals(before.fluids().suspendedResiduals()),"Ordinary upgrade discarded suspended NBT");
        var fluidEdit=upgraded.fluids().state().edit();fluidEdit.thresholds(1,1,-1);
        h.assertTrue(upgraded.withFluids(upgraded.fluids().withState(fluidEdit.finish())).fluids().suspendedResiduals().equals(before.fluids().suspendedResiduals()),
                "Fluid edit discarded suspended NBT");
        var sent=TestPlayers.nativePackets(player);player.tickCount=0;
        dev.scathiard.feedmepackages.network.CompatibilityStatus.tick(player);
        dev.scathiard.feedmepackages.network.CompatibilityStatus.tick(player);
        h.assertTrue(sent.stream().filter(p->p instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("message.create_feed_me_packages.compat.fluid_residual_suspended")).count()==1,
                "Missing-addon warning was silent or spammed repeated ticks");
        ReceiveService.resume(player);
        h.assertTrue(original.equals(ledger.save(new CompoundTag(),player.registryAccess())),"Compatibility status or resumption mutated suspended data");
    }
    private static ListTag lifecycleResidualRows(CompoundTag ledger,UUID cache) {
        var row=ledger.getList("caches",Tag.TAG_COMPOUND).stream().map(t->(CompoundTag)t)
                .filter(t->t.getUUID("id").equals(cache)).findFirst().orElseThrow();
        return row.getCompound("astra_fluids").getList("residuals",Tag.TAG_COMPOUND);
    }
    private static String lifecycleDigest(CompoundTag data) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static void lifecycleFill(GameTestHelper h,ServerPlayer player) {
        var view=dev.scathiard.feedmepackages.interaction.CacheActions.open(player);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.CacheActions.execute(player,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(),view.record().state().revision(),
                dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_FLUID,1,1000,0,""),1)==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK,"Reopened native world could not preview stored fluid");
        view=dev.scathiard.feedmepackages.interaction.CacheActions.snapshot(player);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.CacheActions.execute(player,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(),view.record().state().revision(),
                dev.scathiard.feedmepackages.interaction.CacheActions.Action.PLACE_FLUID,1,0,1,ItemVariantKey.of(player.getInventory().getItem(0),player.registryAccess()).encoded()),2)
                ==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK && player.getInventory().getItem(0).is(Items.WATER_BUCKET),"Reopened native world could not fill a real bucket");
    }

    @GameTest(template = "empty")
    public static void convergedSchemaEightConvertsUnitsAndPreservesExactData(GameTestHelper h) {
        var registry = h.getLevel().registryAccess(); var old = new CacheLedger();
        UUID owner = UUID.randomUUID(), id = LegacyOwnership.on(old).personalOrCreate(owner); var handle = new CacheHandle(id, owner, null);
        var stone = ItemVariantKey.of(new ItemStack(Items.STONE), registry);
        var pearl = ItemVariantKey.of(new ItemStack(Items.ENDER_PEARL), registry);
        var source = old.find(id); var edit = source.state().edit();
        edit.filter(0, stone); edit.filter(1, pearl); edit.thresholds(0, 2, 2); edit.thresholds(1, 2, 2);
        edit.insert(0, stone, 37); edit.insert(1, pearl, 11);
        edit.request(0, UUID.randomUUID(), owner, 27, true);
        old.replace(handle, source.state().revision(), source.withState(edit.finish())); old.setReturnAddress(id, "keep-return");
        var eight = old.save(new CompoundTag(), registry); eight.putInt("schema", 8); eight.remove("private_retirements"); eight.remove("return_handoffs");
        for (Tag row : eight.getList("caches", Tag.TAG_COMPOUND)) {
            ((CompoundTag)row).remove("capacity_rules"); ((CompoundTag)row).remove("astra_fluids");
            for (Tag cell : ((CompoundTag) row).getList("cells", Tag.TAG_COMPOUND)) ((CompoundTag) cell).remove("item_controls");
        }
        var loaded = CacheLedger.load(eight, registry); var record = loaded.find(id);
        h.assertTrue(loaded.problem().isEmpty() && record.state().cells().get(0).settings().lower() == 128
                && record.state().cells().get(1).settings().lower() == 32 && record.state().cells().get(1).amount() == 11
                && record.state().pending(0) == 27 && loaded.authentic("probe", old.authenticate("probe"))
                && "keep-return".equals(loaded.returnAddress(id)), "0.2.3 unit conversion changed stock, promises, address or seal identity");
        var saved = loaded.save(new CompoundTag(), registry);
        h.assertTrue(saved.getList("caches", Tag.TAG_COMPOUND).getCompound(0).getList("cells", Tag.TAG_COMPOUND).getCompound(0).getInt("min") == 2,
                "Legacy group column was reinterpreted or removed");
        var transfer = loaded.beginRetirement(owner); loaded.completeRetirement(owner, transfer.receipt());
        var completed = CacheLedger.load(loaded.save(new CompoundTag(), registry), registry);
        h.assertTrue(completed.problem().isEmpty() && completed.personal(owner) == null && completed.find(id).owner() == null
                && completed.find(id).state().equals(record.state()) && completed.retirement(owner).completed(), "Retirement failed durable exactly-once ownership conversion");
        loaded.completeRetirement(owner, transfer.receipt());
        h.assertTrue(loaded.find(id).state().equals(record.state()), "Repeated retirement duplicated or changed stock");
        var bad = completed.save(new CompoundTag(), registry);
        bad.getList("caches", Tag.TAG_COMPOUND).getCompound(0).getList("cells", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("item_controls").putInt("batch", 577);
        var locked = CacheLedger.load(bad, registry);
        h.assertTrue(!locked.problem().isEmpty() && locked.save(new CompoundTag(), registry).equals(bad), "Invalid control record was rewritten instead of retained");
        h.succeed();
    }

    static LegacyRetirement.Persistence simulatedCheckpoints() {
        return new LegacyRetirement.Persistence() {
            public void ledger(CacheLedger ledger) {}
            public void player(ServerPlayer player, ItemStack replacement) {
                if (!replacement.has(FmpRegistries.RETIREMENT_RECEIPT.get())) throw new AssertionError("Missing delivery receipt");
            }
        };
    }

    @GameTest(template = "empty")
    public static void convergedRetirementWaitsForSpaceThenGrantsOnlyOnce(GameTestHelper h) {
        var player = TestPlayers.create(h, ItemStack.EMPTY); var ledger = CacheLedger.get(player.getServer());
        UUID id = LegacyOwnership.on(ledger).personalOrCreate(player.getUUID()); var source = ledger.find(id); var edit = source.state().edit();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        edit.filter(0, key); edit.insert(0, key, 57); edit.upgrade();
        ledger.replace(new CacheHandle(id, player.getUUID(), null), source.state().revision(), source.withState(edit.finish()));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        h.assertTrue(LegacyRetirement.transfer(player, simulatedCheckpoints()) == LegacyRetirement.Outcome.WAITING_FOR_SPACE
                && ledger.retirement(player.getUUID()) == null && ledger.find(id).owner().equals(player.getUUID()), "Full backpack lost or prematurely claimed legacy stock");
        player.getInventory().setItem(7, ItemStack.EMPTY);
        h.assertTrue(LegacyRetirement.transfer(player, simulatedCheckpoints()) == LegacyRetirement.Outcome.TRANSFERRED, "No-key owner could not claim");
        var delivered = player.getInventory().getItem(7);
        h.assertTrue(id.equals(delivered.get(FmpRegistries.IDENTITY.get())) && ledger.find(id).state().level() == 2
                && ledger.find(id).state().cells().get(0).amount() == 57, "Wrong delivery identity, level or stock");
        var oldKey = FmpRegistries.PERSONAL_PENDANT.toStack(); oldKey.set(FmpRegistries.OWNER.get(), player.getUUID());
        player.getInventory().setItem(8, oldKey); LegacyRetirement.transfer(player, simulatedCheckpoints());
        h.assertTrue(player.getInventory().getItem(8).is(FmpRegistries.PENDANT.get())
                && !player.getInventory().getItem(8).has(FmpRegistries.IDENTITY.get()) && player.getInventory().getItem(7) == delivered,
                "Extra key duplicated personal stock");
        player.getInventory().setItem(7, ItemStack.EMPTY); TestPlayers.necklace(player).setStackInSlot(0, delivered);
        h.assertTrue(AccessGate.resolve(player).active() && AccessGate.resolve(player).handle().cacheId().equals(id), "Retired cache cannot be accessed as an ordinary pendant");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void convergedInterruptedRetirementRecoversExistingReceiptAndRejectsOtherOwner(GameTestHelper h) {
        var player = TestPlayers.create(h, ItemStack.EMPTY); var ledger = CacheLedger.get(player.getServer());
        UUID id = LegacyOwnership.on(ledger).personalOrCreate(player.getUUID());
        var key = FmpRegistries.PERSONAL_PENDANT.toStack(); key.set(FmpRegistries.OWNER.get(), player.getUUID());
        var thief = TestPlayers.create(h, key.copy());
        LegacyRetirement.transfer(thief, simulatedCheckpoints());
        h.assertTrue(ledger.personal(player.getUUID()).equals(id) && !TestPlayers.necklace(thief).getStackInSlot(0).has(FmpRegistries.IDENTITY.get()),
                "Non-owner claimed legacy stock");
        TestPlayers.necklace(player).setStackInSlot(0, key);
        try {
            LegacyRetirement.transfer(player, new LegacyRetirement.Persistence() {
                public void ledger(CacheLedger data) {}
                public void player(ServerPlayer p, ItemStack replacement) { throw new IllegalStateException("Injected interrupted player checkpoint"); }
            });
            throw new AssertionError("Expected checkpoint failure");
        } catch (IllegalStateException expected) {}
        var replacement = TestPlayers.necklace(player).getStackInSlot(0);
        h.assertTrue(!AccessGate.resolve(player).active() && replacement.has(FmpRegistries.RETIREMENT_RECEIPT.get())
                && !ledger.retirement(player.getUUID()).completed(), "Pending delivery became accessible before save confirmation");
        h.assertTrue(LegacyRetirement.transfer(player, simulatedCheckpoints()) == LegacyRetirement.Outcome.RECOVERED
                && TestPlayers.necklace(player).getStackInSlot(0) == replacement && AccessGate.resolve(player).active(), "Retry issued a different cache or failed to recover");
        // The completed credential remains on the ordinary pendant across subsequent ticks.
        replacement.set(FmpRegistries.RETIREMENT_RECEIPT.get(), ledger.retirement(player.getUUID()).receipt());
        LegacyRetirement.transfer(player, simulatedCheckpoints());
        h.assertTrue(AccessGate.resolve(player).active(), "Completed receipt remained permanently locked");
        var revoked = FmpRegistries.PENDANT.toStack(); revoked.set(FmpRegistries.IDENTITY.get(), id);
        TestPlayers.necklace(thief).setStackInSlot(0, revoked);
        h.assertTrue(!AccessGate.resolve(thief).active(), "Revoked historical ordinary copy regained access after retirement");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void missingInterruptedReceiptIsRevokedBeforeReplacement(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PERSONAL_PENDANT.toStack());
        var ledger=CacheLedger.get(player.getServer()); var id=LegacyOwnership.on(ledger).personalOrCreate(player.getUUID());
        try {
            LegacyRetirement.transfer(player,new LegacyRetirement.Persistence() {
                public void ledger(CacheLedger data) {}
                public void player(ServerPlayer p,ItemStack stack) { throw new IllegalStateException("Interrupted receipt save"); }
            });
        } catch (IllegalStateException expected) {}
        var stale=TestPlayers.necklace(player).getStackInSlot(0);
        var staleReceipt=stale.get(FmpRegistries.RETIREMENT_RECEIPT.get());
        h.assertTrue(staleReceipt!=null && !ledger.retirement(player.getUUID()).completed(),"Interruption fixture did not leave a pending receipt");
        TestPlayers.necklace(player).setStackInSlot(0,ItemStack.EMPTY);
        var other=TestPlayers.create(h,stale);
        LegacyRetirement.transfer(player,simulatedCheckpoints());
        var delivered=player.getInventory().removeItemNoUpdate(0); TestPlayers.necklace(player).setStackInSlot(0,delivered);
        h.assertTrue(AccessGate.resolve(player).active() && AccessGate.resolve(player).handle().cacheId().equals(id)
                && !AccessGate.resolve(other).active() && !staleReceipt.equals(delivered.get(FmpRegistries.RETIREMENT_RECEIPT.get())),
                "An unknown former receipt regained authority after recovery");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void retirementUsesRealWorldAndPlayerCheckpoints(GameTestHelper h) {
        var server=h.getLevel().getServer(); var player=TestPlayers.real(h,UUID.randomUUID());
        var ledger=CacheLedger.get(server); var id=LegacyOwnership.on(ledger).personalOrCreate(player.getUUID());
        var old=FmpRegistries.PERSONAL_PENDANT.toStack(); old.set(FmpRegistries.OWNER.get(),player.getUUID());
        TestPlayers.necklace(player).setStackInSlot(0,old);
        var source=ledger.find(id); var edit=source.state().edit();
        var key=ItemVariantKey.of(new ItemStack(Items.ENDER_PEARL),player.registryAccess()); edit.filter(0,key); edit.insert(0,key,19);
        ledger.replace(new CacheHandle(id,player.getUUID(),null),source.state().revision(),source.withState(edit.finish()));
        // Only this test server's player list is used; no user world or logged-in user is touched.
        TestPlayers.nativeSavePlayers(server).add(player);
        try { player.tickCount=20; LegacyRetirement.tick(player); }
        finally { TestPlayers.nativeSavePlayers(server).remove(player); }
        h.assertTrue(ledger.retirement(player.getUUID())!=null && ledger.retirement(player.getUUID()).completed()
                && AccessGate.resolve(player).active(),"Native checkpoint verification did not complete retirement");
        var restored=TestPlayers.create(h,player.getUUID(),ItemStack.EMPTY);
        h.assertTrue(server.getPlayerList().load(restored).isPresent() && AccessGate.resolve(restored).active()
                && AccessGate.resolve(restored).handle().cacheId().equals(id)
                && ledger.find(id).state().cells().getFirst().amount()==19,"Saved player did not restore the actual retired stock credential");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void exactVariantsPreserveComponents(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var plain = new ItemStack(Items.ARROW, 32);
        var named = new ItemStack(Items.ARROW, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Supply specimen"));
        var key = ItemVariantKey.of(named, registries);
        helper.assertTrue(!key.equals(ItemVariantKey.of(plain, registries)), "Component variants collapsed");
        helper.assertTrue(key.equals(ItemVariantKey.decode(key.encoded(), registries)), "Canonical template does not round-trip");
        var recovered = key.stack(registries, 3);
        helper.assertTrue(ItemStack.matches(recovered, named), "Components changed in storage");
        recovered.set(DataComponents.CUSTOM_NAME, Component.literal("Changed copy"));
        helper.assertTrue(ItemStack.matches(key.stack(registries, 3), named), "Caller changed immutable variant");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsafeTemplatesAreRejected(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var large = new ItemStack(Items.STONE); large.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(1100)));
        rejects(helper, () -> ItemVariantKey.of(large, registries));
        rejects(helper, () -> ItemVariantKey.of(FmpRegistries.PENDANT.toStack(), registries));
        rejects(helper, () -> ItemVariantKey.decode("{id:\"minecraft:stone\",count:64}", registries));
        rejects(helper, () -> ItemVariantKey.decode("{id:\"missing:no_item\",count:1}", registries));
        helper.assertTrue(large.getCount() == 1 && large.getHoverName().getString().length() == 1100, "Rejected item mutated");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ledgerReloadsWholeCacheAndPlayerIsolation(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var ledger = new CacheLedger();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(); UUID id = ledger.createOrdinary();
        var record = ledger.find(id); var key = ItemVariantKey.of(new ItemStack(Items.STONE), registries);
        var edit = record.state().edit(); edit.filter(0, key); edit.insert(0, key, 90); edit.thresholds(0, 2, -1);
        edit.request(0, UUID.randomUUID(), alice, 30, false);
        var state = edit.finish(); ledger.replace(new CacheHandle(id, alice, null), record.state().revision(), record.withState(state));
        LegacyOwnership.on(ledger).personalOrCreate(alice); LegacyOwnership.on(ledger).personalOrCreate(bob); ledger.setCacheFirst(alice, true);
        var saved = ledger.save(new CompoundTag(), registries); var loaded = CacheLedger.load(saved, registries);
        helper.assertTrue(loaded.problem().isEmpty(), "Valid ledger locked");
        helper.assertTrue(state.equals(loaded.find(id).state()), "Cache changed across reload");
        helper.assertTrue(!loaded.personal(alice).equals(loaded.personal(bob)), "Players share private inventory");
        helper.assertTrue(!loaded.cacheFirst(alice) && !loaded.cacheFirst(bob), "Persisted inactive preference overrode fixed priority");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void corruptLedgerLocksAndPreservesOriginal(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var invalid = new CompoundTag(); invalid.putInt("schema", 999);
        invalid.putString("irreplaceable_evidence", "keep original");
        var loaded = CacheLedger.load(invalid, registries);
        helper.assertTrue(!loaded.problem().isEmpty(), "Corrupt schema was accepted");
        rejects(helper, loaded::createOrdinary);
        helper.assertTrue(invalid.equals(loaded.save(new CompoundTag(), registries)), "Rejected data overwritten");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void configuredCapacityRetainsExactStockAndOriginalControlsAcrossReload(GameTestHelper h) {
        var registries=h.getLevel().registryAccess();var ledger=new CacheLedger();var id=ledger.createOrdinary();
        var handle=new CacheHandle(id,UUID.randomUUID(),null);var before=ledger.find(id);
        var stone=ItemVariantKey.of(new ItemStack(Items.STONE),registries);var iron=ItemVariantKey.of(new ItemStack(Items.IRON_INGOT),registries);
        var water=FluidVariantKey.of(new FluidStack(Fluids.WATER,1),registries);
        var controls=new dev.scathiard.feedmepackages.domain.CellSettings(1900,2000,64,128);
        var items=before.state().edit();items.resize(new dev.scathiard.feedmepackages.domain.CacheCapacity(50,32));
        items.filter(0,stone);items.insert(0,stone,1800);items.settings(0,controls);items.filter(49,iron);items.insert(49,iron,11);
        var grown=before.withState(items.finish());ledger.replace(handle,before.state().revision(),grown);var fluids=grown.fluids().state().edit();fluids.filter(40,water);fluids.insert(40,water,24000);
        long grownRevision=grown.state().revision();grown=grown.withFluids(grown.fluids().withState(fluids.finish()));
        ledger.replace(handle,grownRevision,grown);
        var shrink=grown.state().edit();shrink.resize(new dev.scathiard.feedmepackages.domain.CacheCapacity(9,2));
        var reduced=grown.withState(shrink.finish());ledger.replace(handle,grown.state().revision(),reduced);
        var saved=ledger.save(new CompoundTag(),registries);var loaded=CacheLedger.load(saved,registries);
        h.assertTrue(loaded.problem().isEmpty(),"Legal over-capacity stock locked on reload: "+loaded.problem());
        var restored=loaded.find(id);
        h.assertTrue(restored.state().equals(reduced.state()) && restored.fluids().state().equals(reduced.fluids().state()),"Capacity provenance changed across reload");
        h.assertTrue(!restored.state().writable(0) && !restored.state().writable(49) && !restored.fluids().state().writable(40)
                && restored.state().cells().get(0).amount()==1800 && restored.fluids().state().cells().get(40).amount()==24000,"Reduced stock was lost or writable");
        var take=restored.state().edit();h.assertTrue(take.extract(49,1)==1,"Hidden retained slot was not extractable");
        take.resize(new dev.scathiard.feedmepackages.domain.CacheCapacity(50,32));restored=restored.withState(take.finish());
        h.assertTrue(restored.state().writable(49) && restored.state().cells().get(49).amount()==10
                && restored.state().cells().get(0).settings().equals(controls) && restored.fluids().state().writable(40),"Restoring config did not restore exact controls and rights");
        for(int fault=0;fault<3;fault++) {
            var invalid=saved.copy();var row=invalid.getList("caches",Tag.TAG_COMPOUND).getCompound(0);
            if(fault==0)row.remove("capacity_rules");
            if(fault==1)row.getCompound("capacity_rules").getList("retained",Tag.TAG_COMPOUND).getCompound(0).putInt("ceiling",128);
            if(fault==2)row.getCompound("capacity_rules").putInt("slots",129);
            var rejected=CacheLedger.load(invalid,registries);
            h.assertTrue(rejected.problem().contains(id.toString()) && rejected.save(new CompoundTag(),registries).equals(invalid),"Unexplained capacity accepted, unlocatable, or raw invalid data discarded: "+fault);
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void previousConvergedSchemaAddsCapacityWithoutChangingExistingFields(GameTestHelper h) {
        var registries=h.getLevel().registryAccess();var ledger=new CacheLedger();var id=ledger.createOrdinary();
        var source=ledger.save(new CompoundTag(),registries);source.putInt("schema",31001);
        for(var element:source.getList("caches",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;row.remove("capacity_rules");row.getCompound("astra_fluids").remove("capacity_rules");
        }
        var loaded=CacheLedger.load(source,registries);
        h.assertTrue(loaded.problem().isEmpty() && loaded.find(id).state().equals(ledger.find(id).state()),"Previous converged schema lost its original state: "+loaded.problem());
        var saved=loaded.save(new CompoundTag(),registries);saved.putInt("schema",31001);
        for(var element:saved.getList("caches",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;row.remove("capacity_rules");row.getCompound("astra_fluids").remove("capacity_rules");
        }
        h.assertTrue(saved.equals(source),"Additive capacity upgrade changed a previous field");h.succeed();
    }

    private static void rejects(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsafe operation was accepted");
    }

    @GameTest(template = "empty")
    public static void wrongListElementTypesNeverBecomeEmptyState(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var ledger = new CacheLedger(); ledger.createOrdinary();
        var valid = ledger.save(new CompoundTag(), registries);
        var wrongOrders = valid.copy(); var strings = new ListTag(); strings.add(StringTag.valueOf("unreadable order"));
        wrongOrders.getList("caches", Tag.TAG_COMPOUND).getCompound(0).put("orders", strings);
        var wrongPreferences = valid.copy(); wrongPreferences.put("preferences", strings.copy());
        for (var invalid : java.util.List.of(wrongOrders, wrongPreferences)) {
            var loaded = CacheLedger.load(invalid, registries);
            helper.assertTrue(!loaded.problem().isEmpty() && loaded.save(new CompoundTag(), registries).equals(invalid), "Malformed list silently erased its data");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyCommitInvalidatesPriorRevision(GameTestHelper helper) {
        var ledger = new CacheLedger(); var id = ledger.createOrdinary(); var original = ledger.find(id);
        var handle = new CacheHandle(id, UUID.randomUUID(), null);
        ledger.replace(handle, original.state().revision(), original);
        helper.assertTrue(ledger.find(id).state().revision() == original.state().revision() + 1, "Record-only commit retained stale revision");
        rejects(helper, () -> ledger.replace(handle, original.state().revision(), original));
        helper.succeed();
    }

    private static final UUID PROCESS = UUID.randomUUID();

    @GameTest(template = "empty")
    public static void manufacturingProposalsRegisterNothingUntilTheirSingleCommit(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer()); var registries = helper.getLevel().registryAccess();
        var before = ledger.save(new CompoundTag(), registries); var source = ledger.ordinaryForManufacturing(null);
        var proposal = ledger.prepareOrdinaryUpgrade(source, 2);
        helper.assertTrue(before.equals(ledger.save(new CompoundTag(), registries)) && ledger.find(source.state().id()) == null,
                "Preview registered an ordinary cache");
        proposal.commit();
        helper.assertTrue(ledger.find(source.state().id()).state().level() == 2 && !proposal.current(), "Actual upgrade was not committed once");
        rejects(helper, proposal::commit);
        var stale = ledger.prepareOrdinaryUpgrade(ledger.find(source.state().id()), 3); ledger.createOrdinary();
        var after = ledger.save(new CompoundTag(), registries); rejects(helper, stale::commit);
        helper.assertTrue(after.equals(ledger.save(new CompoundTag(), registries)), "Stale world proposal overwrote later state");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retirementPreservesHistoricalOwnershipAndRevokesOrdinaryCopies(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); helper.assertTrue(ReceiveService.receive(f.player(), f.box(32)), "Private ownership fixture failed");
        var before = f.ledger().save(new CompoundTag(), f.player().registryAccess());
        var plan = PrivateCacheMerge.simulate(f.ledger(), f.player().registryAccess(), f.player().getUUID(), f.handle().cacheId());
        var transaction = LegacyOwnership.on(f.ledger()).prepareOwnership(plan.source(), plan.target(), plan.replacement(), plan.sourceRoutes());
        helper.assertTrue(f.ledger().personal(f.player().getUUID()) == null && before.equals(f.ledger().save(new CompoundTag(), f.player().registryAccess())),
                "Ownership preparation changed the ledger");
        transaction.commit(); rejects(helper, transaction::commit);
        helper.assertTrue(f.ledger().personal(f.player().getUUID()).equals(f.handle().cacheId()) && f.record().owner().equals(f.player().getUUID()), "First privatization changed its cache id");
        helper.assertTrue(!AccessGate.resolve(f.player()).active(), "Old ordinary input still grants personal cache access");
        var personalPendant = FmpRegistries.PERSONAL_PENDANT.toStack(); personalPendant.set(FmpRegistries.OWNER.get(), f.player().getUUID());
        TestPlayers.necklace(f.player()).setStackInSlot(0, personalPendant);
        LegacyRetirement.transfer(f.player(), simulatedCheckpoints());
        helper.assertTrue(AccessGate.resolve(f.player()).handle().cacheId().equals(f.handle().cacheId()) && !f.residual().isEmpty()
                && f.record().state().cells().getFirst().amount() == ReceiveTests.FULL && f.record().state().pending(0) == 32, "Ownership commit lost a part of the cache");
        var copy = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        helper.assertTrue(copy.problem().isEmpty() && copy.personal(f.player().getUUID()) == null
                && copy.ordinaryEntry(f.handle().cacheId(), TestPlayers.necklace(f.player()).getStackInSlot(0).get(FmpRegistries.RETIREMENT_RECEIPT.get()))
                && !copy.ordinaryEntry(f.handle().cacheId(), null), "Retired authority did not save/load safely");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mergedCacheAcceptsAuthenticatedLateParcelsButNotOldAccessOrOldFilters(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); ItemStack late = f.box(32), later = f.box(16);
        UUID target = LegacyOwnership.on(f.ledger()).personalOrCreate(f.player().getUUID());
        var plan = PrivateCacheMerge.simulate(f.ledger(), f.player().registryAccess(), f.player().getUUID(), f.handle().cacheId());
        LegacyOwnership.on(f.ledger()).prepareOwnership(plan.source(), plan.target(), plan.replacement(), plan.sourceRoutes()).commit();
        helper.assertTrue(f.ledger().find(f.handle().cacheId()) == null && !AccessGate.resolve(f.player()).active(), "Retired ordinary cache remained accessible");
        var copy = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        long epoch = late.get(FmpRegistries.PARCEL_SEAL.get()).filterRevision();
        helper.assertTrue(copy.problem().isEmpty() && copy.parcelTarget(f.handle().cacheId(), f.key(), epoch).cacheId().equals(target), "Manufacturing route did not save/load");
        var mergedPersonal = FmpRegistries.PERSONAL_PENDANT.toStack(); mergedPersonal.set(FmpRegistries.OWNER.get(), f.player().getUUID());
        TestPlayers.necklace(f.player()).setStackInSlot(0, mergedPersonal);
        LegacyRetirement.transfer(f.player(), simulatedCheckpoints());
        helper.assertTrue(ReceiveService.receive(f.player(), late) && late.isEmpty(), "Valid late parcel could not find the merged cache");
        var current = f.ledger().find(target); var handle = AccessGate.resolve(f.player()).handle();
        helper.assertTrue(current.state().cells().getFirst().amount() == 32 && current.state().pending(0) == 32, "Redirected delivery lost stock or order state");
        var edit = current.state().edit(); edit.extract(0, 32); edit.filter(0, null); edit.filter(1, f.key());
        f.ledger().replace(handle, current.state().revision(), current.withState(edit.finish()));
        ItemStack unchanged = later.copy();
        helper.assertTrue(!ReceiveService.receive(f.player(), later) && ItemStack.matches(later, unchanged), "Redirect bypassed an expired filter lifetime");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retiredCacheUpgradesOnlyAfterOrdinaryHandover(GameTestHelper helper) {
        var ledger=CacheLedger.get(helper.getLevel().getServer());var registries=helper.getLevel().registryAccess();
        var player=TestPlayers.create(helper,FmpRegistries.PERSONAL_PENDANT.toStack());
        UUID id=LegacyOwnership.on(ledger).personalOrCreate(player.getUUID());
        rejects(helper,()->ledger.prepareOrdinaryUpgrade(ledger.find(id),2));
        LegacyRetirement.transfer(player,simulatedCheckpoints());
        var correct=ledger.prepareOrdinaryUpgrade(ledger.find(id),2);
        helper.assertTrue(ledger.find(id).state().level()==1,"Upgrade preview advanced the retired cache");
        correct.commit();rejects(helper,correct::commit);
        helper.assertTrue(ledger.find(id).owner()==null && ledger.find(id).state().level()==2,"Retired cache did not upgrade as ordinary");
        rejects(helper,()->ledger.prepareOrdinaryUpgrade(ledger.find(id),4));
        var saved=CacheLedger.load(ledger.save(new CompoundTag(),registries),registries);
        helper.assertTrue(saved.problem().isEmpty() && saved.find(id).state().level()==2 && saved.find(id).owner()==null,
            "Ordinary upgrade did not preserve retired state on reload");
        helper.succeed();
    }
    private static final UUID ORDINARY_PLAYER = UUID.fromString("36fa75ac-ce67-44c5-8c66-a54f10150001");
    private static final UUID PERSONAL_PLAYER = UUID.fromString("36fa75ac-ce67-44c5-8c66-a54f10150002");

    @GameTest(template = "empty")
    public static void ordinaryCacheSurvivesNativeDeathAndClone(GameTestHelper helper) { deathAndClone(helper, false); }

    @GameTest(template = "empty")
    public static void retiredOrdinaryCacheSurvivesNativeDeathAndClone(GameTestHelper helper) { deathAndClone(helper, true); }

    private static void deathAndClone(GameTestHelper helper, boolean personal) {
        var rules = helper.getLevel().getGameRules(); var server = helper.getLevel().getServer();
        boolean originalKeep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        try {
            for (boolean keep : new boolean[]{false, true}) for (boolean worn : new boolean[]{false, true}) {
                rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keep, server);
                UUID playerId = UUID.randomUUID(), marker = UUID.randomUUID();
                var player = TestPlayers.real(helper, playerId);
                var pendant = (personal ? FmpRegistries.PERSONAL_PENDANT : FmpRegistries.PENDANT).toStack();
                pendant.set(FmpRegistries.NETWORK.get(), marker); TestPlayers.necklace(player).setStackInSlot(0, pendant);
                if (personal) {
                    LegacyOwnership.on(CacheLedger.get(server)).personalOrCreate(playerId);
                    LegacyRetirement.transfer(player, simulatedCheckpoints());
                }
                var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(server); var initial = ledger.find(handle.cacheId());
                var edit = initial.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
                edit.filter(0, key); edit.thresholds(0, 2, -1);
                UUID request = UUID.randomUUID(); edit.request(0, request, playerId, 64, false); edit.insert(0, key, 120);
                ledger.replace(handle, initial.state().revision(), initial.withState(edit.finish()));
                var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, 32))); String address = SupplyService.address(player);
                PackageItem.addAddress(box, address);
                ParcelAuthentication.apply(ledger, player.registryAccess(), box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), playerId,
                        request, key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
                helper.assertTrue(ReceiveService.receive(player, box), "Death fixture could not receive its parcel"); ledger.setCacheFirst(playerId, true);
                var before = row(ledger, handle.cacheId(), player.registryAccess());
                for (int cycle = 0; cycle < 2; cycle++) {
                    if (!worn) {
                        player.getInventory().setItem(5, TestPlayers.necklace(player).getStackInSlot(0)); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
                    }
                    player.setHealth(0); player.die(player.damageSources().generic());
                    helper.assertTrue(!AccessGate.resolve(player).active(), "Dead player retained cache authority");
                    helper.assertTrue(row(ledger, handle.cacheId(), player.registryAccess()).equals(before), "Death changed stock, request, residual or grade");
                    long dropped = DeathRecovery.loot(player).stream().filter(item -> marker.equals(item.get(FmpRegistries.NETWORK.get())))
                            .mapToLong(ItemStack::getCount).sum();
                    helper.assertTrue(dropped == (keep ? 0 : 1), "Native death duplicated or lost the pendant in drops/grave");
                    var revived = DeathRecovery.respawn(helper, player);
                    if (!keep) {
                        helper.assertTrue(!AccessGate.resolve(revived).active(), "Death without keepInventory recreated a worn pendant");
                        DeathRecovery.recover(revived);
                        for (var entity : DeathRecovery.drops(revived)) if (marker.equals(entity.getItem().get(FmpRegistries.NETWORK.get()))) {
                            entity.setNoPickUpDelay(); entity.playerTouch(revived);
                            helper.assertTrue(entity.isRemoved(), "Real pendant pickup was not consumed");
                        }
                    }
                    if ((!keep || !worn) && !marker.equals(TestPlayers.necklace(revived).getStackInSlot(0).get(FmpRegistries.NETWORK.get()))) {
                        int recoveredSlot = -1;
                        for (int index = 0; index < 36; index++) if (marker.equals(revived.getInventory().getItem(index).get(FmpRegistries.NETWORK.get()))) recoveredSlot = index;
                        helper.assertTrue(recoveredSlot >= 0, "Pendant did not remain in native inventory or reach it by pickup");
                        var recovered = revived.getInventory().getItem(recoveredSlot); revived.getInventory().setItem(recoveredSlot, ItemStack.EMPTY);
                        TestPlayers.necklace(revived).setStackInSlot(0, recovered);
                    }
                    var access = AccessGate.resolve(revived);
                    helper.assertTrue(access.active() && access.handle().cacheId().equals(handle.cacheId()), "Reequipped pendant selected another cache after death");
                    helper.assertTrue(row(ledger, handle.cacheId(), revived.registryAccess()).equals(before) && !ledger.cacheFirst(playerId), "Native clone changed persistent cache or fixed priority");
                    player = revived;
                }
            }
        } finally { rules.getRule(GameRules.RULE_KEEPINVENTORY).set(originalKeep, server); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void currentModelPersistsInRealPlayerFilesAcrossJvm(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var registries = helper.getLevel().registryAccess();
        var ledger = CacheLedger.get(server);
        var probe = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PersistenceProbe::new, PersistenceProbe::load), "fmp_reference_current_model_probe");
        boolean prior = !probe.data.isEmpty();
        helper.assertTrue(prior || !Boolean.getBoolean("fmp.requirePriorPersistence"), "Prior JVM persistence evidence is required but missing");
        if (prior) { int probeSchema = probe.data.getInt("schema");
            helper.assertTrue(!PROCESS.equals(probe.data.getUUID("writer")) && probeSchema >= 2 && probeSchema <= CacheLedger.SCHEMA,
                "Persistence evidence came from this JVM or another data model"); }
        var ordinary = TestPlayers.create(helper, ORDINARY_PLAYER, FmpRegistries.PENDANT.toStack());
        var personalStack = FmpRegistries.PERSONAL_PENDANT.toStack(); personalStack.set(FmpRegistries.OWNER.get(), PERSONAL_PLAYER);
        var personal = TestPlayers.create(helper, PERSONAL_PLAYER, personalStack);
        if (!prior) {
            LegacyOwnership.on(ledger).personalOrCreate(PERSONAL_PLAYER);
            LegacyRetirement.transfer(personal, simulatedCheckpoints());
        }
        var players = List.of(ordinary, personal);
        if (prior) for (int index = 0; index < players.size(); index++) {
            var player = players.get(index);
            helper.assertTrue(server.getPlayerList().load(player).isPresent(), "Native player-file load failed");
            var access = AccessGate.resolve(player); UUID expectedId = probe.data.getUUID("cache_" + index);
            helper.assertTrue(access.active() && access.handle().cacheId().equals(expectedId), "Curios cache identity did not survive native player-file load");
            var expected = probe.data.getCompound("record_" + index).copy();
            if (probe.data.getInt("schema") == 31001 && !expected.contains("capacity_rules")) {
                // Approved additive schema change only. All original fields still compare exactly.
                var capacity = dev.scathiard.feedmepackages.domain.CacheCapacity.defaults(expected.getInt("level"));
                var added = new CompoundTag(); added.putInt("version",1); added.putInt("slots",capacity.slots()); added.putInt("groups",capacity.groups());
                added.put("retained",new ListTag()); added.put("requested",new ListTag()); expected.put("capacity_rules",added);
            }
            helper.assertTrue(row(ledger, expectedId, registries).equals(expected), "World cache state changed across JVM outside approved additive capacity fields");
            helper.assertTrue(!ledger.cacheFirst(player.getUUID()), "Prior saved preference reactivated after JVM restart");
            helper.assertTrue(player.getInventory().getItem(12).is(Items.DIAMOND) && player.getInventory().getItem(12).getCount() == 13,
                    "Real vanilla player inventory was not restored");
            var residuals = ledger.find(expectedId).residuals();
            var residual = residuals.values().stream().findFirst().orElse(ItemStack.EMPTY);
            helper.assertTrue(!residual.isEmpty() && ParcelAuthentication.valid(ledger, registries, residual, residual.get(FmpRegistries.PARCEL_SEAL.get())),
                    "Real saved residual or world authentication state did not survive JVM");
        }
        var next = new CompoundTag(); next.putUUID("writer", PROCESS); next.putInt("schema", CacheLedger.SCHEMA);
        for (int index = 0; index < players.size(); index++) {
            var player = players.get(index); var handle = AccessGate.resolve(player).handle();
            var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
            for (int level = before.state().level(); level < (index == 0 ? 2 : 5); level++) edit.upgrade();
            edit.reset(0); edit.extract(0, Integer.MAX_VALUE);
            var sample = new ItemStack(Items.IRON_INGOT); sample.set(DataComponents.CUSTOM_NAME, Component.literal("Persistent exact component " + index));
            var key = ItemVariantKey.of(sample, registries); edit.filter(0, key);
            int capacity = CacheLevel.of(index == 0 ? 2 : 5).capacity(); int groupCapacity = CacheLevel.of(index == 0 ? 2 : 5).groupCapacity(); edit.thresholds(0, groupCapacity, -1);
            UUID order = UUID.randomUUID(); edit.request(0, order, player.getUUID(), 64, false); edit.insert(0, key, groupCapacity * key.stackSize() - 16);
            ledger.replace(handle, before.state().revision(), new CacheRecord(edit.finish(), before.owner(), Map.of()));
            var box = PackageItem.containing(List.of(key.stack(registries, 32))); String address = SupplyService.address(player);
            PackageItem.addAddress(box, address);
            ParcelAuthentication.apply(ledger, registries, box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(), order,
                    key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
            helper.assertTrue(ReceiveService.receive(player, box), "Persistence fixture failed to form its real residual");
            var full = ledger.find(handle.cacheId()); var take = full.state().edit(); take.extract(0, 5);
            ledger.replace(handle, full.state().revision(), full.withState(take.finish()));
            ledger.setCacheFirst(player.getUUID(), index == 1); player.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 13));
            saveNativePlayer(player);
            next.putUUID("cache_" + index, handle.cacheId()); next.put("record_" + index, row(ledger, handle.cacheId(), registries));
        }
        probe.data = next; probe.setDirty();
        FeedMePackages.LOGGER.info("FMP_CURRENT_PERSISTENCE {} schema={} ordinary+retired exact-state/player-files", prior ? "PRIOR_JVM_PASSED" : "FIRST_WRITE", CacheLedger.SCHEMA);
        helper.succeed();
    }

    static CompoundTag row(CacheLedger ledger, UUID id, HolderLookup.Provider registries) {
        for (var entry : ledger.save(new CompoundTag(), registries).getList("caches", Tag.TAG_COMPOUND))
            if (((CompoundTag)entry).getUUID("id").equals(id)) return ((CompoundTag)entry).copy();
        throw new IllegalStateException("Persistence record is missing");
    }
    static void saveNativePlayer(ServerPlayer player) {
        try {
            // Only the test fixture needs access to this protected vanilla save entry.
            var method = PlayerList.class.getDeclaredMethod("save", ServerPlayer.class); method.setAccessible(true);
            method.invoke(player.getServer().getPlayerList(), player);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native fixture player save failed", failure); }
    }
    private static final class PersistenceProbe extends SavedData {
        private CompoundTag data = new CompoundTag();
        private static PersistenceProbe load(CompoundTag tag, HolderLookup.Provider registries) {
            var probe = new PersistenceProbe(); probe.data = tag.copy(); return probe;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return data.copy(); }
    }
}
