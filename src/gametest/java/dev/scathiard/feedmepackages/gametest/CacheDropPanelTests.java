package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheDropTarget;
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

    /** Exactly what the panel resolves: the client snapshot's cells plus the encoded cursor stack. */
    private static CacheDropTarget.Target targetFor(ServerPlayer player, ItemStack carried) {
        var view = CacheActions.snapshot(player);
        int groupCapacity = Math.max(1, CacheLevel.of(view.record().state().level()).capacity() / 64);
        List<CacheDropTarget.Cell> cells = new ArrayList<>();
        for (var cell : view.record().state().cells()) {
            int stackSize = cell.filter() == null ? 64 : Math.max(1, cell.filter().stackSize());
            cells.add(new CacheDropTarget.Cell(cell.filter() == null ? "" : cell.filter().encoded(),
                    cell.amount() >= groupCapacity * stackSize));
        }
        String template = carried.isEmpty() ? "" : ItemVariantKey.of(carried, player.registryAccess()).encoded();
        return CacheDropTarget.resolve(cells, template);
    }

    /** One drop: resolve the cell the panel would pick, then send the panel's own DEPOSIT there. */
    private static Result drop(ServerPlayer player, ItemStack carried, int first) {
        player.containerMenu.setCarried(carried.copy());
        var target = targetFor(player, carried);
        if (!target.hasTarget()) return null;   // the panel says NO_SPACE out loud instead of sending
        var view = CacheActions.open(player);
        return CacheActions.execute(player, new CacheActions.Intent(view.session(),
                view.record() == null ? -1 : view.record().state().revision(), Action.DEPOSIT, target.slot(), first, -1, ""));
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
                        && cells.get(0).amount() == 64, "the seeded cell changed");
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
}
