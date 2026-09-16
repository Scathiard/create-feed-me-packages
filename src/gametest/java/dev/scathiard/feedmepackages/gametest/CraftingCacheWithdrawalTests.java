package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.MaterialHints;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * U09 evidence (user report 2026-09-16: with a pendant that is worn but NOT bound to a network, taking
 * cache material into a real crafting grid always ends in "missing material"). Each case drives a real
 * entry - the panel/JEI intent over {@link PanelNetwork#command} plus the vanilla menu click - and pins
 * one of the three candidate causes with numbers, so the judgement table in the report is reproducible:
 *
 * <ul>
 *   <li>the hint channel (A): the server still reports {@code active} for an unbound pendant, the promised
 *       templates decode back to the very stacks a recipe ingredient has, and no cell can carry stock
 *       without a filter (the one shape that makes the client drop its whole material view);</li>
 *   <li>the variant mismatch (B): a cell whose exact variant carries components still serves a recipe that
 *       asks for the plain item - measured against the plain control, so this cause is excluded;</li>
 *   <li>the 3x3 two-phase gap (C): filling an iron-block recipe was covered, taking the result was not.</li>
 * </ul>
 *
 * <p>The second round (user: "scissors work, the iron block does not") adds one case per candidate for a
 * 3x3-only failure: the demanded quantity itself (H1), stock split over cells (H2 - two cells of the same
 * exact variant are refused outright by {@code CacheEdit.filter}, and the legal mixed-variant form fills
 * fine), the menu/parameter differences between 2x2 and 3x3 (H3), a live crafting lease (H4) and a cursor
 * hold (H5). H2-H5 are excluded with numbers; H1 is the truth when the demanded identity really has fewer
 * than nine.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftingCacheWithdrawalTests {
    private CraftingCacheWithdrawalTests() {}
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
    @SuppressWarnings("unchecked") private static RecipeHolder<CraftingRecipe> recipe(ServerPlayer player, String id) {
        return (RecipeHolder<CraftingRecipe>) player.getServer().getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow();
    }
    /** The real panel/JEI entry: a live snapshot, then one FILL_RECIPE intent over the panel packet channel. */
    private static CacheActions.Result fill(ServerPlayer player, String recipeId, boolean maximum) {
        return command(player, CacheActions.Action.FILL_RECIPE, 0, maximum ? 1 : 0, recipeId);
    }
    /** Any real panel action over the panel packet channel, with a live snapshot first. */
    private static CacheActions.Result command(ServerPlayer player, CacheActions.Action action, int slot, int first, String template) {
        player.tickCount += 8; // PanelNetwork.Budget refuses a second query within four ticks
        var window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, player.containerMenu.containerId, true));
        var intent = new CacheActions.Intent(view.session(), view.revision(), action, slot, first, -1, template);
        return PanelNetwork.command(player, new PanelPackets.Command(window, 1, intent, false, "", 0)).result();
    }
    private static void table(GameTestHelper helper, ServerPlayer player) {
        var relative = new BlockPos(1, 1, 1); helper.setBlock(relative, Blocks.CRAFTING_TABLE); var pos = helper.absolutePos(relative);
        player.setPos(pos.getX(), pos.getY() + 1, pos.getZ());
        player.containerMenu = new CraftingMenu(37, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), pos));
    }
    private static ItemStack named(ItemStack stack, String name) {
        var copy = stack.copy(); copy.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return copy;
    }

    /**
     * (C) The half that was never covered: 9 cached ingots fill a 3x3 iron-block recipe AND the take
     * settles exactly one block. Real entries: FILL_RECIPE over the panel channel, then the vanilla
     * shift-click on the result slot.
     */
    @GameTest(template = "empty")
    public static void cachedIngotsFillAndTakeAnIronBlockOnATable(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        helper.assertTrue(fill(player, "minecraft:iron_block", false) == CacheActions.Result.OK, "3x3 iron-block fill from a cache-only cell failed");
        helper.assertTrue(gridCount(player, Items.IRON_INGOT) == 9 && stock(player, 0) == 9,
                "Preparation must place 9 ingots as a lease without debiting: grid=" + gridCount(player, Items.IRON_INGOT) + ", cache=" + stock(player, 0));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0 && gridCount(player, Items.IRON_INGOT) == 0,
                "One shift-click must craft exactly one iron block and settle exactly the 9 leased ingots: blocks=" + inventoryCount(player, Items.IRON_BLOCK)
                        + ", cache=" + stock(player, 0) + ", grid=" + gridCount(player, Items.IRON_INGOT));
        player.closeContainer();
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0 && inventoryCount(player, Items.IRON_INGOT) == 0,
                "Closing the 3x3 grid lost or duplicated material after the take");
        helper.succeed();
    }

    /** (C) The pickup half of the same recipe: one left-click on the result settles one block. */
    @GameTest(template = "empty")
    public static void cachedIngotsTakeAnIronBlockByPickup(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        helper.assertTrue(fill(player, "minecraft:iron_block", false) == CacheActions.Result.OK, "3x3 iron-block fill from a cache-only cell failed");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.IRON_BLOCK) && player.containerMenu.getCarried().getCount() == 1
                        && stock(player, 0) == 0 && gridCount(player, Items.IRON_INGOT) == 0,
                "One manual take must settle exactly one block and consume exactly the 9 leased ingots: carried=" + player.containerMenu.getCarried()
                        + ", cache=" + stock(player, 0) + ", grid=" + gridCount(player, Items.IRON_INGOT));
        player.closeContainer();
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0, "Closing after the manual take lost the crafted block");
        helper.succeed();
    }

    /** The control the report needs: too few cached ingots must answer MISSING, not silently succeed. */
    @GameTest(template = "empty")
    public static void tooFewCachedIngotsAnswerMissingMaterial(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 4);
        var result = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(result == CacheActions.Result.MISSING_MATERIAL && stock(player, 0) == 4 && gridCount(player, Items.IRON_INGOT) == 0,
                "An under-filled cell must answer MISSING_MATERIAL and move nothing: result=" + result + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (A) The hint channel for exactly the user's setup: pendant worn, NOT bound to a network.
     * This is the data the client's material view is built from, so if it is sound here the client can
     * only lose it inside its own accept() path - and the one shape that makes it drop everything
     * (a cell with stock but no filter) is checked to be unreachable.
     */
    @GameTest(template = "empty")
    public static void unboundPendantStillReportsActiveMaterialHints(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        var access = AccessGate.resolve(player);
        helper.assertTrue(access.active() && access.handle() != null && access.handle().networkId() == null,
                "Fixture is not the user's case: access=" + access.status() + ", network=" + (access.handle() == null ? null : access.handle().networkId()));
        var message = MaterialHints.next(player);
        helper.assertTrue(message != null && message.active(), "The server must still send active hints for an unbound worn pendant");
        helper.assertTrue(message.templates().size() == message.amounts().size() && message.ownReservations().size() == message.amounts().size(),
                "Hint lists must stay parallel: templates=" + message.templates().size() + ", amounts=" + message.amounts().size()
                        + ", own=" + message.ownReservations().size());
        helper.assertTrue(message.amounts().get(0) == 9 && message.ownReservations().get(0) == 0, "The cached stock must be offered to the client as 9 free ingots");
        var decoded = message.templates().get(0).isEmpty() ? ItemStack.EMPTY : ItemVariantKey.decode(message.templates().get(0), player.registryAccess()).stack(player.registryAccess(), 1);
        helper.assertTrue(ItemStack.isSameItemSameComponents(decoded, new ItemStack(Items.IRON_INGOT)),
                "The promised template must decode to the plain ingredient a recipe asks for, not a variant: " + decoded);
        for (int i = 0; i < message.templates().size(); i++) helper.assertTrue(!message.templates().get(i).isEmpty() || message.amounts().get(i) == 0,
                "A cell with stock but no filter would make the client drop its whole material view (ClientMaterials.accept rejects it); cell " + i
                        + " breaks that: template empty with amount " + message.amounts().get(i));
        helper.succeed();
    }

    /** (A) And the transfer itself works unbound: the cache is a real source without a network. */
    @GameTest(template = "empty")
    public static void unboundPendantStillFillsAndTakesFromTheCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        helper.assertTrue(AccessGate.resolve(player).handle().networkId() == null, "Fixture must stay unbound");
        helper.assertTrue(fill(player, "minecraft:iron_block", false) == CacheActions.Result.OK, "An unbound pendant must still fill from its cache");
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0,
                "An unbound pendant must still craft: blocks=" + inventoryCount(player, Items.IRON_BLOCK) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (B) Excluded, with numbers: the cache cell stores an exact variant that carries a component while the
     * recipe ingredient is the plain item. Both the client-side preview (what the JEI plus runs first) and
     * the real panel entry accept it, so a component-bearing cell is NOT the way "missing material" appears.
     */
    @GameTest(template = "empty")
    public static void componentBearingCellStillServesAPlainRecipe(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        var variant = named(new ItemStack(Items.IRON_INGOT), "Requested stock");
        seed(player, 0, variant, 9);
        var recipe = recipe(player, "minecraft:iron_block");
        var plain = new ItemStack(Items.IRON_INGOT, 9);
        var asVariant = variant.copyWithCount(9);
        helper.assertTrue(!ItemStack.isSameItemSameComponents(asVariant, new ItemStack(Items.IRON_INGOT)),
                "Fixture must differ by component identity");
        var variantSource = List.of(asVariant);
        var plainSource = List.of(plain);
        var variantChoice = CraftingPlanner.solve(recipe, 3, 3, 64, 1, false, variantSource, player.level());
        var plainChoice = CraftingPlanner.solve(recipe, 3, 3, 64, 1, false, plainSource, player.level());
        helper.assertTrue(variantChoice.error() == CraftingPlanner.Error.NONE && plainChoice.error() == CraftingPlanner.Error.NONE,
                "The preview the JEI plus runs must accept both stacks (its ingredient test tolerates extra components): variant=" + variantChoice.error()
                        + ", plain=" + plainChoice.error());
        var real = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(real == CacheActions.Result.OK && stock(player, 0) == 9 && gridCount(player, Items.IRON_INGOT) == 9,
                "A component-bearing cell must still serve the plain recipe (measured, so this cause is excluded): result=" + real + ", cache=" + stock(player, 0)
                        + ", grid=" + gridCount(player, Items.IRON_INGOT));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0 && gridCount(player, Items.IRON_INGOT) == 0,
                "The variant cell must also craft and settle exactly like the plain one: blocks=" + inventoryCount(player, Items.IRON_BLOCK) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (H1) The honest half of the user's report: eight cached ingots are genuinely not enough for an iron
     * block, so MISSING_MATERIAL is the truth there (nine is the C1 case above, four is the control).
     */
    @GameTest(template = "empty")
    public static void eightCachedIngotsAreGenuinelyMissingForAnIronBlock(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 8);
        var view = MaterialTransaction.open(player).orElseThrow();
        helper.assertTrue(view.available(new ItemStack(Items.IRON_INGOT)) == 8,
                "Fixture must offer eight ingots to the planner: " + view.available(new ItemStack(Items.IRON_INGOT)));
        var result = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(result == CacheActions.Result.MISSING_MATERIAL && stock(player, 0) == 8 && gridCount(player, Items.IRON_INGOT) == 0,
                "Eight ingots must answer MISSING_MATERIAL and move nothing: result=" + result + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (H2) The captain's "5 + 4 in two cells" cannot even be built: the domain refuses two cells with the
     * same exact filter ({@code CacheEdit.filter:23-27} throws "Duplicate exact filter", which is what
     * {@code CacheActions.Result.DUPLICATE_FILTER} reports). So the only way the user's panel can show
     * several ingot-looking cells is cells with DIFFERENT variants - that is the fixture below.
     */
    @GameTest(template = "empty")
    public static void twoCellsWithTheSameExactFilterAreRefusedByTheDomain(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 5);
        String refusal = null;
        try { seed(player, 1, new ItemStack(Items.IRON_INGOT), 4); } catch (RuntimeException refused) { refusal = String.valueOf(refused.getMessage()); }
        helper.assertTrue(refusal != null, "A second cell with the same exact filter must be refused, otherwise H2 would be buildable");
        helper.assertTrue(stock(player, 0) == 5 && stock(player, 1) == 0,
                "The refusal must leave the cache as it was: cells=" + stock(player, 0) + "+" + stock(player, 1) + ", refusal=" + refusal);
        helper.succeed();
    }

    /**
     * (H2, legal form) Two ingot cells can only differ by variant, and then the 3x3 fill still works: the
     * planner's ingredient test tolerates the extra component, and the take walks the grid slot by slot.
     * Measured 5 plain + 4 named -&gt; OK with nine ingots in the grid, cells unchanged (a lease).
     */
    @GameTest(template = "empty")
    public static void mixedVariantCellsStillFillAnIronBlock(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 5);
        seed(player, 1, named(new ItemStack(Items.IRON_INGOT), "Requested stock"), 4);
        var view = MaterialTransaction.open(player).orElseThrow();
        int plain = view.available(new ItemStack(Items.IRON_INGOT));
        var result = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(result == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 9 && stock(player, 0) == 5 && stock(player, 1) == 4,
                "A second ingot cell with a different variant must not break the 3x3 fill (it does not, measured): result=" + result + ", plain=" + plain
                        + ", cells=" + stock(player, 0) + "+" + stock(player, 1) + ", grid=" + gridCount(player, Items.IRON_INGOT));
        helper.succeed();
    }

    /**
     * (H4) A live lease is the one way the hints can offer less than the panel shows (MaterialHints:71
     * subtracts every live reservation, ClientMaterials.stacks adds the player's own back). The
     * preparation path excludes the player's own lease, so an unfinished shears fill must not starve the
     * iron block: measured offered=7 + own=2, block=OK with nine ingots in the grid.
     */
    @GameTest(template = "empty")
    public static void anUnfinishedShearsLeaseDoesNotStarveTheIronBlock(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        helper.assertTrue(fill(player, "minecraft:shears", false) == CacheActions.Result.OK, "A 2x2 recipe with nine cached ingots must fill");
        var hints = MaterialHints.next(player);
        helper.assertTrue(hints.amounts().get(0) == 7 && hints.ownReservations().get(0) == 2,
                "The hint must offer free stock and the player's own lease separately: offered=" + hints.amounts().get(0)
                        + ", own=" + hints.ownReservations().get(0));
        var block = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(block == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 9,
                "A live lease of the player's own must not starve a nine-ingot fill: result=" + block + ", grid="
                        + gridCount(player, Items.IRON_INGOT) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (H5) Items held on the cursor from the cache reserve stock for the hints (offered drops to four of
     * nine) but not for crafting: the crafting action releases the hold first, so the 3x3 fill still works.
     */
    @GameTest(template = "empty")
    public static void aCursorHoldDoesNotStarveTheIronBlock(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        helper.assertTrue(command(player, CacheActions.Action.TAKE_CURSOR, 0, 5, "") == CacheActions.Result.OK
                        && player.containerMenu.getCarried().getCount() == 5,
                "A real cursor take of five ingots must succeed: carried=" + player.containerMenu.getCarried());
        var hints = MaterialHints.next(player);
        helper.assertTrue(hints.amounts().get(0) == 4, "The hint must exclude the five held on the cursor: offered=" + hints.amounts().get(0));
        var block = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(block == CacheActions.Result.OK && stock(player, 0) == 9,
                "A held cursor stack must not starve the fill (crafting releases the hold first): result=" + block + ", grid="
                        + gridCount(player, Items.IRON_INGOT) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * The discriminating evidence for the JEI report: the user's own simplest recipe, from a cache-only
     * cell with 128 logs, over the same real panel entry JEI's plus uses. If this works here, the server
     * side (which the vanilla recipe book also uses) is not what fails in their session.
     */
    @GameTest(template = "empty")
    public static void thePanelEntryFillsPlanksFromACachedLog(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.OAK_LOG), 128);
        var result = fill(player, "minecraft:oak_planks", false);
        helper.assertTrue(result == CacheActions.Result.OK && gridCount(player, Items.OAK_LOG) == 1 && stock(player, 0) == 128,
                "One cached log must fill the simplest recipe: result=" + result + ", grid=" + gridCount(player, Items.OAK_LOG)
                        + ", cache=" + stock(player, 0));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 4 && stock(player, 0) == 127,
                "One log must settle into exactly four planks: planks=" + inventoryCount(player, Items.OAK_PLANKS) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * The other entry the user says works: the NATIVE recipe book. It goes through
     * {@code CraftingService.fromRecipeBook -> place(...)} with no client hint channel involved, so its
     * success in their session proves the server fill/lease/settle chain is sound.
     */
    @GameTest(template = "empty")
    public static void theRecipeBookEntryFillsFromTheSameCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.OAK_LOG), 128);
        var planks = recipe(player, "minecraft:oak_planks");
        // The vanilla recipe book only places recipes the player knows (CraftingService.simulate:51).
        if (!player.getRecipeBook().contains(planks)) player.getRecipeBook().add(planks);
        helper.assertTrue(player.getRecipeBook().contains(planks), "The fixture must know the recipe");
        var accepted = CraftingService.fromRecipeBook(player, planks, false);
        helper.assertTrue(accepted && gridCount(player, Items.OAK_LOG) == 1 && stock(player, 0) == 128,
                "The native recipe book entry must fill from the cache: accepted=" + accepted + ", grid=" + gridCount(player, Items.OAK_LOG)
                        + ", cache=" + stock(player, 0));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 4 && stock(player, 0) == 127,
                "The recipe book path must settle exactly one log: planks=" + inventoryCount(player, Items.OAK_PLANKS) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /** The user's own cell contents: 128 ingots must fill a nine-slot recipe and settle exactly nine. */
    @GameTest(template = "empty")
    public static void aHundredAndTwentyEightIngotsFillAnIronBlock(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 128);
        helper.assertTrue(fill(player, "minecraft:iron_block", false) == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 9,
                "128 cached ingots must fill a 3x3 block: grid=" + gridCount(player, Items.IRON_INGOT) + ", cache=" + stock(player, 0));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 119,
                "Exactly nine ingots must settle: blocks=" + inventoryCount(player, Items.IRON_BLOCK) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * The 2x2/3x3 comparison the JEI report needs, with the actual values printed: the same cache, the
     * same recipe set, and the same planner call the client makes - once in the player's own 2x2 grid and
     * once in a real crafting table. All three previews answer NONE, so the client's own simulation is not
     * where "2x2 passes, 3x3 fails" can come from; the difference has to be in the client-side inputs
     * (hints/panel), which the new log line prints on a real click.
     */
    @GameTest(template = "empty")
    public static void theTwoMenusAgreeOnGridHintsAndPreview(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 128);
        player.containerMenu = new InventoryMenu(player.getInventory(), true, player);
        var small = CraftingService.grid(player.containerMenu);
        var smallHints = MaterialHints.next(player);
        var smallPreview = CraftingPlanner.solve(recipe(player, "minecraft:shears"), small.getWidth(), small.getHeight(), small.getMaxStackSize(), 1, false,
                List.of(new ItemStack(Items.IRON_INGOT, 128)), player.level());
        boolean smallSupported = CraftingService.supported(player.containerMenu);
        table(helper, player);
        var large = CraftingService.grid(player.containerMenu);
        var largeHints = MaterialHints.next(player);
        var largeShears = CraftingPlanner.solve(recipe(player, "minecraft:shears"), large.getWidth(), large.getHeight(), large.getMaxStackSize(), 1, false,
                List.of(new ItemStack(Items.IRON_INGOT, 128)), player.level());
        var largeBlock = CraftingPlanner.solve(recipe(player, "minecraft:iron_block"), large.getWidth(), large.getHeight(), large.getMaxStackSize(), 1, false,
                List.of(new ItemStack(Items.IRON_INGOT, 128)), player.level());
        String values = " small=" + small.getWidth() + "x" + small.getHeight() + " supported=" + smallSupported + " hints=" + smallHints.amounts() + " reservedGrid="
                + smallHints.reservedGrid().size() + " shears=" + smallPreview.error() + " | large=" + large.getWidth() + "x" + large.getHeight()
                + " supported=" + CraftingService.supported(player.containerMenu) + " hints=" + largeHints.amounts() + " reservedGrid=" + largeHints.reservedGrid().size()
                + " shears=" + largeShears.error() + " block=" + largeBlock.error();
        helper.assertTrue(smallSupported && small.getWidth() == 2 && large.getWidth() == 3 && large.getHeight() == 3
                        && smallHints.amounts().get(0) == 128 && largeHints.amounts().get(0) == 128
                        && smallPreview.error() == CraftingPlanner.Error.NONE && largeShears.error() == CraftingPlanner.Error.NONE
                        && largeBlock.error() == CraftingPlanner.Error.NONE,
                "The two menus must agree on hints and on the client preview:" + values);
        helper.succeed();
    }

    /** (t62-2) The player's own 2x2 grid is a real entry too: two cached ingots must become scissors. */
    @GameTest(template = "empty")
    public static void theInventoryGridWithdrawsScissorsFromTheCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.containerMenu = new InventoryMenu(player.getInventory(), true, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 2);
        var result = fill(player, "minecraft:shears", false);
        helper.assertTrue(result == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 2 && stock(player, 0) == 2,
                "Two cached ingots must fill the 2x2 inventory grid: result=" + result + ", grid=" + gridCount(player, Items.IRON_INGOT)
                        + ", cache=" + stock(player, 0));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.SHEARS) == 1 && stock(player, 0) == 0,
                "One take must settle both leased ingots into one shears: shears=" + inventoryCount(player, Items.SHEARS) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /** (t62-3) The contrast the user reported: the same recipe, the same cache, both menus. */
    @GameTest(template = "empty")
    public static void theSameRecipeFillsInBothMenus(GameTestHelper helper) {
        var small = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        small.containerMenu = new InventoryMenu(small.getInventory(), true, small);
        seed(small, 0, new ItemStack(Items.IRON_INGOT), 2);
        var smallResult = fill(small, "minecraft:shears", false);
        var large = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, large);
        seed(large, 0, new ItemStack(Items.IRON_INGOT), 2);
        var largeResult = fill(large, "minecraft:shears", false);
        helper.assertTrue(smallResult == CacheActions.Result.OK && largeResult == CacheActions.Result.OK
                        && gridCount(small, Items.IRON_INGOT) == 2 && gridCount(large, Items.IRON_INGOT) == 2,
                "2x2 and 3x3 must agree for the same recipe and cache: inventory=" + smallResult + ", table=" + largeResult);
        helper.succeed();
    }

    /**
     * (t62-1) The server must not need the client's material view at all: the 3x3 fill has to work and
     * settle with no client state present. This dedicated-server test JVM literally has none -
     * {@code ClientMaterials} cannot even be loaded here (invalid dist) - so a real panel fill plus a
     * real take in this test is the "client view cleared" case; the client-side {@code clear()} path is
     * covered by the handler and the new log lines instead.
     */
    @GameTest(template = "empty")
    public static void theBlockFillsEvenWithNoClientMaterialView(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); table(helper, player);
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 9);
        var result = fill(player, "minecraft:iron_block", false);
        helper.assertTrue(result == CacheActions.Result.OK && gridCount(player, Items.IRON_INGOT) == 9,
                "A missing client view must not block the server fill (this JVM has no client state): result=" + result + ", grid="
                        + gridCount(player, Items.IRON_INGOT));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 1 && stock(player, 0) == 0,
                "The take must settle with no client view: blocks=" + inventoryCount(player, Items.IRON_BLOCK) + ", cache=" + stock(player, 0));
        helper.succeed();
    }

    /**
     * (H6-a) Every advertised template must be one the client can NAME, or empty with zero stock: the
     * client decodes with ItemVariantKey, and one nameless cell used to clear its whole material view.
     */
    @GameTest(template = "empty")
    public static void everyAdvertisedHintTemplateCanBeNamedByTheClient(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 128);
        var message = MaterialHints.next(player);
        int namelessWithStock = 0;
        for (int i = 0; i < message.templates().size(); i++) {
            var template = message.templates().get(i);
            if (template.isEmpty()) { if (message.amounts().get(i) > 0) namelessWithStock++; continue; }
            var decoded = ItemVariantKey.decode(template, player.registryAccess()).stack(player.registryAccess(), 1);
            helper.assertTrue(!decoded.isEmpty(), "Advertised template " + i + " must decode to a real item: " + decoded);
        }
        helper.assertTrue(namelessWithStock == 0,
                "No hinted cell may carry stock without a name the client can decode (the client clears its whole view on that): " + namelessWithStock);
        helper.succeed();
    }

    /** (H6-b) The invariant behind that: an unfiltered cell cannot hold stock, and a filter change needs an empty cell. */
    @GameTest(template = "empty")
    public static void anUnfilteredCellCannotHoldStock(GameTestHelper helper) {
        String stateRefusal = null, changeRefusal = null;
        try { new Cell<ItemVariantKey>(null, 5, -1, -1, 0); } catch (RuntimeException refused) { stateRefusal = refused.getMessage(); }
        var ingot = Cell.empty().configure(ItemVariantKey.of(new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess())).withAmount(5);
        try { ingot.configure(ItemVariantKey.of(new ItemStack(Items.STICK), helper.getLevel().registryAccess())); }
        catch (RuntimeException refused) { changeRefusal = refused.getMessage(); }
        helper.assertTrue(stateRefusal != null && changeRefusal != null,
                "An unfiltered cell with stock and a filter change on a stocked cell must both be refused: state=" + stateRefusal + ", change=" + changeRefusal);
        helper.succeed();
    }

    /**
     * The real root cause, pinned end to end with real calls: a ledger written by a newer version (the
     * user's world is schema 10) is refused by this baseline and comes back LOCKED, which turns
     * AccessGate into STORAGE_LOCKED (AccessGate:52-53) and leaves the client with no material view at
     * all (MaterialHints.next's active=false) - the state that made JEI silently fall back to the vanilla
     * transfer for every cache-supplied recipe while the recipe book kept working. The locked ledger is
     * installed into the world's data storage for the assertions and swapped back afterwards; a locked
     * ledger cannot even be saved (CacheLedger.writable refuses), so nothing is overwritten.
     */
    @GameTest(template = "empty")
    public static void aHigherSchemaLedgerLocksTheCacheAndTheMaterialView(GameTestHelper helper) {
        helper.assertTrue(CacheLedger.SCHEMA == 8, "This baseline is expected to be schema 8, not " + CacheLedger.SCHEMA);
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var server = helper.getLevel().getServer();
        var storage = server.overworld().getDataStorage();
        var healthy = CacheLedger.get(server);
        var fromNewer = new CompoundTag(); fromNewer.putInt("schema", 10);
        var locked = CacheLedger.load(fromNewer, helper.getLevel().registryAccess());
        boolean refused = !locked.problem().isEmpty();
        // The message can name the refused file's own schema, and the lock re-writes the original data
        // verbatim on save: both come from the same public call.
        var preserved = locked.save(new CompoundTag(), helper.getLevel().registryAccess());
        int sourceSchema = preserved.contains("schema") ? preserved.getInt("schema") : -1;
        String status; boolean hinted; boolean empty;
        try {
            storage.set(CacheLedger.NAME, locked);
            status = AccessGate.resolve(player).status().name();
            var message = MaterialHints.next(player);
            hinted = message.active(); empty = message.templates().isEmpty() && message.amounts().isEmpty();
        } finally { storage.set(CacheLedger.NAME, healthy); }
        helper.assertTrue(refused && status.equals("STORAGE_LOCKED") && !hinted && empty && sourceSchema == 10,
                "A schema-10 ledger must lock the cache and leave the client without a material view: refused=" + refused
                        + ", status=" + status + ", hintsActive=" + hinted + ", empty=" + empty + ", sourceSchema=" + sourceSchema);
        helper.assertTrue(AccessGate.resolve(player).status() == AccessGate.Status.ACTIVE && MaterialHints.next(player).active(),
                "Swapping the healthy ledger back must restore the cache (otherwise this test poisons the world)");
        helper.succeed();
    }

    /**
     * The player side of the lock, on REAL server players (a FakePlayer swallows client messages): the
     * very first tick after joining reports it once - even for a player wearing nothing, which is the
     * whole point, since an unworn player never reaches the gate - the worn case then repeats about every
     * five seconds, and a healthy world stays silent.
     */
    @GameTest(template = "empty")
    public static void theLockIsAnnouncedOncePerJoinAndThenOnAPace(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var storage = server.overworld().getDataStorage();
        var healthy = CacheLedger.get(server);
        var fromNewer = new CompoundTag(); fromNewer.putInt("schema", 10);
        var locked = CacheLedger.load(fromNewer, helper.getLevel().registryAccess());
        int unworn, unwornQuiet, worn, wornRepeat, sentCount; String sentTypes;
        try {
            storage.set(CacheLedger.NAME, locked);
            var bare = TestPlayers.real(helper, UUID.randomUUID());
            var bareSent = TestPlayers.nativePackets(bare);
            bare.tickCount = 0; MaterialHints.tick(bare); unworn = actionBars(bareSent);
            bare.tickCount = 4; MaterialHints.tick(bare); unwornQuiet = actionBars(bareSent);
            sentCount = bareSent.size();
            sentTypes = bareSent.stream().map(entry -> entry.getClass().getSimpleName()).distinct().toList().toString();
            var wornPlayer = TestPlayers.real(helper, UUID.randomUUID());
            TestPlayers.necklace(wornPlayer).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
            var wornSent = TestPlayers.nativePackets(wornPlayer);
            wornPlayer.tickCount = 0; MaterialHints.tick(wornPlayer); worn = actionBars(wornSent);
            wornPlayer.tickCount = 200; MaterialHints.tick(wornPlayer); wornRepeat = actionBars(wornSent);
        } finally { storage.set(CacheLedger.NAME, healthy); }
        var quiet = TestPlayers.real(helper, UUID.randomUUID());
        var quietSent = TestPlayers.nativePackets(quiet);
        quiet.tickCount = 0; MaterialHints.tick(quiet);
        int healthySilent = actionBars(quietSent);
        helper.assertTrue(unworn == 1 && unwornQuiet == 1 && worn == 1 && wornRepeat == 2 && healthySilent == 0,
                "The lock must be announced once on join (even unworn), then on a five-second pace, and never in a healthy world: unworn=" + unworn
                        + ", unwornQuiet=" + unwornQuiet + ", worn=" + worn + ", wornRepeat=" + wornRepeat + ", healthySilent=" + healthySilent
                        + ", packetsSeen=" + sentCount + " types=" + sentTypes + " problem=[" + CacheLedger.get(server).problem() + "]");
        helper.succeed();
    }
    /** The action bar is an overlay SystemChat packet in 1.21, not a SetActionBarText packet. */
    private static int actionBars(java.util.List<net.minecraft.network.protocol.Packet<?>> sent) {
        return (int) sent.stream().filter(packet -> packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat
                && chat.overlay()).count();
    }
}
