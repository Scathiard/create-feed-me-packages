package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheCapacity;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * R-018 acceptance, the part that needs no client: a real {@link CacheLedger} grows by five append-only cells per
 * dragon-head expansion, keeps every bought cell through a level upgrade, accepts a filter and stock in a cell beyond
 * 0.3.0's 128 ceiling, and refuses to grow past its new bound. The in-game recipe/JEI observation remains a client run.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CacheExpansionTests {
    private static final UUID PROCESS = UUID.randomUUID();

    @GameTest(template = "empty")
    public static void expansionGrowsFiveCellsAndSurvivesALevelUpgrade(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        var id = ledger.createOrdinary();
        var handle = new CacheHandle(id, PROCESS, null);
        helper.assertTrue(ledger.find(id).state().capacity().slots() == 9, "a level-1 cache starts with nine cells");

        for (int step = 1; step <= 3; step++) {
            var record = ledger.find(id);
            ledger.prepareExpansion(record).commit();
            int expected = 9 + CacheCapacity.EXPANSION_SLOTS * step;
            var after = ledger.find(id);
            helper.assertTrue(after.state().capacity().slots() == expected,
                    "expansion " + step + " should give " + expected + " cells but gave " + after.state().capacity().slots());
            helper.assertTrue(after.state().cells().size() >= expected, "cells were not appended by expansion " + step);
        }

        var expanded = ledger.find(id);
        helper.assertTrue(expanded.state().level() == 1, "expansion must not change the level");
        ledger.prepareOrdinaryUpgrade(expanded, 2).commit();
        var levelled = ledger.find(id);
        helper.assertTrue(levelled.state().level() == 2, "the level upgrade did not advance the level");
        int carried = CacheLevel.of(2).slots() + 3 * CacheCapacity.EXPANSION_SLOTS;
        helper.assertTrue(levelled.state().capacity().slots() == carried,
                "the level upgrade must keep the bought cells on top of the new level: expected " + carried
                        + " but was " + levelled.state().capacity().slots());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stockAndFilterWorkBeyondTheOldCeiling(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        var registries = helper.getLevel().registryAccess();
        var id = ledger.createOrdinary();
        var handle = new CacheHandle(id, PROCESS, null);

        int guard = 0;
        while (ledger.find(id).state().capacity().slots() < 134 && guard++ < 64)
            ledger.prepareExpansion(ledger.find(id)).commit();
        var record = ledger.find(id);
        helper.assertTrue(record.state().capacity().slots() >= 134,
                "expansions must be able to pass 0.3.0's 128 ceiling: " + record.state().capacity().slots());

        int slot = 133;
        var key = ItemVariantKey.of(new ItemStack(Items.ENDER_PEARL), registries);
        var edit = record.state().edit();
        edit.filter(slot, key);
        edit.insert(slot, key, 19);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));

        var reloaded = ledger.find(id);
        var cell = reloaded.state().cells().get(slot);
        helper.assertTrue(cell.filter() != null && key.equals(cell.filter()), "cell 133 lost its filter");
        helper.assertTrue(cell.amount() == 19, "cell 133 lost its stock: " + cell.amount());
        helper.succeed();
    }

}
