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

    /** The specification's own packing examples, decided by the same helpers the dispatch uses. */
    @GameTest(template = "empty")
    public static void thePackingRulesFollowTheSpecification(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var iron = ItemVariantKey.of(new ItemStack(Items.IRON_INGOT), registries);
        var copper = ItemVariantKey.of(new ItemStack(Items.COPPER_INGOT), registries);
        var redstone = ItemVariantKey.of(new ItemStack(Items.REDSTONE), registries);
        var gold = ItemVariantKey.of(new ItemStack(Items.GOLD_INGOT), registries);

        // 4 iron, 3 copper and 2 redstone: one nine-slot package, in scan order, one line each.
        var variants = new java.util.HashMap<Integer, ItemVariantKey>();
        variants.put(0, iron); variants.put(1, copper); variants.put(2, redstone);
        var nine = dev.scathiard.feedmepackages.logistics.ReturnService.keptLines(List.of(
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(0, 4 * 64, 64),
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(1, 3 * 64, 64),
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(2, 2 * 64, 64)), variants);
        helper.assertTrue(nine.size() == 3 && nine.get(0).amount() == 4 * 64 && nine.get(1).amount() == 3 * 64
                && nine.get(2).amount() == 2 * 64, "the three items must ship together: " + nine);
        var box = dev.scathiard.feedmepackages.logistics.ReturnService.planPackage(nine, registries);
        helper.assertTrue(box != null && com.simibubi.create.content.logistics.box.PackageItem.isPackage(box.box()),
                "a combined package must be a real Create package");

        // Gold only 63 over with a minimum of 64 stays where it is.
        var stale = dev.scathiard.feedmepackages.logistics.ReturnService.keptLines(List.of(
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(3, 63, 64)), java.util.Map.of(3, gold));
        helper.assertTrue(stale.isEmpty(), "a cell below its own minimum must not join");

        // Nine iron plus one copper: the package keeps nine stacks, the copper waits for the next dispatch.
        var overflow = dev.scathiard.feedmepackages.logistics.ReturnService.keptLines(List.of(
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(0, 9 * 64, 64),
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(1, 64, 64)), variants);
        helper.assertTrue(overflow.size() == 1 && overflow.getFirst().slot() == 0
                && overflow.getFirst().amount() == 9 * 64, "nine stacks fill the package: " + overflow);

        // Copper first: it leads the package, then the iron fills the rest.
        var copperFirst = dev.scathiard.feedmepackages.logistics.ReturnService.keptLines(List.of(
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(1, 64, 64),
                new dev.scathiard.feedmepackages.domain.ReturnPlan.Eligible(0, 9 * 64, 64)), variants);
        helper.assertTrue(copperFirst.getFirst().slot() == 1 && copperFirst.size() == 2
                && copperFirst.get(1).amount() == 8 * 64, "copper must lead and iron follow: " + copperFirst);
        helper.succeed();
    }
}
