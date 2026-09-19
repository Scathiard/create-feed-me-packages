package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheDropTarget;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.domain.Cell;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * "往面板里一丢就行" (user request): a stack dropped anywhere on the panel must land in the one cell that can
 * hold it - the cell already filtering that exact item, else the first empty cell - and nothing else may move.
 *
 * <p><b>The drop that lands on a cell.</b> The panel body is a gapless grid, so a player dropping a stack "on
 * the panel" almost always lands on a cell. The confirmed rule: the aimed cell keeps the drop when it already
 * filters the item or is free while no other cell claims it; otherwise the item goes to its own cell (or to the
 * first empty one), and only a panel with nowhere to put it says so. The rule itself lives in
 * {@link CacheDropTarget#resolveDrop} - the same single source the un-aimed drop uses.
 *
 * <p>This drives the REAL decision plus the REAL action: the target comes from the very snapshot the client
 * panel receives ({@link CacheActions#snapshot}), and the deposit is the ordinary {@code DEPOSIT} the click
 * path sends. The mouse event itself is not exercised here (game tests have no client), which the record
 * states plainly.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CacheDropPanelTests {
    private static final Item[] SEED = {Items.STONE, Items.DIRT, Items.GRAVEL, Items.SAND, Items.GLASS,
            Items.COAL, Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT};

    /** The cells exactly as the panel's snapshot carries them (filter template plus "is it full"). */
    private static List<CacheDropTarget.Cell> cellList(ServerPlayer player) {
        var view = CacheActions.snapshot(player);
        int groupCapacity = Math.max(1, CacheLevel.of(view.record().state().level()).capacity() / 64);
        List<CacheDropTarget.Cell> cells = new ArrayList<>();
        for (var cell : view.record().state().cells()) {
            int stackSize = cell.filter() == null ? 64 : Math.max(1, cell.filter().stackSize());
            cells.add(new CacheDropTarget.Cell(cell.filter() == null ? "" : cell.filter().encoded(),
                    cell.amount() >= groupCapacity * stackSize));
        }
        return cells;
    }

    private static String encoded(ServerPlayer player, ItemStack carried) {
        return carried.isEmpty() ? "" : ItemVariantKey.of(carried, player.registryAccess()).encoded();
    }

    /** A drop that landed on no cell at all (the panel's blank space): the aim-free resolution. */
    private static CacheDropTarget.Target targetFor(ServerPlayer player, ItemStack carried) {
        return CacheDropTarget.resolve(cellList(player), encoded(player, carried));
    }

    /** A drop that landed on {@code aimed}: the rule both click paths now ask. */
    private static CacheDropTarget.Target aimedTargetFor(ServerPlayer player, ItemStack carried, int aimed) {
        return CacheDropTarget.resolveDrop(cellList(player), aimed, encoded(player, carried));
    }

    /** One ordinary DEPOSIT aimed at exactly this cell - what the panel sends once it has routed the drop. */
    private static Result dropAt(ServerPlayer player, ItemStack carried, int slot, int first) {
        player.containerMenu.setCarried(carried.copy());
        if (slot < 0) return null;   // the panel says NO_SPACE out loud instead of sending
        var view = CacheActions.open(player);
        return CacheActions.execute(player, new CacheActions.Intent(view.session(),
                view.record() == null ? -1 : view.record().state().revision(), Action.DEPOSIT, slot, first, -1, ""));
    }

    /** One aim-free drop: resolve the cell the panel would pick, then send the panel's own DEPOSIT there. */
    private static Result drop(ServerPlayer player, ItemStack carried, int first) {
        return dropAt(player, carried, targetFor(player, carried).slot(), first);
    }

    /** The cache's cells as the ledger holds them right now. */
    private static List<Cell<ItemVariantKey>> cellsOf(ServerPlayer player) {
        var access = AccessGate.resolve(player);
        return CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells();
    }

    @GameTest(template = "empty")
    public static void droppingANewItemAnywhereFillsTheFirstEmptyCellAndTouchesNothingElse(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var stone = new ItemStack(Items.STONE);
        var iron = new ItemStack(Items.IRON_INGOT, 7);
        helper.assertTrue(drop(player, stone, 0) == Result.OK, "fixture could not seed cell 0");

        var target = targetFor(player, iron);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.FIRST_EMPTY_CELL && target.slot() == 1,
                "a brand new item must take the first empty cell, got " + target);
        helper.assertTrue(drop(player, iron, 0) == Result.OK, "the drop was refused");

        var access = AccessGate.resolve(player);
        var cells = CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells();
        helper.assertTrue(cells.get(1).filter() != null
                        && cells.get(1).filter().equals(ItemVariantKey.of(iron, player.registryAccess()))
                        && cells.get(1).amount() == 7, "the new item did not land with its filter and count");
        helper.assertTrue(cells.get(0).filter().equals(ItemVariantKey.of(stone, player.registryAccess()))
                        && cells.get(0).amount() == 1, "the seeded cell changed");
        for (int slot = 2; slot < cells.size(); slot++)
            helper.assertTrue(cells.get(slot).filter() == null && cells.get(slot).amount() == 0,
                    "cell " + slot + " was touched by a drop that had nothing to do with it");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "the drop left material on the cursor");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void droppingAKnownItemAnywhereRefillsItsOwnCell(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var stone = new ItemStack(Items.STONE);
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed cell 0");
        helper.assertTrue(drop(player, new ItemStack(Items.DIRT, 3), 0) == Result.OK, "fixture could not seed cell 1");

        var target = targetFor(player, stone);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.EXISTING_CELL && target.slot() == 0,
                "a known item must go back into its own cell, got " + target);
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 4), 0) == Result.OK, "the top-up was refused");

        var access = AccessGate.resolve(player);
        var cells = CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells();
        helper.assertTrue(cells.get(0).amount() == 9, "the known item's cell did not grow: " + cells.get(0).amount());
        helper.assertTrue(cells.get(1).amount() == 3, "the other filtered cell changed");
        for (int slot = 2; slot < cells.size(); slot++)
            helper.assertTrue(cells.get(slot).filter() == null && cells.get(slot).amount() == 0,
                    "cell " + slot + " gained stock from a top-up");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFullPanelWithNoMatchingCellSaysSoInsteadOfDoingNothing(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        for (Item item : SEED) helper.assertTrue(drop(player, new ItemStack(item), 0) == Result.OK,
                "fixture could not fill a cell with " + item);
        var access = AccessGate.resolve(player);
        var before = CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state();

        var stranger = new ItemStack(Items.NETHER_STAR);
        var target = targetFor(player, stranger);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.NO_TARGET && !target.hasTarget(),
                "a full panel with no match must report no target, got " + target);
        helper.assertTrue(drop(player, stranger, 0) == null, "the panel sent a deposit with nowhere to put it");

        var after = CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state();
        helper.assertTrue(before.cells().equals(after.cells()) && before.revision() == after.revision(),
                "a refused drop still changed the cache");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------
    // The confirmed rule for the drop that lands ON a cell. The panel body is a gapless grid, so this is
    // the case every real click hits; each test drives the domain rule plus the server's own answer.
    // ---------------------------------------------------------------------------------------------------

    /** (a) The aimed cell already filters this exact item: it keeps the drop, "I meant this cell" unchanged. */
    @GameTest(template = "empty")
    public static void anAimedCellThatAlreadyFiltersTheItemKeepsTheDrop(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed cell 0");

        var aimed = aimedTargetFor(player, new ItemStack(Items.STONE, 2), 0);
        helper.assertTrue(aimed.outcome() == CacheDropTarget.Outcome.AIMED_MATCH && aimed.slot() == 0,
                "the aimed cell is this item's own cell, got " + aimed);
        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE, 2), aimed.slot(), 0) == Result.OK,
                "the aimed deposit was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(0).amount() == 7, "the aimed cell did not grow: " + cells.get(0).amount());
        for (int slot = 1; slot < cells.size(); slot++)
            helper.assertTrue(cells.get(slot).filter() == null && cells.get(slot).amount() == 0,
                    "cell " + slot + " moved on an aimed top-up");
        helper.succeed();
    }

    /** (b) Aiming at a free cell keeps that cell - it is not pushed to a different free one. */
    @GameTest(template = "empty")
    public static void anAimedFreeCellKeepsTheDropWhenOtherCellsAreFree(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE), 0) == Result.OK, "fixture could not seed cell 0");

        var aimed = aimedTargetFor(player, new ItemStack(Items.DIRT, 4), 5);
        helper.assertTrue(aimed.outcome() == CacheDropTarget.Outcome.AIMED_EMPTY && aimed.slot() == 5,
                "the aimed free cell must stay the target, got " + aimed);
        helper.assertTrue(dropAt(player, new ItemStack(Items.DIRT, 4), aimed.slot(), 0) == Result.OK,
                "the aimed deposit was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(5).amount() == 4 && cells.get(5).filter() != null, "cell 5 did not take it");
        for (int slot = 1; slot < cells.size(); slot++)
            if (slot != 5) helper.assertTrue(cells.get(slot).filter() == null && cells.get(slot).amount() == 0,
                    "cell " + slot + " moved although the player aimed at cell 5");
        helper.succeed();
    }

    /**
     * (c) The aimed cell holds something else while the item has its own cell: the old code sent the DEPOSIT
     * there and the server refused it silently - the exact failure the user reported. Now it goes to the item's
     * own cell, and the server's own refusal is still there for a direct aim at that cell.
     */
    @GameTest(template = "empty")
    public static void anAimedCellThatHoldsAnotherItemSendsTheItemToItsOwnCell(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed stone");
        helper.assertTrue(drop(player, new ItemStack(Items.DIRT, 3), 0) == Result.OK, "fixture could not seed dirt");

        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE), 1, 0) == Result.DUPLICATE_FILTER,
                "the server must still refuse a second cell for an item it already holds");

        var target = aimedTargetFor(player, new ItemStack(Items.STONE), 1);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.EXISTING_CELL && target.slot() == 0,
                "the item must be routed to its own cell, got " + target);
        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE), target.slot(), 0) == Result.OK,
                "the routed deposit was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(0).amount() == 6, "the item's own cell did not grow: " + cells.get(0).amount());
        helper.assertTrue(cells.get(1).amount() == 3, "the cell the player aimed at changed");
        helper.succeed();
    }

    /** (c) The aimed cell is empty but another cell already claims the item - the DUPLICATE_FILTER case. */
    @GameTest(template = "empty")
    public static void anAimedEmptyCellMovesToTheCellThatClaimsTheItem(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed cell 0");

        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE), 1, 0) == Result.DUPLICATE_FILTER,
                "the server must still refuse an empty cell for an item that already has one");

        var target = aimedTargetFor(player, new ItemStack(Items.STONE), 1);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.EXISTING_CELL && target.slot() == 0,
                "an empty aimed cell must not become a refusal, got " + target);
        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE), target.slot(), 0) == Result.OK,
                "the routed deposit was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(0).amount() == 6, "the claimed cell did not grow: " + cells.get(0).amount());
        helper.assertTrue(cells.get(1).filter() == null && cells.get(1).amount() == 0,
                "the empty cell the player aimed at was left alone");
        helper.succeed();
    }

    /** (c) A brand new item aimed at an occupied cell takes the first empty cell instead of being refused. */
    @GameTest(template = "empty")
    public static void anAimedOccupiedCellGivesANewItemTheFirstEmptyCell(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed stone");
        helper.assertTrue(drop(player, new ItemStack(Items.DIRT, 3), 0) == Result.OK, "fixture could not seed dirt");
        var iron = new ItemStack(Items.IRON_INGOT, 4);

        helper.assertTrue(dropAt(player, iron, 1, 0) == Result.FILTER_OCCUPIED,
                "the server must still refuse a deposit into a cell that filters another item");

        var target = aimedTargetFor(player, iron, 1);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.FIRST_EMPTY_CELL && target.slot() == 2,
                "a new item must take the first empty cell, got " + target);
        helper.assertTrue(dropAt(player, iron, target.slot(), 0) == Result.OK, "the routed deposit was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(2).filter() != null
                        && cells.get(2).filter().equals(ItemVariantKey.of(iron, player.registryAccess()))
                        && cells.get(2).amount() == 4, "the new item did not land with its filter and count");
        helper.assertTrue(cells.get(0).amount() == 5 && cells.get(1).amount() == 3, "a neighbouring cell changed");
        helper.succeed();
    }

    /** An aimed cell is not a way around a full panel: no room anywhere still reports no target, and nothing moves. */
    @GameTest(template = "empty")
    public static void anAimedCellWithNoRoomAnywhereStillReportsNoTarget(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        for (Item item : SEED) helper.assertTrue(drop(player, new ItemStack(item), 0) == Result.OK,
                "fixture could not fill a cell with " + item);
        var before = cellsOf(player);
        var stranger = new ItemStack(Items.NETHER_STAR);

        var target = aimedTargetFor(player, stranger, 4);
        helper.assertTrue(target.outcome() == CacheDropTarget.Outcome.NO_TARGET && !target.hasTarget(),
                "an aimed drop with nowhere to go must report no target, got " + target);
        helper.assertTrue(dropAt(player, stranger, 4, 0) == Result.FILTER_OCCUPIED,
                "the aimed cell must still refuse a direct deposit");
        helper.assertTrue(dropAt(player, stranger, target.slot(), 0) == null, "the panel sent a deposit anyway");

        helper.assertTrue(before.equals(cellsOf(player)), "a refused aimed drop still changed the cache");
        helper.succeed();
    }

    /** A right click still moves exactly one item, wherever the drop was routed. */
    @GameTest(template = "empty")
    public static void aRoutedRightClickStillDepositsExactlyOneItem(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(drop(player, new ItemStack(Items.STONE, 5), 0) == Result.OK, "fixture could not seed stone");
        helper.assertTrue(drop(player, new ItemStack(Items.DIRT, 3), 0) == Result.OK, "fixture could not seed dirt");

        var target = aimedTargetFor(player, new ItemStack(Items.STONE, 8), 1);
        helper.assertTrue(target.slot() == 0, "the routed cell is the item's own cell, got " + target);
        helper.assertTrue(dropAt(player, new ItemStack(Items.STONE, 8), target.slot(), 1) == Result.OK,
                "the routed right click was refused");

        var cells = cellsOf(player);
        helper.assertTrue(cells.get(0).amount() == 6, "a right click moved more than one item: " + cells.get(0).amount());
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 7,
                "the cursor stack must keep the rest: " + player.containerMenu.getCarried().getCount());
        helper.succeed();
    }
}
