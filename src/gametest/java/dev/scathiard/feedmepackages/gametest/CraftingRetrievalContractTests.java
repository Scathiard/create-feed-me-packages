package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * Acceptance contract for taking a crafting result while a pendant is worn: one click may only spend the
 * material the grid really holds (specs/05 §单份与最大填充: "网格填好后，玩家 Shift 点击结果能连续合成的次数由原版当前网格真实材料决定；
 * 本模组不会在每次结果取出后暗中续填").
 *
 * <p>Landed on the released 0.2.0 baseline ({@code fe42c7e}) by the 0.2 patch branch
 * {@code fix/0.2.1-craft-withdrawal}: the silent grid refill (the {@code ResultSlot.onTake} wrapper plus
 * {@code CraftingService.afterCraft}) is gone there too. The nine cases are ported from the 0.3 line's
 * contract class; every API they drive already exists at this baseline, so nothing had to be substituted.
 * The user decided the removal on 2026-09-14 (team comms P2-0246) and revised it to "fix the retrieval" on
 * 2026-09-16 (team comms P2-0247).
 *
 * <p>Every case drives a real entry: the vanilla menu click the packet handler calls
 * ({@code AbstractContainerMenu.clicked}) or the real panel intent ({@link PanelNetwork#command}), never a
 * private service call. The fixture without a worn pendant is the vanilla control the modded fixture has to
 * match item for item. A preparation (JEI "＋" / native recipe book) stays a menu-local lease until a native
 * click spends it, and taking the result never puts material back into the grid.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftingRetrievalContractTests {
    private CraftingRetrievalContractTests() {}
    private static void seed(ServerPlayer player, int slot, ItemStack stack, int count) {
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); var key = ItemVariantKey.of(stack, player.registryAccess()); edit.filter(slot, key); edit.insert(slot, key, count);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
    }
    private static int stock(ServerPlayer player, int slot) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().get(slot).amount();
    }
    private static int inventoryCount(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static int gridCount(ServerPlayer player, Item item) {
        return CraftingService.grid(player.containerMenu).getItems().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static long ledgerRevision(ServerPlayer player) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().revision();
    }
    @SuppressWarnings("unchecked") private static RecipeHolder<CraftingRecipe> recipe(ServerPlayer player, String id) {
        return (RecipeHolder<CraftingRecipe>) player.getServer().getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow();
    }
    /** The real panel/JEI entry: a live snapshot, then one FILL_RECIPE intent over the panel packet channel. */
    private static CacheActions.Result fill(ServerPlayer player, String recipeId, boolean maximum) {
        var window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, player.containerMenu.containerId, true));
        var intent = new CacheActions.Intent(view.session(), view.revision(), CacheActions.Action.FILL_RECIPE, 0, maximum ? 1 : 0, -1, recipeId);
        return PanelNetwork.command(player, new PanelPackets.Command(window, 1, intent, false, "", 0)).result();
    }
    private static void table(GameTestHelper helper, ServerPlayer player) {
        var relative = new BlockPos(1, 1, 1); helper.setBlock(relative, Blocks.CRAFTING_TABLE); var pos = helper.absolutePos(relative);
        player.setPos(pos.getX(), pos.getY() + 1, pos.getZ());
        player.containerMenu = new CraftingMenu(37, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), pos));
    }
    /** What the vanilla result click spent, for the modded/control comparison. */
    private record Spent(int planks, int logs, int gridLogs) {}
    /** One real shift-click on a grid holding exactly one log and a 64-log backpack stack. */
    private static Spent shiftClickAGridHoldingOneLog(GameTestHelper helper, boolean worn) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        if (!worn) TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
        CraftingService.grid(player.containerMenu).setItem(0, new ItemStack(Items.OAK_LOG, 1));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        return new Spent(inventoryCount(player, Items.OAK_PLANKS), inventoryCount(player, Items.OAK_LOG), gridCount(player, Items.OAK_LOG));
    }
    /** The same fixture on a real 3x3 crafting table menu. */
    private static Spent shiftClickATableHoldingOneLog(GameTestHelper helper, boolean worn) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        if (!worn) TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        table(helper, player);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
        CraftingService.grid(player.containerMenu).setItem(0, new ItemStack(Items.OAK_LOG, 1));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        return new Spent(inventoryCount(player, Items.OAK_PLANKS), inventoryCount(player, Items.OAK_LOG), gridCount(player, Items.OAK_LOG));
    }

    /** The real click may spend the grid's own material only: the backpack stack stays untouched. */
    @GameTest(template = "empty")
    public static void aShiftClickSpendsOnlyTheGridsOwnBackpackMaterial(GameTestHelper helper) {
        var spent = shiftClickAGridHoldingOneLog(helper, true);
        helper.assertTrue(spent.planks() == 4 && spent.logs() == 64 && spent.gridLogs() == 0,
                "One shift-click on a grid holding one log must craft once (4 planks), leave the 64-log backpack stack alone and empty the grid; measured " + spent);
        helper.succeed();
    }

    /** The same click on a real crafting table, against its own vanilla control. */
    @GameTest(template = "empty")
    public static void aShiftClickOnACraftingTableMatchesVanilla(GameTestHelper helper) {
        var worn = shiftClickATableHoldingOneLog(helper, true);
        var vanilla = shiftClickATableHoldingOneLog(helper, false);
        helper.assertTrue(worn.equals(vanilla) && worn.planks() == 4 && worn.logs() == 64 && worn.gridLogs() == 0,
                "A shift-click on the crafting table must craft once (4 planks) with the backpack untouched and match the pendantless control: worn=" + worn + ", vanilla=" + vanilla);
        helper.succeed();
    }

    /** The same click with a cache cell in reach must not spend it either. */
    @GameTest(template = "empty")
    public static void aCacheCellIsNotSpentWhenTheGridHoldsItsOwnLog(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 128);
        CraftingService.grid(player.containerMenu).setItem(0, new ItemStack(Items.OAK_LOG, 1));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 4 && stock(player, 0) == 128 && gridCount(player, Items.OAK_LOG) == 0,
                "A shift-click must spend the grid's own log, not the cache cell beside it: planks=" + inventoryCount(player, Items.OAK_PLANKS)
                        + ", cache=" + stock(player, 0) + ", grid logs=" + gridCount(player, Items.OAK_LOG));
        helper.succeed();
    }

    /** A prepared cache-only cell: the click settles exactly the one leased log (128 -> 127) and leaves no lease. */
    @GameTest(template = "empty")
    public static void aPreparedCraftSettlesOneCachedLogAndLeavesTheCellEmpty(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 128);
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:oak_planks"), false, false, true) == CraftingService.Result.OK,
                "Preparing one log from the cache failed");
        helper.assertTrue(stock(player, 0) == 128 && gridCount(player, Items.OAK_LOG) == 1,
                "Preparation already spent cache material: cache=" + stock(player, 0) + ", grid logs=" + gridCount(player, Items.OAK_LOG));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 4 && stock(player, 0) == 127 && gridCount(player, Items.OAK_LOG) == 0,
                "One shift-click on a prepared cache log must settle exactly one recipe (128 -> 127, 4 planks, empty grid): planks="
                        + inventoryCount(player, Items.OAK_PLANKS) + ", cache=" + stock(player, 0) + ", grid logs=" + gridCount(player, Items.OAK_LOG));
        var cache = AccessGate.resolve(player).handle().cacheId();
        helper.assertTrue(CraftingReservations.reservedCache(cache, 0, null) == 0 && CraftingReservations.gridAmounts(player).isEmpty(),
                "An emptied grid must not keep a lease or a preview behind");
        player.closeContainer();
        helper.assertTrue(stock(player, 0) == 127 && inventoryCount(player, Items.OAK_PLANKS) == 4 && inventoryCount(player, Items.OAK_LOG) == 0,
                "Closing the grid after the take released a phantom item or lost the output: cache=" + stock(player, 0)
                        + ", planks=" + inventoryCount(player, Items.OAK_PLANKS) + ", backpack logs=" + inventoryCount(player, Items.OAK_LOG));
        helper.succeed();
    }

    /** The vanilla control: the identical fixture without a worn pendant has to behave item for item the same. */
    @GameTest(template = "empty")
    public static void theSameClickWithoutWearingIsVanilla(GameTestHelper helper) {
        var worn = shiftClickAGridHoldingOneLog(helper, true);
        var vanilla = shiftClickAGridHoldingOneLog(helper, false);
        helper.assertTrue(worn.equals(vanilla), "A worn pendant changed the native result click: worn=" + worn + ", vanilla=" + vanilla);
        helper.assertTrue(vanilla.planks() == 4 && vanilla.logs() == 64 && vanilla.gridLogs() == 0, "The control is not vanilla itself: " + vanilla);
        helper.succeed();
    }

    /** Cached iron ingots fill a real 2x2 panel intent, and one shift-click settles exactly one shears recipe. */
    @GameTest(template = "empty")
    public static void cachedIronIngotsFillARealPanelIntentAndSettleOnce(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.IRON_INGOT), 30);
        var before = ledgerRevision(player);
        var result = fill(player, "minecraft:shears", false);
        helper.assertTrue(result == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 2 && stock(player, 0) == 30 && ledgerRevision(player) == before,
                "FILL_RECIPE minecraft:shears from a cache-only iron-ingot cell returned " + result + ", grid ingots=" + gridCount(player, Items.IRON_INGOT)
                        + ", cache=" + stock(player, 0) + " (a preparation is a lease, not a debit)");
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.SHEARS) == 1 && stock(player, 0) == 28 && gridCount(player, Items.IRON_INGOT) == 0,
                "One shift-click on the prepared shears must settle exactly one recipe (1 shears, cache 30 -> 28): shears="
                        + inventoryCount(player, Items.SHEARS) + ", cache=" + stock(player, 0) + ", grid ingots=" + gridCount(player, Items.IRON_INGOT));
        player.closeContainer();
        helper.assertTrue(stock(player, 0) == 28 && inventoryCount(player, Items.SHEARS) == 1,
                "Closing the prepared grid lost or duplicated material: cache=" + stock(player, 0) + ", shears=" + inventoryCount(player, Items.SHEARS));
        helper.succeed();
    }

    /** The same cache-only cell fills a real 3x3 crafting-table intent, still as a lease. */
    @GameTest(template = "empty")
    public static void cachedIronIngotsFillAThreeByThreeTable(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 30);
        var result = fill(player, "minecraft:iron_block", false);
        int ingots = gridCount(player, Items.IRON_INGOT);
        helper.assertTrue(result == CacheActions.Result.OK && ingots == 9 && stock(player, 0) == 30,
                "FILL_RECIPE minecraft:iron_block on a crafting table from a cache-only iron-ingot cell returned " + result
                        + ", grid ingots=" + ingots + ", cache=" + stock(player, 0) + " (expected OK, 9, 30)");
        helper.succeed();
    }

    /** The backpack control of the same entry: it fills from ordinary inventory material just as well. */
    @GameTest(template = "empty")
    public static void backpackLogsFillTheSameEntry(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
        var result = fill(player, "minecraft:oak_planks", false);
        int logs = gridCount(player, Items.OAK_LOG);
        helper.assertTrue(result == CacheActions.Result.OK && logs == 1,
                "FILL_RECIPE minecraft:oak_planks from a backpack log stack returned " + result + " with grid logs=" + logs);
        helper.succeed();
    }

    /** A manual take settles exactly one cached ingot, leaves the cell empty and opens no new lease. */
    @GameTest(template = "empty")
    public static void takingTheResultSettlesOneCachedIngotAndLeavesNoNewLease(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.IRON_INGOT), 30);
        var result = fill(player, "minecraft:iron_nugget", false);
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(result == CacheActions.Result.OK && player.containerMenu.getCarried().is(Items.IRON_NUGGET)
                        && player.containerMenu.getCarried().getCount() == 9 && gridCount(player, Items.IRON_INGOT) == 0 && stock(player, 0) == 29,
                "One manual take must settle exactly one cached ingot and leave the cell empty (cache 30 -> 29): carried="
                        + player.containerMenu.getCarried() + ", grid ingots=" + gridCount(player, Items.IRON_INGOT) + ", cache=" + stock(player, 0));
        var cache = AccessGate.resolve(player).handle().cacheId();
        helper.assertTrue(CraftingReservations.reservedCache(cache, 0, null) == 0 && CraftingReservations.gridAmounts(player).isEmpty(),
                "A taken result must not open a new lease on the emptied cell");
        player.closeContainer();
        helper.assertTrue(stock(player, 0) == 29 && inventoryCount(player, Items.IRON_NUGGET) == 9,
                "Closing after a manual take lost or duplicated material: cache=" + stock(player, 0) + ", nuggets=" + inventoryCount(player, Items.IRON_NUGGET));
        helper.succeed();
    }
}
