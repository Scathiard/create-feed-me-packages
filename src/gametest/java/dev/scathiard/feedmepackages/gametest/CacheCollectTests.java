package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CacheActions.Action;
import dev.scathiard.feedmepackages.interaction.CacheActions.Result;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The one-key collect button (user request: "一键把背包物品收进对应缓存格"). This drives the REAL ledger and the
 * REAL server action: a real player inventory, the real typed ledger, and the very {@code COLLECT_MATCHING}
 * intent the panel sends.
 *
 * <p>The mouse click itself is not exercised (game tests have no client); what is pinned is the decision the
 * click triggers - each target cell's amount, every other cell untouched, the inventory debited by exactly what
 * moved, the total conserved - plus the "nothing moves" paths.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CacheCollectTests {

    /** Give one cell a filter and an amount, through the ordinary private edit + single publish. */
    private static int seed(ServerPlayer player, ItemStack prototype, int amount) {
        var ledger = CacheLedger.get(player.getServer());
        var access = AccessGate.resolve(player);
        var record = ledger.find(access.handle().cacheId());
        var key = ItemVariantKey.of(prototype, player.registryAccess());
        int slot = record.state().find(key);
        if (slot < 0) for (int index = 0; index < record.state().cells().size(); index++)
            if (record.state().cells().get(index).filter() == null) { slot = index; break; }
        var edit = record.state().edit();
        edit.filter(slot, key);
        edit.insert(slot, key, amount);
        ledger.replace(access.handle(), record.state().revision(), record.withState(edit.finish()));
        return slot;
    }

    private static void carry(ServerPlayer player, int slot, ItemStack stack) {
        player.getInventory().setItem(slot, stack);
    }

    private static Result collect(ServerPlayer player) {
        var view = CacheActions.open(player);
        return CacheActions.execute(player, new CacheActions.Intent(view.session(),
                view.record() == null ? -1 : view.record().state().revision(), Action.COLLECT_MATCHING, -1, 0, -1, ""));
    }

    private static int carried(ServerPlayer player, ItemStack prototype) {
        var key = ItemVariantKey.of(prototype, player.registryAccess());
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemVariantKey.of(stack, player.registryAccess()).equals(key)) total += stack.getCount();
        }
        return total;
    }

    private static int cachedTotal(ServerPlayer player) {
        var access = AccessGate.resolve(player);
        int total = 0;
        for (var cell : CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells())
            total += cell.amount();
        return total;
    }

    private static int cellOf(ServerPlayer player, ItemStack prototype) {
        var access = AccessGate.resolve(player);
        return CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state()
                .find(ItemVariantKey.of(prototype, player.registryAccess()));
    }

    @GameTest(template = "empty")
    public static void oneClickCollectsEveryMatchingKindAndLeavesTheRestAlone(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var stone = new ItemStack(Items.STONE);
        var iron = new ItemStack(Items.IRON_INGOT);
        int stoneCell = seed(player, stone, 5);
        int ironCell = seed(player, iron, 3);
        carry(player, 0, new ItemStack(Items.STONE, 20));
        carry(player, 1, new ItemStack(Items.IRON_INGOT, 8));
        carry(player, 2, new ItemStack(Items.GRAVEL, 64));   // no cell filters gravel
        int cachedBefore = cachedTotal(player);

        helper.assertTrue(collect(player) == Result.OK, "the collect was refused");

        var cells = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells();
        helper.assertTrue(cells.get(stoneCell).amount() == 25, "stone cell should be 5 + 20, was " + cells.get(stoneCell).amount());
        helper.assertTrue(cells.get(ironCell).amount() == 11, "iron cell should be 3 + 8, was " + cells.get(ironCell).amount());
        helper.assertTrue(carried(player, stone) == 0, "the stone stayed in the inventory");
        helper.assertTrue(carried(player, iron) == 0, "the iron stayed in the inventory");
        helper.assertTrue(player.getInventory().getItem(2).getCount() == 64, "gravel has no cell and must not be touched");
        helper.assertTrue(player.getInventory().getItem(3).isEmpty(), "an untouched slot changed");
        helper.assertTrue(cachedTotal(player) == cachedBefore + 28,
                "28 items moved, the cache gained " + (cachedTotal(player) - cachedBefore));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void whatDoesNotFitStaysInTheInventoryAndNothingIsLost(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var stone = new ItemStack(Items.STONE);
        int cell = seed(player, stone, 3);
        // Level 1 holds two stacks of a 64-stack item = 128 items; 125 free, and we carry 192.
        carry(player, 0, new ItemStack(Items.STONE, 64));
        carry(player, 1, new ItemStack(Items.STONE, 64));
        carry(player, 2, new ItemStack(Items.STONE, 64));
        int cachedBefore = cachedTotal(player);

        helper.assertTrue(collect(player) == Result.OK, "the collect was refused");

        var cells = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells();
        helper.assertTrue(cells.get(cell).amount() == CacheLevel.of(1).groupCapacity() * 64,
                "the cell must stop at its capacity, was " + cells.get(cell).amount());
        helper.assertTrue(carried(player, stone) == 192 - 125, "what did not fit must stay in the inventory");
        helper.assertTrue(cachedTotal(player) == cachedBefore + 125, "the moved amount is wrong");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnmatchedInventoryAndAStaleClickBothChangeNothing(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var stone = new ItemStack(Items.STONE);
        int cell = seed(player, stone, 4);
        carry(player, 0, new ItemStack(Items.DIAMOND, 64));   // no cell filters diamonds

        var ledger = CacheLedger.get(player.getServer());
        java.util.UUID cacheId = AccessGate.resolve(player).handle().cacheId();
        var before = ledger.find(cacheId).state();
        helper.assertTrue(collect(player) == Result.OK, "a collect with nothing to collect is not a failure");
        var afterNothing = ledger.find(cacheId).state();
        helper.assertTrue(before.cells().equals(afterNothing.cells()) && before.revision() == afterNothing.revision(),
                "a collect with no matching cell still changed the cache");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 64, "an unmatched stack was moved");

        // A stale revision - the panel's answer to a cache change it never saw - must change nothing either.
        carry(player, 1, new ItemStack(Items.STONE, 10));
        var view = CacheActions.open(player);
        Result stale = CacheActions.execute(player, new CacheActions.Intent(view.session(),
                ledger.find(cacheId).state().revision() + 1, Action.COLLECT_MATCHING, -1, 0, -1, ""));
        helper.assertTrue(stale == Result.STALE, "a stale collect must be refused, was " + stale);
        helper.assertTrue(ledger.find(cacheId).state().cells().equals(afterNothing.cells()), "a refused collect still moved items");
        helper.assertTrue(player.getInventory().getItem(1).getCount() == 10, "a refused collect emptied the stack");
        helper.assertTrue(ledger.find(cacheId).state().cells().get(cell).amount() == 4, "the seeded cell changed");

        // A cell that is already at its own capacity takes nothing and keeps the stack in the bag. Together with
        // the unmatched case above this is the "zero message" side of the silence rule (user, 2026-09-19): the
        // player is told only when something actually moved, and the report decision is pinned in CollectPlan.
        seed(player, stone, CacheLevel.of(1).groupCapacity() * 64);
        helper.assertTrue(ledger.find(cacheId).state().cells().get(cell).amount() == CacheLevel.of(1).groupCapacity() * 64,
                "fixture could not fill the cell to capacity");
        carry(player, 2, new ItemStack(Items.STONE, 32));
        var beforeFull = ledger.find(cacheId).state();
        helper.assertTrue(collect(player) == Result.OK, "a full cell is not a failure");
        helper.assertTrue(ledger.find(cacheId).state().cells().equals(beforeFull.cells()),
                "a full cell took stock it cannot hold");
        helper.assertTrue(ledger.find(cacheId).state().revision() == beforeFull.revision(),
                "a collect that moved nothing still bumped the revision");
        helper.assertTrue(player.getInventory().getItem(2).getCount() == 32, "the stack did not stay in the bag");
        helper.succeed();
    }
}
