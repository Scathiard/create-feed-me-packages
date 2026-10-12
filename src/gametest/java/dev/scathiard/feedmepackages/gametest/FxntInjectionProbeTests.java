package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.block.Blocks;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;

/** Verifies actual optional method descriptors by observing a pure count and one exact paid placement. */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntInjectionProbeTests {
    @GameTest(template = "empty")
    public static void nativeTransferMethodsExposeOnlyPaidSources(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("fxntstorage")) {
            FeedMePackages.LOGGER.info("FMP compat probe: skipped (fxntstorage is not installed)");
            helper.succeed();
            return;
        }
        var player = TestPlayers.create(helper, dev.scathiard.feedmepackages.registry.FmpRegistries.PENDANT.toStack());
        // Real crafting menu (their placement writes into these slots) and a matching cache cell to debit.
        var relative = new BlockPos(1, 1, 1); helper.setBlock(relative, Blocks.CRAFTING_TABLE);
        player.containerMenu = new CraftingMenu(37, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(relative)));
        player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 1, 2)));
        helper.assertTrue(player.containerMenu.stillValid(player), "Native workbench fixture is out of range");
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); var stone = new net.minecraft.world.item.ItemStack(Items.STONE);
        var key = ItemVariantKey.of(stone, player.registryAccess()); edit.filter(0, key); edit.insert(0, key, 8);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        int stoneBefore = record.state().cells().get(0).amount() + 8;
        var packetClass = Class.forName("net.fxnt.fxntstorage.network.packet.TransferRecipePacket");
        var packet = packetClass.getDeclaredConstructor(ResourceLocation.class, List.class, boolean.class, int.class)
                .newInstance(ResourceLocation.withDefaultNamespace("oak_planks"), List.of(), false, 0);

        var count = packetClass.getDeclaredMethod("getMaxCraftableItems", List.class, Inventory.class, IItemHandler.class);
        count.setAccessible(true);
        var place = packetClass.getDeclaredMethod("collectAndPlace", Ingredient.class, int.class, int.class,
                Inventory.class, IItemHandlerModifiable.class, List.class);
        place.setAccessible(true);
        var wanted = Ingredient.of(Items.STONE);
        try (var scope = new dev.scathiard.feedmepackages.compat.fxntstorage.FxntTransferContext(
                List.of(wanted), null, false, dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.JEI)) {
            int available = ((Number) count.invoke(packet, List.of(wanted), player.getInventory(), new ItemStackHandler(200))).intValue();
            helper.assertTrue(available == 8 && ledger.find(handle.cacheId()).state().cells().get(0).amount() == 8,
                    "Native count did not expose all exact sources purely");
            boolean placed = (boolean) place.invoke(packet, wanted, 0, 1, player.getInventory(),
                    new ItemStackHandler(200), List.of(player.containerMenu.getSlot(1)));
            var output = player.containerMenu.getSlot(1).getItem();
            helper.assertTrue(placed && output.is(Items.STONE) && output.getCount() == 1
                    && ledger.find(handle.cacheId()).state().cells().get(0).amount() == 7,
                    "Native placement must materialize and debit exactly one item");
        }
        helper.assertTrue(dev.scathiard.feedmepackages.compat.fxntstorage.FxntTransferContext.current() == null,
                "Native transfer leaked its scope");
        helper.succeed();
    }
}
