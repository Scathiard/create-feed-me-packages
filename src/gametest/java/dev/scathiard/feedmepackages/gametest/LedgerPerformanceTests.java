package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Opt-in microbenchmark of real ledger writes and NBT construction, not a full-server TPS claim. */
@GameTestHolder("fmp_ledger_performance") @PrefixGameTestTemplate(false)
public final class LedgerPerformanceTests {
    @GameTest(template="empty",timeoutTicks=2000)
    public static void historicalCacheScale(GameTestHelper h) {
        var registry=h.getLevel().registryAccess();var seed=new CacheLedger();var seedId=LedgerIsolationTests.seed(seed,h,37);
        var template=seed.save(new CompoundTag(),registry);var row=LedgerIsolationTests.row(template,seedId);
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        FeedMePackages.LOGGER.info("FMP_LEDGER_PERF_ENV phase={} java={} processors={} maxHeap={} active=4 writesPerSample=4 retired=50 residuals=0 warmup=100 samples=400 saveSamples=30",
                System.getProperty("fmp.ledgerBenchmark"),System.getProperty("java.version"),Runtime.getRuntime().availableProcessors(),Runtime.getRuntime().maxMemory());
        for(int n:new int[]{100,1000,10000}) {
            var tag=template.copy();var rows=new ListTag();var retired=new ListTag();var active=new ArrayList<UUID>();
            for(int i=0;i<n;i++) {
                var id=new UUID(700,i+1);var r=row.copy();r.putUUID("id",id);rows.add(r);
                if(i<4)active.add(id);
                if(i>=n-50){var owner=new UUID(701,i+1);r.putUUID("owner",owner);var receipt=new CompoundTag();receipt.putUUID("owner",owner);
                    receipt.putUUID("cache",id);receipt.putUUID("receipt",new UUID(702,i+1));receipt.putBoolean("completed",true);retired.add(receipt);}
            }
            tag.put("caches",rows);tag.put("private_retirements",retired);
            long loadStart=System.nanoTime();var ledger=CacheLedger.load(tag,registry);long load=System.nanoTime()-loadStart;
            h.assertTrue(ledger.problem().isEmpty() && active.stream().allMatch(id->ledger.find(id)!=null),"Benchmark fixture did not load");
            for(int i=0;i<100;i++)write(ledger,active);
            long[] writes=new long[400],allocations=new long[400],saves=new long[30],saveAlloc=new long[30];
            long gcCount=gc(false),gcMillis=gc(true);
            for(int i=0;i<writes.length;i++) {
                long a=allocation.getThreadAllocatedBytes(thread),t=System.nanoTime();write(ledger,active);writes[i]=System.nanoTime()-t;allocations[i]=allocation.getThreadAllocatedBytes(thread)-a;
            }
            for(int i=0;i<saves.length;i++) {
                long a=allocation.getThreadAllocatedBytes(thread),t=System.nanoTime();var snapshot=ledger.save(new CompoundTag(),registry);saves[i]=System.nanoTime()-t;saveAlloc[i]=allocation.getThreadAllocatedBytes(thread)-a;
                h.assertTrue(snapshot.getList("caches",Tag.TAG_COMPOUND).size()==n,"Saving dropped historical stock");
            }
            var last=ledger.save(new CompoundTag(),registry);var restored=CacheLedger.load(last,registry);
            h.assertTrue(restored.problem().isEmpty() && LedgerIsolationTests.sameRows(last,restored.save(new CompoundTag(),registry)),"Scale reload changed ledger");
            for(UUID id:active)h.assertTrue(restored.find(id).state().cells().getFirst().amount()==37,"Active writes lost stock");
            FeedMePackages.LOGGER.info("FMP_LEDGER_PERF phase={} caches={} loadMs={} writesMs={} allocatedBytes={} saveNbtMs={} saveAllocatedBytes={} gcCount={} gcMillis={}",
                    System.getProperty("fmp.ledgerBenchmark"),n,load/1e6,percentiles(writes,1e6),percentiles(allocations,1),percentiles(saves,1e6),percentiles(saveAlloc,1),gc(false)-gcCount,gc(true)-gcMillis);
        }
        h.succeed();
    }
    private static void write(CacheLedger ledger,List<UUID> ids){for(UUID id:ids){
        var r=ledger.find(id);var e=r.state().edit();var key=r.state().cells().getFirst().filter();e.extract(0,1);e.insert(0,key,1);
        if(!ledger.ordinaryEntry(id,null))throw new AssertionError("Active ordinary entry unavailable");
        ledger.replace(new CacheHandle(id,new UUID(703,1),null),r.state().revision(),r.withState(e.finish()));
    }}
    private static String percentiles(long[] values,double divisor){var v=values.clone();Arrays.sort(v);return String.format(Locale.ROOT,"p50=%.3f,p95=%.3f,p99=%.3f",v[(int)Math.ceil(v.length*.50)-1]/divisor,v[(int)Math.ceil(v.length*.95)-1]/divisor,v[(int)Math.ceil(v.length*.99)-1]/divisor);}
    private static long gc(boolean time){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,time?b.getCollectionTime():b.getCollectionCount())).sum();}
}
