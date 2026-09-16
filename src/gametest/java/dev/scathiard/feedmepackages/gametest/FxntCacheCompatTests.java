package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingHandler;
import dev.scathiard.feedmepackages.compat.fxntstorage.CacheSupply;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The Create: Storage compat wrapper, driven in-process by a fake base {@link ItemStackHandler} (standing in
 * for their backpack) and a fake {@link CacheSupply} (standing in for our cache). These are the five rules
 * the wrapper must obey; the real injection into their class is verified by the user in their pack.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntCacheCompatTests {
    /** A cache view that records every debit, so "took exactly this much" is checkable. */
    private static final class FakeSupply implements CacheSupply {
        private final List<ItemStack> stacks = new ArrayList<>();
        private final List<String> debits = new ArrayList<>();
        FakeSupply(ItemStack... contents) { for (ItemStack stack : contents) stacks.add(stack); }
        @Override public List<ItemStack> available() { return stacks; }
        @Override public void take(ItemStack prototype, int count) { debits.add(prototype.getHoverName().getString() + "x" + count); }
        @Override public String side() { return "test"; }
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
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), 0, 3, "test");

        // Their occupied slots come back untouched; their empty item slots expose our cache.
        helper.assertTrue(wrapper.getStackInSlot(0).is(Items.IRON_INGOT) && wrapper.getStackInSlot(0).getCount() == 5, "their item was altered");
        helper.assertTrue(wrapper.getStackInSlot(1).is(Items.STICK) && wrapper.getStackInSlot(1).getCount() == 2, "their item was altered");
        helper.assertTrue(wrapper.getStackInSlot(2).is(Items.OAK_PLANKS) && wrapper.getStackInSlot(2).getCount() == 64, "cache stack not presented");
        helper.assertTrue(wrapper.exposed() == 1, "only one empty item slot exists, so only one may be lent: " + wrapper.exposed());
        // Outside the item-slot range nothing is lent, even when empty.
        helper.assertTrue(wrapper.getStackInSlot(7).isEmpty(), "slots outside the item range must stay theirs");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theirSlotWritesGoBackToThemAndOursTakeExactlyWhatWasUsed(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(1, new ItemStack(Items.STICK, 3));
        var supply = new FakeSupply(new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.OAK_PLANKS, 64));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), 0, 3, "test");
        String before = nbt(theirs);

        // A write to one of THEIR slots is handed straight back to their container.
        wrapper.setStackInSlot(1, new ItemStack(Items.STICK, 1));
        helper.assertTrue(theirs.getStackInSlot(1).getCount() == 1, "their own slot did not go back to them");
        helper.assertTrue(supply.debits.isEmpty(), "their own slot write must not debit our cache");

        // A write-back on OUR slot debits exactly (presented - remaining) and never touches their container.
        helper.assertTrue(wrapper.getStackInSlot(0).is(Items.IRON_INGOT) && wrapper.getStackInSlot(2).is(Items.OAK_PLANKS), "the two empty item slots must lend the two cache stacks");
        int presented = wrapper.getStackInSlot(2).getCount();
        helper.assertTrue(presented == 64, "expected the whole cache stack: " + presented);
        wrapper.setStackInSlot(2, new ItemStack(Items.OAK_PLANKS, presented - 4));
        helper.assertTrue(supply.debits.equals(List.of("Oak Planksx4")), "expected exactly 4 debited, got " + supply.debits);
        String after = nbt(theirs);
        helper.assertFalse(after.contains("Iron Ingot"), "a virtual cache item must never appear in their container");
        var expected = new ArrayList<String>();
        for (int slot = 0; slot < 9; slot++) expected.add(slot == 1 ? "1x" + Items.STICK : "-");
        helper.assertTrue(after.equals(expected.toString()), "only their own write may change their container: " + before + " -> " + after);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShortageOfSlotsExposesFewerStacksAndATakeOfOneDebitsOne(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(0, new ItemStack(Items.STICK));
        theirs.setStackInSlot(1, new ItemStack(Items.STICK));
        var supply = new FakeSupply(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.IRON_INGOT, 7), new ItemStack(Items.GOLD_INGOT, 3));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), 0, 3, "test");
        // Only slot 2 is empty inside the item range, so exactly one of the three cache stacks is exposed.
        helper.assertTrue(wrapper.exposed() == 1, "expected one exposed slot: " + wrapper.exposed());
        int presented = wrapper.getStackInSlot(2).getCount();
        wrapper.setStackInSlot(2, new ItemStack(Items.OAK_PLANKS, presented - 1));
        helper.assertTrue(supply.debits.equals(List.of("Oak Planksx1")), "expected a one-item debit, got " + supply.debits);
        // Nothing left to lend: a second read exposes nothing.
        helper.assertTrue(wrapper.getStackInSlot(2).isEmpty(), "a settled slot must not keep presenting");
        helper.assertTrue(wrapper.extractItem(2, 1, false).isEmpty(), "a settled slot must not hand out items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extractItemDebitsAndSimulationDoesNot(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        var supply = new FakeSupply(new ItemStack(Items.IRON_INGOT, 10));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), 0, 3, "test");
        var simulated = wrapper.extractItem(0, 3, true);
        helper.assertTrue(simulated.getCount() == 3 && supply.debits.isEmpty(), "a simulation must not debit");
        var real = wrapper.extractItem(0, 3, false);
        helper.assertTrue(real.getCount() == 3 && supply.debits.equals(List.of("Iron Ingotx3")), "extract must debit what it handed out: " + supply.debits);
        helper.assertTrue(wrapper.getStackInSlot(0).getCount() == 7, "the presentation must shrink by what was extracted");
        // A repeated extract keeps working and debits exactly once per item.
        var again = wrapper.extractItem(0, 100, false);
        helper.assertTrue(again.getCount() == 7 && supply.debits.equals(List.of("Iron Ingotx3", "Iron Ingotx7")), "the rest must be extractable once: " + supply.debits);
        helper.assertTrue(wrapper.getStackInSlot(0).isEmpty() && wrapper.extractItem(0, 1, false).isEmpty(), "an exhausted lending must present nothing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theWrapperNeverSerializesIntoTheirBackpack(GameTestHelper helper) {
        var theirs = new ItemStackHandler(9);
        theirs.setStackInSlot(0, new ItemStack(Items.STICK, 4));
        String before = nbt(theirs);
        var supply = new FakeSupply(new ItemStack(Items.OAK_PLANKS, 12), new ItemStack(Items.IRON_INGOT, 12));
        var wrapper = new CachePresentingHandler(theirs, supply, supply.available(), 0, 9, "test");
        helper.assertTrue(wrapper.getStackInSlot(1).is(Items.OAK_PLANKS) && wrapper.getStackInSlot(2).is(Items.IRON_INGOT), "both empty slots should have been lent");
        // Drain both slots exactly as their placement code would, then compare their container byte for byte.
        wrapper.setStackInSlot(1, ItemStack.EMPTY);
        wrapper.setStackInSlot(2, ItemStack.EMPTY);
        helper.assertTrue(nbt(theirs).equals(before), "their container changed: " + before + " -> " + nbt(theirs));
        helper.assertTrue(supply.debits.size() == 2, "both takes must debit: " + supply.debits);
        // The wrapper presents nothing once its lendings are settled, and never mirrors their storage.
        helper.assertTrue(wrapper.exposed() == 0 && wrapper.getStackInSlot(1).isEmpty() && wrapper.getStackInSlot(2).isEmpty(), "a settled wrapper must present nothing");
        helper.succeed();
    }
}
