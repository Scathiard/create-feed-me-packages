package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.ServerCacheSupply;
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

import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/**
 * F-2 evidence: a debit must really move the ledger. This drives {@link ServerCacheSupply#take} against a REAL
 * cache (the same CacheLedger the panel uses) and measures the cell afterwards - no log-reading, no mock.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntCacheDebitTests {
    private static void seed(ServerPlayer player, int slot, ItemStack stack, int count) {
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); var key = ItemVariantKey.of(stack, player.registryAccess()); edit.filter(slot, key); edit.insert(slot, key, count);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
    }
    private static int stock(ServerPlayer player, int slot) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().get(slot).amount();
    }

    @GameTest(template = "empty")
    public static void takeByCellIndexRemovesExactlyThatManyFromThatCell(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 64);
        seed(player, 1, new ItemStack(Items.OAK_LOG), 8);
        var supply = new ServerCacheSupply(player);

        var entries = supply.available();
        helper.assertTrue(entries.size() == 2 && entries.get(0).cell() == 0 && entries.get(0).stack().getCount() == 64,
                "the cache view must carry the cell index and the free amount: " + entries.size());

        int first = supply.take(entries.get(0).cell(), 5);
        helper.assertTrue(first == 5, "take must report the amount really removed: " + first);
        helper.assertTrue(stock(player, 0) == 59, "cell 0 must hold 59 after taking 5, but holds " + stock(player, 0));
        helper.assertTrue(stock(player, 1) == 8, "the untouched cell must not change");

        int capped = supply.take(0, 1000);
        helper.assertTrue(capped == 59 && stock(player, 0) == 0, "taking more than the cell holds must stop at what is there: " + capped);

        // F-6 gate, in-process: the seam is the vanilla Inventory parameter itself, so there is no identity to
        // match and a write-back must debit the real ledger.
        seed(player, 3, new ItemStack(Items.DIAMOND), 64);
        var real = new ServerCacheSupply(player);
        helper.assertTrue(real.side().equals("server-cache"), "the server-side view must announce itself: " + real.side());
        var seam = new dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingInventory(
                player, real, real.available(), List.of(Ingredient.of(Items.DIAMOND)), "debit-seam-test", false);
        helper.assertTrue(seam.owner() == player, "the owner is the inventory parameter itself");
        int lent = -1;
        for (int index = 0; index < seam.getContainerSize() && lent < 0; index++) if (seam.getItem(index).is(Items.DIAMOND)) lent = index;
        helper.assertTrue(lent >= 0, "the accepted cell must be presented");
        int presented = seam.getItem(lent).getCount();
        seam.setItem(lent, ItemStack.EMPTY);
        helper.assertTrue(seam.served() == presented && seam.debited() == presented,
                "a full take must serve and debit the same amount: " + seam.served() + "/" + seam.debited());
        helper.assertTrue(stock(player, 3) == 64 - presented, "the ledger cell must lose exactly what was taken: " + stock(player, 3));        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCellAboveTheStackLimitIsClampedAndNeverThrows(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.IRON_INGOT), 1000);
        seed(player, 1, new ItemStack(Items.SNOWBALL), 1000);
        seed(player, 2, new ItemStack(Items.WATER_BUCKET), 8);
        var supply = new ServerCacheSupply(player);
        var entries = supply.available();   // must not throw for any of them
        for (var entry : entries) {
            helper.assertTrue(entry.stack().getCount() <= entry.stack().getMaxStackSize(),
                    "a presented stack must never exceed its native limit: " + entry.stack());
        }
        var present = new dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingInventory(
                player, supply, entries, List.of(Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.SNOWBALL), Ingredient.of(Items.WATER_BUCKET)), "cap-test", false);
        int lent = -1;
        for (int index = 0; index < present.getContainerSize() && lent < 0; index++) if (present.getItem(index).is(Items.IRON_INGOT) || present.getItem(index).is(Items.SNOWBALL) || present.getItem(index).is(Items.WATER_BUCKET)) lent = index;
        helper.assertTrue(lent >= 0, "at least one capped cell must be presented");
        int shown = present.getItem(lent).getCount();
        helper.assertTrue(shown <= present.getItem(lent).getMaxStackSize(), "presented count must respect the cap: " + shown);
        helper.succeed();
    }
}