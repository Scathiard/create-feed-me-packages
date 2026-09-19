package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheGrid;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * User report: "升级的时候，原本缓存里放号的物品位置会错位。让加槽位的逻辑变成加行加列吧，而不是顺序顺延".
 * This drives a REAL {@link CacheLedger} — nine seeded cells, a real ordinary upgrade to level 2 — and pins that
 * nothing in an existing cell moves: same cell index, same filter, same amounts, same (row, column).
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CacheUpgradeGridTests {
    private static final UUID PROCESS = UUID.randomUUID();
    private static final Item[] SEED = {Items.STONE, Items.DIRT, Items.GRAVEL, Items.SAND, Items.GLASS,
            Items.COAL, Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT};

    @GameTest(template = "empty")
    public static void anUpgradeKeepsEverySeededCellInPlace(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        var registries = helper.getLevel().registryAccess();
        var id = ledger.createOrdinary();
        var record = ledger.find(id);
        var handle = new CacheHandle(id, PROCESS, null);
        var edit = record.state().edit();
        for (int slot = 0; slot < SEED.length; slot++) {
            var key = ItemVariantKey.of(new ItemStack(SEED[slot]), registries);
            edit.filter(slot, key);
            edit.insert(slot, key, 16 + slot);
        }
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        var before = ledger.find(id);
        int slotsBefore = before.state().cells().size();
        helper.assertTrue(slotsBefore == CacheLevel.of(1).slots() && slotsBefore == SEED.length, "fixture did not fill level 1");

        ledger.prepareOrdinaryUpgrade(before, 2).commit();
        var after = ledger.find(id);

        helper.assertTrue(after.state().level() == 2, "the upgrade did not advance the level");
        helper.assertTrue(after.state().cells().size() == CacheLevel.of(2).slots(),
                "the cell count did not follow the level");
        helper.assertTrue(after.state().cells().size() - slotsBefore == 7,
                "the upgrade did not add exactly the new row and column of the bigger rectangle");
        CacheGrid was = CacheGrid.forLevel(1);
        CacheGrid now = CacheGrid.forLevel(2);
        int changed = 0, moved = 0, emptied = 0;
        long amountBefore = 0, amountAfter = 0;
        for (int slot = 0; slot < slotsBefore; slot++) {
            var oldCell = before.state().cells().get(slot);
            var newCell = after.state().cells().get(slot);
            amountBefore += oldCell.amount();
            amountAfter += newCell.amount();
            if (!Objects.equals(oldCell.filter(), newCell.filter()) || oldCell.amount() != newCell.amount()
                    || oldCell.minimum() != newCell.minimum() || oldCell.maximum() != newCell.maximum()
                    || oldCell.filterRevision() != newCell.filterRevision()) changed++;
            if (was.row(slot) != now.row(slot) || was.column(slot) != now.column(slot)) moved++;
            if (newCell.filter() == null) emptied++;
        }
        helper.assertTrue(changed == 0, "the upgrade changed " + changed + " existing cells");
        helper.assertTrue(moved == 0, "the upgrade moved " + moved + " existing cells to another row/column");
        helper.assertTrue(emptied == 0, "the upgrade emptied " + emptied + " existing cells");
        helper.assertTrue(amountBefore == amountAfter, "items were lost or duplicated across the upgrade");
        for (int slot = slotsBefore; slot < after.state().cells().size(); slot++)
            helper.assertTrue(after.state().cells().get(slot).filter() == null
                    && after.state().cells().get(slot).amount() == 0, "a new cell did not start empty");
        helper.succeed();
    }
}
