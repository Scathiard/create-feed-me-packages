package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingInventory;
import dev.scathiard.feedmepackages.compat.fxntstorage.CacheSupply;
import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * F-6: the seam is now the vanilla Inventory subclass, and this case guards the property it must keep - ONLY
 * the cache cells this call's materials accept are ever presented, a matching cell presents what the cache can
 * give, a write-back debits exactly that cell, and nothing virtual lands in the player's real inventory.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntCacheCompatTests {
    /** A cache view that records every debit as "cellxcount", so "took exactly this from this cell" is checkable. */
    private static final class FakeSupply implements CacheSupply {
        private final List<Entry> entries = new ArrayList<>();
        private final List<String> debits = new ArrayList<>();
        FakeSupply(ItemStack... contents) { for (int cell = 0; cell < contents.length; cell++) entries.add(new Entry(cell, contents[cell])); }
        @Override public List<Entry> available() { return entries; }
        @Override public int take(int cell, int count) { debits.add(cell + "x" + count); return count; }
        @Override public String side() { return "test"; }
    }
    private static List<Ingredient> materials(net.minecraft.world.item.Item... items) {
        var list = new ArrayList<Ingredient>();
        for (var item : items) list.add(Ingredient.of(item));
        return list;
    }
    private static String inventoryOf(net.minecraft.world.entity.player.Player player) {
        return player.getInventory().items.toString();
    }

    @GameTest(template = "empty")
    public static void onlyCellsTheMaterialsAcceptArePresented(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var supply = new FakeSupply(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.IRON_INGOT, 32));
        // A recipe that needs only iron: the log cell (0) must never be presented anywhere.
        var seam = new CachePresentingInventory(player, supply, supply.available(), materials(Items.IRON_INGOT), "materials-test", false);
        helper.assertTrue(seam.exposed() == 1, "exactly one accepted cell may be lent: " + seam.exposed());
        var presented = new ArrayList<ItemStack>();
        for (int slot = 0; slot < seam.getContainerSize(); slot++) presented.add(seam.getItem(slot));
        helper.assertTrue(presented.stream().noneMatch(stack -> stack.is(Items.OAK_LOG)), "a cell the materials do not accept must not be presented");
        helper.assertTrue(presented.stream().anyMatch(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 32), "the accepted cell must be presented with what the cache can give");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWriteBackDebitsOnlyTheCellItCameFromAndNeverTheRealInventory(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.getInventory().setItem(5, new ItemStack(Items.STICK, 3));
        String before = inventoryOf(player);
        var supply = new FakeSupply(new ItemStack(Items.GOLD_INGOT, 40), new ItemStack(Items.IRON_INGOT, 12));
        var seam = new CachePresentingInventory(player, supply, supply.available(), materials(Items.GOLD_INGOT, Items.IRON_INGOT), "debit-test", false);

        helper.assertTrue(seam.getItem(5).is(Items.STICK) && seam.getItem(5).getCount() == 3, "their own item must be untouched");
        helper.assertTrue(seam.owner() == player, "the owner is the inventory parameter itself");

        int slot = -1;
        for (int index = 0; index < seam.getContainerSize() && slot < 0; index++) if (seam.getItem(index).is(Items.GOLD_INGOT)) slot = index;
        helper.assertTrue(slot >= 0 && seam.getItem(slot).getCount() == 40, "the accepted cell must be presented: " + slot);
        seam.setItem(slot, new ItemStack(Items.GOLD_INGOT, 36));
        helper.assertTrue(supply.debits.equals(List.of("0x4")), "exactly 4 items from cell 0 must be debited: " + supply.debits);
        helper.assertTrue(seam.served() == 4 && seam.debited() == 4, "served and debited must agree: " + seam.served() + "/" + seam.debited());
        helper.assertTrue(inventoryOf(player).equals(before), "the real inventory must not change: " + before + " -> " + inventoryOf(player));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aReadOnlyPresenterWritesNowhereAndSaysSo(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.getInventory().setItem(3, new ItemStack(Items.STICK, 2));
        String before = inventoryOf(player);
        var supply = new FakeSupply(new ItemStack(Items.IRON_INGOT, 16));
        var seam = new CachePresentingInventory(player, supply, supply.available(), materials(Items.IRON_INGOT), "readonly-test", true);
        helper.assertTrue(seam.readOnly(), "the client presenter must be read-only");
        seam.setItem(3, new ItemStack(Items.DIAMOND, 1));
        helper.assertTrue(inventoryOf(player).equals(before), "a read-only presenter must write nowhere: " + inventoryOf(player));
        helper.assertTrue(CompatLog.hasLogged("debit-refused:readonly"), "a read-only write must be refused out loud");
        helper.succeed();
    }
}