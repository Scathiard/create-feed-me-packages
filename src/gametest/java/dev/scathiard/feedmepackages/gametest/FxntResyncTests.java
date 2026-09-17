package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntResync;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * F-7 gate, F-8 hardened: the post-placement re-sync only BROADCASTS the two menus. It must not create, move,
 * replace or even re-stack an item anywhere - so this checks the stack INSTANCES as well as their contents.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntResyncTests {
    @GameTest(template = "empty")
    public static void theResyncKeepsEveryItemInstanceAndContent(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.getInventory().setItem(2, new ItemStack(Items.IRON_INGOT, 7));
        player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
        ItemStack watched = player.getInventory().getItem(2);
        int identity = System.identityHashCode(watched);
        String inventory = player.getInventory().items.toString();
        String carried = player.containerMenu.getCarried().toString();

        FxntResync.afterPlacement(player);

        helper.assertTrue(System.identityHashCode(player.getInventory().getItem(2)) == identity,
                "the re-sync must not replace the stack instance");
        helper.assertTrue(player.getInventory().getItem(2) == watched, "the very same stack object must still be there");
        helper.assertTrue(player.getInventory().items.toString().equals(inventory), "the re-sync must not change the inventory: " + player.getInventory().items);
        helper.assertTrue(player.containerMenu.getCarried().toString().equals(carried), "the re-sync must not change the carried stack");
        helper.assertTrue(CompatLog.hasLogged("resync-menu-ok"), "the one broadcast line must be emitted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMissingPlayerDegradesQuietly(GameTestHelper helper) {
        FxntResync.afterPlacement(null);
        helper.succeed();
    }
}