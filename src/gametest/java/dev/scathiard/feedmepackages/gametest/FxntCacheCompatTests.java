package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingHandler;
import dev.scathiard.feedmepackages.compat.fxntstorage.CacheSupply;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrapper driven in-process by a fake base {@link ItemStackHandler} (standing in for their backpack) and a
 * fake {@link CacheSupply} (standing in for our cache). The five rules: only their empty item slots are
 * filled, their items and their own writes are untouched, a write-back debits exactly what was taken, a
 * shortage of slots exposes fewer, and nothing virtual ever lands in their container.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntCacheCompatTests {
    /** A cache view that records every debit as "cellxcount", so "took exactly this much, from this cell" is checkable. */
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
    private static String nbt(ItemStackHandler handler) {
        var parts = new ArrayList<String>();
        for (int index = 0; index < handler.getSlots(); index++) {
            var stack = handler.getStackInSlot(index);
            parts.add(stack.isEmpty() ? "-" : stack.getCount() + "x" + stack.getItem());
        }
        return parts.toString();
    }

    @GameTest(template = "empty")
    public static void borrowsOnlyTheirEmptySlotsAndNeverTheirItems(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 5));
        theirs.setStackInSlot(1, new ItemStack(Items.STICK, 2));
        var supply = new FakeSupply(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.IRON_INGOT, 32));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(),
                materials(Items.OAK_PLANKS, Items.IRON_INGOT), 0, 3, "test");

        helper.assertTrue(wrapper.getStackInSlot(0).is(Items.IRON_INGOT) && wrapper.getStackInSlot(0).getCount() == 5, "their item was altered");
        helper.assertTrue(wrapper.getStackInSlot(1).is(Items.STICK) && wrapper.getStackInSlot(1).getCount() == 2, "their item was altered");
        helper.assertTrue(wrapper.getStackInSlot(2).is(Items.OAK_PLANKS) && wrapper.getStackInSlot(2).getCount() == 64, "cache stack not presented");
        helper.assertTrue(wrapper.exposed() == 1, "only one empty item slot exists, so only one may be lent: " + wrapper.exposed());
        helper.assertTrue(wrapper.getStackInSlot(7).isEmpty(), "slots outside the item range must stay theirs");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyCellsTheRecipeAcceptsArePresented(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        var supply = new FakeSupply(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.IRON_INGOT, 32));
        // A recipe that needs only iron: the log cell must never be presented.
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), materials(Items.IRON_INGOT), 0, 3, "test");
        helper.assertTrue(wrapper.exposed() == 1, "exactly one accepted cell may be lent: " + wrapper.exposed());
        helper.assertTrue(!wrapper.getStackInSlot(0).is(Items.OAK_LOG), "a cell the recipe does not accept must not be presented");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theirSlotWritesGoBackToThemAndOurTakesDebitThatCell(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(1, new ItemStack(Items.STICK, 3));
        var supply = new FakeSupply(new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.OAK_PLANKS, 64));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(),
                materials(Items.IRON_INGOT, Items.OAK_PLANKS), 0, 3, "test");
        String before = nbt(theirs);

        wrapper.setStackInSlot(1, new ItemStack(Items.STICK, 1));
        helper.assertTrue(theirs.getStackInSlot(1).getCount() == 1, "their own slot did not go back to them");
        helper.assertTrue(supply.debits.isEmpty(), "their own slot write must not debit our cache");

        helper.assertTrue(wrapper.getStackInSlot(0).is(Items.IRON_INGOT) && wrapper.getStackInSlot(2).is(Items.OAK_PLANKS), "the two empty item slots must lend the two accepted cells");
        int presented = wrapper.getStackInSlot(2).getCount();
        wrapper.setStackInSlot(2, new ItemStack(Items.OAK_PLANKS, presented - 4));
        helper.assertTrue(supply.debits.equals(List.of("1x4")), "expected exactly 4 debited from cell 1, got " + supply.debits);
        helper.assertTrue(wrapper.served() == 4 && wrapper.debited() == 4, "served and debited must agree: " + wrapper.served() + "/" + wrapper.debited());
        var expected = new ArrayList<String>();
        for (int slot = 0; slot < 9; slot++) expected.add(slot == 1 ? "1x" + Items.STICK : "-");
        helper.assertTrue(nbt(theirs).equals(expected.toString()), "only their own write may change their container: " + before + " -> " + nbt(theirs));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShortageOfSlotsExposesFewerAndExtractDebitsOncePerItem(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(0, new ItemStack(Items.STICK));
        theirs.setStackInSlot(1, new ItemStack(Items.STICK));
        var supply = new FakeSupply(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.IRON_INGOT, 7), new ItemStack(Items.GOLD_INGOT, 3));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(),
                materials(Items.OAK_PLANKS, Items.IRON_INGOT, Items.GOLD_INGOT), 0, 3, "test");
        helper.assertTrue(wrapper.exposed() == 1, "only one empty item slot exists: " + wrapper.exposed());
        var simulated = wrapper.extractItem(2, 3, true);
        helper.assertTrue(simulated.getCount() == 3 && supply.debits.isEmpty(), "a simulation must not debit");
        var real = wrapper.extractItem(2, 3, false);
        helper.assertTrue(real.getCount() == 3 && supply.debits.equals(List.of("0x3")), "extract must debit cell 0 once: " + supply.debits);
        var again = wrapper.extractItem(2, 100, false);
        helper.assertTrue(again.getCount() == 61 && supply.debits.equals(List.of("0x3", "0x61")), "the rest must be extractable once: " + supply.debits);
        helper.assertTrue(wrapper.getStackInSlot(2).isEmpty(), "an exhausted lending must present nothing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theWrapperNeverSerializesIntoTheirBackpack(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(0, new ItemStack(Items.STICK, 4));
        String before = nbt(theirs);
        var supply = new FakeSupply(new ItemStack(Items.OAK_PLANKS, 12), new ItemStack(Items.IRON_INGOT, 12));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(),
                materials(Items.OAK_PLANKS, Items.IRON_INGOT), 0, 9, "test");
        helper.assertTrue(wrapper.getStackInSlot(1).is(Items.OAK_PLANKS) && wrapper.getStackInSlot(2).is(Items.IRON_INGOT), "both empty slots should have been lent");
        wrapper.setStackInSlot(1, ItemStack.EMPTY);
        wrapper.setStackInSlot(2, ItemStack.EMPTY);
        helper.assertTrue(nbt(theirs).equals(before), "their container changed: " + before + " -> " + nbt(theirs));
        helper.assertTrue(supply.debits.size() == 2, "both takes must debit: " + supply.debits);
        helper.assertTrue(wrapper.exposed() == 0 && wrapper.getStackInSlot(1).isEmpty() && wrapper.getStackInSlot(2).isEmpty(), "a settled wrapper must present nothing");
        helper.succeed();
    }
}