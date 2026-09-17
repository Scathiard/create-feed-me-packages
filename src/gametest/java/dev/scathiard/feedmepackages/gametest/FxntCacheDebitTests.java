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
import net.neoforged.neoforge.items.ItemStackHandler;
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

        // F-4 gate, in-process: a wrapper fed by the REAL server cache must debit the ledger and say which
        // side it used (single player used to pick the client view here, and the debit then vanished).
        seed(player, 2, new ItemStack(Items.GOLD_INGOT), 64);
        var real = new ServerCacheSupply(player);
        helper.assertTrue(real.side().equals("server-cache"), "the server-side view must announce itself: " + real.side());
        var theirs = new ItemStackHandler(9);
        var wrapper = new dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingHandler(
                theirs, real, real.available(), List.of(Ingredient.of(Items.GOLD_INGOT)), 0, 9, "debit-test");
        helper.assertTrue(wrapper.getStackInSlot(0).is(Items.GOLD_INGOT) && wrapper.exposed() == 1, "the accepted cell must be lent");
        int presented = wrapper.getStackInSlot(0).getCount();
        wrapper.setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(wrapper.served() == presented && wrapper.debited() == presented,
                "a full take must serve and debit the same amount: " + wrapper.served() + "/" + wrapper.debited());
        helper.assertTrue(stock(player, 2) == 64 - presented, "the ledger cell must lose exactly what was taken: " + stock(player, 2));
        helper.succeed();
    }
}