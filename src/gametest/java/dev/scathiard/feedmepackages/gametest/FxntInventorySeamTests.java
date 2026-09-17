package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CachePresentingInventory;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * F-6 gate 1 (constructive): the seam is a subclass of the vanilla Inventory parameter, so the owner is the
 * parameter itself - there is nothing to "match" and therefore nothing that can fail to match. This drives it
 * in-process with a real player and a real ledger and checks that a write-back debits exactly that cell while
 * the player's real inventory stays untouched.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntInventorySeamTests {
    private static void seed(ServerPlayer player, int slot, ItemStack stack, int count) {
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); var key = ItemVariantKey.of(stack, player.registryAccess()); edit.filter(slot, key); edit.insert(slot, key, count);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
    }
    private static int stock(ServerPlayer player, int slot) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().get(slot).amount();
    }

    @GameTest(template = "empty")
    public static void theInventoryParameterCarriesTheOwnerAndTheDebitHitsThatCell(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        seed(player, 0, new ItemStack(Items.GOLD_INGOT), 32);
        player.getInventory().setItem(5, new ItemStack(Items.STICK, 3));
        String before = player.getInventory().items.toString();

        var supply = new ServerCacheSupply(player);
        var seam = new CachePresentingInventory(player, supply, supply.available(),
                List.of(Ingredient.of(Items.GOLD_INGOT)), "inventory-seam-test");

        // No identity matching anywhere: the owner IS the vanilla parameter we wrapped.
        helper.assertTrue(seam.owner() == player, "the seam must carry the player it was handed: " + seam.owner());

        // Their occupied slot is theirs; our lent slot presents the cache.
        helper.assertTrue(seam.getItem(5).is(Items.STICK) && seam.getItem(5).getCount() == 3, "their item must be untouched");
        helper.assertTrue(seam.getItem(0).is(Items.GOLD_INGOT) && seam.getItem(0).getCount() == 32, "the accepted cell must be presented");
        helper.assertTrue(seam.exposed() == 1, "exactly one empty slot may be lent: " + seam.exposed());

        // A write-back on the lent slot debits exactly that cell and never writes the real inventory.
        seam.setItem(0, ItemStack.EMPTY);
        helper.assertTrue(seam.served() == 32 && seam.debited() == 32, "served and debited must agree: " + seam.served() + "/" + seam.debited());
        helper.assertTrue(stock(player, 0) == 0, "the cache cell must lose exactly what was taken: " + stock(player, 0));
        helper.assertTrue(player.getInventory().items.toString().equals(before), "the real inventory must not change");
        helper.succeed();
    }
}