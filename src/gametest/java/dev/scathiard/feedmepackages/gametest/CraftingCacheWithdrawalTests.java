package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.consumption.CraftingService;
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
import net.minecraft.network.chat.Component;
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
}
