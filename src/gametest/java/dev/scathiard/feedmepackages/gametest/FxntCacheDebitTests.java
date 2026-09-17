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

        int empty = supply.take(0, 1);
        helper.assertTrue(empty == 0, "an empty cell must remove nothing: " + empty);
        helper.succeed();
    }
}