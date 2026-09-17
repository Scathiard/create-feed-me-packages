package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntResync;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * F-7 gate 2 (approved fallback): the post-placement re-sync only BROADCASTS what already exists - it must
 * change no item anywhere - and it runs once per transfer (their handle is called once per placement).
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntResyncTests {
    @GameTest(template = "empty")
    public static void theResyncChangesNothing(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.getInventory().setItem(2, new ItemStack(Items.IRON_INGOT, 7));
        player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
        String inventory = player.getInventory().items.toString();
        String carried = player.containerMenu.getCarried().toString();

        FxntResync.afterPlacement(player);

        helper.assertTrue(player.getInventory().items.toString().equals(inventory), "the resync must not change the inventory: " + player.getInventory().items);
        helper.assertTrue(player.containerMenu.getCarried().toString().equals(carried), "the resync must not change the carried stack");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFailedResyncDegradesQuietly(GameTestHelper helper) {
        FxntResync.afterPlacement(null);
        helper.succeed();
    }
}