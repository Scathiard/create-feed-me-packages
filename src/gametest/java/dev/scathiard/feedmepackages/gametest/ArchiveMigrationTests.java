package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.LegacyRetirement;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs alone against a complete copy of the archived schema-8 world. Never loads a user world. */
@GameTestHolder("fmp_legacy_migration")
@PrefixGameTestTemplate(false)
public final class ArchiveMigrationTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void wholeArchivedWorldPreservesEveryRecordAndHandsOverOnce(GameTestHelper h) throws Exception {
        var server=h.getLevel().getServer();var registries=h.getLevel().registryAccess();
        Path backup=Path.of(System.getProperty("fmp.legacyBackup"));
        Path world=server.getWorldPath(LevelResource.ROOT);
        h.assertTrue(!world.toRealPath().equals(backup.toRealPath())
            && world.toRealPath().startsWith(backup.getParent().resolve("run").toRealPath())
            && world.toRealPath().toString().contains("legacy023"),"Expected isolated legacy world copy");
        var original=read(backup.resolve("data").resolve(CacheLedger.NAME+".dat")).getCompound("data");
        h.assertTrue(original.getInt("schema")==8,"Archived world is not the released schema");
        checkOriginalJar(original,registries,h);
        var ledger=CacheLedger.get(server);h.assertTrue(ledger.problem().isEmpty(),"Archived ledger was locked: "+ledger.problem());
        CompoundTag chosen=null;
        for(Tag tag:original.getList("caches",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)tag;
            if(row.hasUUID("owner") && Files.isRegularFile(world.resolve("playerdata").resolve(row.getUUID("owner")+".dat"))) {chosen=row;break;}
        }
        h.assertTrue(chosen!=null,"Archived world lacks a saved original owner");
        UUID owner=chosen.getUUID("owner"),id=chosen.getUUID("id");
        var prior=ledger.retirement(owner);
        h.assertTrue(!Boolean.getBoolean("fmp.requirePriorPersistence") || prior!=null && prior.completed(),"Expected prior-process completed migration");
        var expected=original.copy();expected.putInt("schema",CacheLedger.SCHEMA);var completed=new ListTag();
        if(prior!=null) {
            h.assertTrue(prior.completed() && prior.cache().equals(id),"Prior migration identity changed");
            var record=new CompoundTag();record.putUUID("owner",owner);record.putUUID("cache",id);
            record.putUUID("receipt",prior.receipt());record.putBoolean("completed",true);completed.add(record);
        }
        expected.put("private_retirements",completed);
        long total=0;int orders=0,residuals=0;
        for(Tag tag:expected.getList("caches",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)tag;orders+=row.getList("orders",Tag.TAG_COMPOUND).size();residuals+=row.getList("residuals",Tag.TAG_COMPOUND).size();
            for(Tag cellTag:row.getList("cells",Tag.TAG_COMPOUND)) {
                var cell=(CompoundTag)cellTag;total+=cell.getInt("amount");
                int size=cell.contains("filter")?ItemVariantKey.decode(cell.getString("filter"),registries).stackSize():1;
                var settings=new CompoundTag();int low=cell.getInt("min"),high=cell.getInt("max");
                settings.putInt("lower",low<0?-1:Math.multiplyExact(low,size));settings.putInt("upper",high<0?-1:Math.multiplyExact(high,size));
                settings.putInt("batch",1);settings.putInt("pending_limit",-1);cell.put("item_controls",settings);
            }
        }
        h.assertTrue(normalize(expected).equals(normalize(ledger.save(new CompoundTag(),registries))),
            "Full archived ledger changed beyond the approved control conversion");
        var player=TestPlayers.real(h,owner);
        h.assertTrue(server.getPlayerList().load(player).isPresent(),"Original owner did not load from the copied player file");
        var before=ledger.find(id);TestPlayers.nativeSavePlayers(server).add(player);
        try {player.tickCount=20;LegacyRetirement.tick(player);}
        finally {TestPlayers.nativeSavePlayers(server).remove(player);}
        h.assertTrue(ledger.retirement(owner)!=null && ledger.retirement(owner).completed(),"Archived owner's actual delivery did not reach disk");
        h.assertTrue(prior==null || prior.equals(ledger.retirement(owner)),"Restart issued a replacement migration receipt");
        h.assertTrue(ledger.find(id).owner()==null && before.state().equals(ledger.find(id).state()),"Handover changed cache identity, level, stock, settings or orders");
        var once=ledger.save(new CompoundTag(),registries);LegacyRetirement.transfer(player,FoundationTests.simulatedCheckpoints());
        h.assertTrue(normalize(once).equals(normalize(ledger.save(new CompoundTag(),registries))),"Repeated migration changed the ledger");
        var onDisk=CacheLedger.load(read(world.resolve("data").resolve(CacheLedger.NAME+".dat")).getCompound("data"),registries);
        h.assertTrue(onDisk.problem().isEmpty() && onDisk.retirement(owner).completed() && onDisk.find(id).state().equals(before.state()),"Actual migrated ledger did not reload intact");
        var restored=TestPlayers.create(h,owner,net.minecraft.world.item.ItemStack.EMPTY);
        h.assertTrue(server.getPlayerList().load(restored).isPresent(),"Converted owner's saved player file did not reload");
        boolean credential=false;
        for(int i=0;i<restored.getInventory().getContainerSize();i++) {
            var s=restored.getInventory().getItem(i);
            credential|=s.is(FmpRegistries.PENDANT.get()) && id.equals(s.get(FmpRegistries.IDENTITY.get()))
                && ledger.retirement(owner).receipt().equals(s.get(FmpRegistries.RETIREMENT_RECEIPT.get()));
        }
        var worn=TestPlayers.necklace(restored).getStackInSlot(0);
        credential|=worn.is(FmpRegistries.PENDANT.get()) && id.equals(worn.get(FmpRegistries.IDENTITY.get()))
            && ledger.retirement(owner).receipt().equals(worn.get(FmpRegistries.RETIREMENT_RECEIPT.get()));
        h.assertTrue(credential,"Original owner did not persist the unique ordinary pendant");
        h.assertTrue(read(backup.resolve("data").resolve(CacheLedger.NAME+".dat")).getCompound("data").equals(original),"Backup was modified");
        FeedMePackages.LOGGER.info("FMP_ARCHIVED_WORLD_PASSED {} schema=8->{} caches={} stock={} orders={} residuals={} original-jar-reader/full-ledger/native-owner/once/disk-reload",
            prior==null?"FIRST_TRANSFER":"PRIOR_JVM_PASSED",CacheLedger.SCHEMA,original.getList("caches",Tag.TAG_COMPOUND).size(),total,orders,residuals);
        h.succeed();
    }

    /** Load the released reader, not a reimplementation, against the untouched backup. */
    private static void checkOriginalJar(CompoundTag original,HolderLookup.Provider registries,GameTestHelper h) throws Exception {
        Path jar=Path.of(System.getProperty("fmp.legacyJar"));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        h.assertTrue(hash.equals("d40766daf9d4652163e2a94fa9e467c93bc5e60f8d8efa90ccc883bcc23b8b2b"),"Not the verified released 0.2.3 artifact");
        try(var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ArchiveMigrationTests.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("dev.scathiard.feedmepackages.domain.") || name.startsWith("dev.scathiard.feedmepackages.storage.")
                        || name.startsWith("dev.scathiard.feedmepackages.item.ItemVariantKey")) {
                    synchronized(getClassLoadingLock(name)) {var c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;}
                }
                return super.loadClass(name,resolve);
            }
        }) {
            var type=loader.loadClass("dev.scathiard.feedmepackages.storage.CacheLedger");
            var old=type.getMethod("load",CompoundTag.class,HolderLookup.Provider.class).invoke(null,original.copy(),registries);
            h.assertTrue(((String)type.getMethod("problem").invoke(old)).isEmpty(),"Released 0.2.3 cannot read the untouched backup");
            var restored=(CompoundTag)type.getMethod("save",CompoundTag.class,HolderLookup.Provider.class).invoke(old,new CompoundTag(),registries);
            h.assertTrue(normalize(original).equals(normalize(restored)),"Released reader changed backup stock or metadata");
        }
    }

    private static CompoundTag read(Path path) throws java.io.IOException {return NbtIo.readCompressed(path,NbtAccounter.create(64L*1024*1024));}
    /** Sort set-like record lists only. Cell ordering and all nested item components remain exact. */
    private static CompoundTag normalize(CompoundTag input) {
        var tag=input.copy();
        for(Tag rowTag:tag.getList("caches",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)rowTag;
            for(String key:List.of("orders","residuals"))if(row.contains(key))row.put(key,sorted(row.getList(key,Tag.TAG_COMPOUND)));
        }
        for(Tag rowTag:tag.getList("redirects",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)rowTag;row.put("variants",sorted(row.getList("variants",Tag.TAG_COMPOUND)));
        }
        for(String key:List.of("caches","preferences","redirects","returnAddresses","private_retirements"))
            if(tag.contains(key))tag.put(key,sorted(tag.getList(key,Tag.TAG_COMPOUND)));
        return tag;
    }
    private static ListTag sorted(ListTag input) {
        var entries=new ArrayList<Tag>();input.forEach(entries::add);entries.sort(Comparator.comparing(Tag::toString));
        var result=new ListTag();entries.forEach(result::add);return result;
    }
}
