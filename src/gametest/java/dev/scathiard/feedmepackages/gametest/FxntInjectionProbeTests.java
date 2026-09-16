package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;

/**
 * F-1 regression probe: our Create: Storage hooks must actually MATCH their injection points. The earlier
 * build declared the hook parameter as {@code ItemStackHandler} while their LVT says {@code IItemHandler}/
 * {@code IItemHandlerModifiable}, so Mixin matched nothing and the wrapper never ran (and no {@code FMP compat:}
 * line ever appeared).
 *
 * <p>This test calls their two server-side transfer methods reflectively (no compile dependency on their mod)
 * and asserts that our one-shot {@code hooked …} lines were emitted - i.e. the injections are applied and the
 * types match. On a run without that mod the test reports itself as skipped and passes.
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntInjectionProbeTests {
    @GameTest(template = "empty")
    public static void theirTransferArgumentsAreWrappedByOurHooks(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("fxntstorage")) {
            FeedMePackages.LOGGER.info("FMP compat probe: skipped (fxntstorage is not installed)");
            helper.succeed();
            return;
        }
        var player = TestPlayers.create(helper, net.minecraft.world.item.ItemStack.EMPTY);
        var packetClass = Class.forName("net.fxnt.fxntstorage.network.packet.TransferRecipePacket");
        var packet = packetClass.getDeclaredConstructor(ResourceLocation.class, List.class, boolean.class, int.class)
                .newInstance(ResourceLocation.withDefaultNamespace("oak_planks"), List.of(), false, 0);

        // Their read-only count method: the third argument is IItemHandler (LVT slot 3 "backpack").
        var count = packetClass.getDeclaredMethod("getMaxCraftableItems", List.class, Inventory.class, IItemHandler.class);
        count.setAccessible(true);
        // Their own code may legitimately refuse a fake handler; what this test proves is that OUR hooks ran.
        try { count.invoke(packet, List.of(), player.getInventory(), new ItemStackHandler(200)); }
        catch (Throwable refusal) { FeedMePackages.LOGGER.info("FMP compat probe: their count method answered with {}", refusal.getClass().getSimpleName()); }

        // Their placement method: the fifth argument is IItemHandlerModifiable (LVT slot 5 "itemHandler").
        var place = packetClass.getDeclaredMethod("collectAndPlace", Ingredient.class, int.class, int.class,
                Inventory.class, IItemHandlerModifiable.class, List.class);
        place.setAccessible(true);
        try { place.invoke(packet, Ingredient.of(Items.STONE), 0, 1, player.getInventory(), new ItemStackHandler(200), List.of()); }
        catch (Throwable refusal) { FeedMePackages.LOGGER.info("FMP compat probe: their placement answered with {}", refusal.getClass().getSimpleName()); }

        helper.assertTrue(CompatLog.hasLogged("hooked:getMaxCraftableItems"),
                "our IItemHandler hook never fired - the injection point does not match their LVT");
        helper.assertTrue(CompatLog.hasLogged("hooked:collectAndPlace"),
                "our IItemHandlerModifiable hook never fired - the injection point does not match their LVT");
        helper.succeed();
    }
}
