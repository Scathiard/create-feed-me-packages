package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.ReturnEscrow;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * R-019 acceptance for the risky half: a combined handoff debits every cell exactly once, and a line that no longer
 * matches its cell aborts the whole thing without debiting anything (all-or-nothing, no partial stock loss).
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReturnEscrowCombinedTests {
    private static final UUID PROCESS = UUID.randomUUID();

    @GameTest(template = "empty")
    public static void aCombinedHandoffDebitsEveryLineOnce(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        var registries = helper.getLevel().registryAccess();
        var id = ledger.createOrdinary();
        var handle = new CacheHandle(id, PROCESS, null);
        var record = ledger.find(id);
        var iron = ItemVariantKey.of(new ItemStack(Items.IRON_INGOT), registries);
        var copper = ItemVariantKey.of(new ItemStack(Items.COPPER_INGOT), registries);
        var redstone = ItemVariantKey.of(new ItemStack(Items.REDSTONE), registries);
        var edit = record.state().edit();
        edit.filter(0, iron); edit.insert(0, iron, 2 * 64);
        edit.filter(1, copper); edit.insert(1, copper, 2 * 64);
        edit.filter(2, redstone); edit.insert(2, redstone, 2 * 64);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));

        var current = ledger.find(id);
        var extras = List.of(new ReturnEscrow.Line(1, copper, 2 * 64), new ReturnEscrow.Line(2, redstone, 2 * 64));
        var hold = new ReturnEscrow(UUID.randomUUID(), handle, current.state().revision(), 0, iron, 2 * 64,
                new ItemStack(Items.PAPER), 0, -1, "paper", "combined", false, null, extras);
        ledger.beginReturn(hold, current, registries);
        ledger.finishReturn(hold, registries);

        var after = ledger.find(id);
        helper.assertTrue(after.state().cells().get(0).amount() == 0, "iron was not debited: " + after.state().cells().get(0).amount());
        helper.assertTrue(after.state().cells().get(1).amount() == 0, "copper was not debited: " + after.state().cells().get(1).amount());
        helper.assertTrue(after.state().cells().get(2).amount() == 0, "redstone was not debited: " + after.state().cells().get(2).amount());
        helper.assertTrue(ledger.returnEscrow(id) == null, "the settled handoff must leave no escrow behind");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStaleLineAbortsBeforeAnythingIsDebited(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        var registries = helper.getLevel().registryAccess();
        var id = ledger.createOrdinary();
        var handle = new CacheHandle(id, PROCESS, null);
        var record = ledger.find(id);
        var iron = ItemVariantKey.of(new ItemStack(Items.IRON_INGOT), registries);
        var copper = ItemVariantKey.of(new ItemStack(Items.COPPER_INGOT), registries);
        var edit = record.state().edit();
        edit.filter(0, iron); edit.insert(0, iron, 2 * 64);
        edit.filter(1, copper); edit.insert(1, copper, 2 * 64);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        var current = ledger.find(id);

        // The second line names a variant cell 1 does not hold: the whole handoff must be refused, nothing debited.
        var hold = new ReturnEscrow(UUID.randomUUID(), handle, current.state().revision(), 0, iron, 2 * 64,
                new ItemStack(Items.PAPER), 0, -1, "paper", "combined", false, null,
                List.of(new ReturnEscrow.Line(1, iron, 2 * 64)));
        boolean refused = false;
        try {
            ledger.beginReturn(hold, current, registries);
        } catch (IllegalStateException | IllegalArgumentException expected) {
            refused = true;
        }
        var after = ledger.find(id);
        helper.assertTrue(refused, "a stale combined line must be refused");
        helper.assertTrue(after.state().cells().get(0).amount() == 2 * 64 && after.state().cells().get(1).amount() == 2 * 64,
                "a refused combined handoff must debit nothing");
        helper.assertTrue(ledger.returnEscrow(id) == null, "a refused handoff must not stay registered");
        helper.succeed();
    }
}
