package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpAdvancement;
import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import dev.scathiard.feedmepackages.compat.maid.MaidMaterialSource;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdvancementTests {
    static ServerPlayer equipped(GameTestHelper h) {
        var player = TestPlayers.real(h, UUID.randomUUID());
        TestPlayers.nativePackets(player);
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        return player;
    }

    static boolean done(ServerPlayer player, String name) {
        var holder = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, name));
        if (holder == null) throw new IllegalStateException("Missing advancement " + name);
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    @GameTest(template = "empty")
    public static void inventoryUnlockAndHiddenDefinitions(GameTestHelper h) {
        var player = TestPlayers.real(h, UUID.randomUUID());
        TestPlayers.nativePackets(player);
        int hidden = 0;
        for (var entry : FmpAdvancement.values()) {
            var name = entry.name().toLowerCase(Locale.ROOT);
            h.assertFalse(done(player, name), "Unexpected initial progress " + name);
            var holder = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, name));
            if (holder.value().display().orElseThrow().isHidden()) hidden++;
        }
        h.assertTrue(hidden == 5 && FmpAdvancement.values().length == 12, "Expected seven visible and five hidden advancements");
        player.initInventoryMenu();
        player.getInventory().setItem(3, FmpRegistries.PENDANT.toStack());
        player.inventoryMenu.broadcastChanges();
        h.assertTrue(done(player, "root"), "Real inventory acquisition did not unlock the root");
        h.succeed();
    }

    private static CacheActions.Result click(ServerPlayer player, CacheActions.Action action, int slot, String data) {
        var view = CacheActions.snapshot(player);
        return CacheActions.execute(player, new CacheActions.Intent(view.session(), view.record().state().revision(),
                action, slot, 0, -1, data));
    }

    @GameTest(template = "empty")
    public static void rejectedContainersAndOwnAddress(GameTestHelper h) {
        var player = equipped(h);
        CacheActions.open(player);
        player.containerMenu.setCarried(FmpRegistries.PENDANT.toStack());
        h.assertTrue(click(player, CacheActions.Action.DEPOSIT, 0, "") == CacheActions.Result.INVALID_ITEM
                && done(player, "self_reference") && !done(player, "forbidden_nesting")
                && player.containerMenu.getCarried().is(FmpRegistries.PENDANT.get()), "Pendant rejection changed the cursor or awarded the wrong attempt");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        h.assertTrue(click(player, CacheActions.Action.SET_GHOST, 0, "minecraft:shulker_box") == CacheActions.Result.INVALID_ITEM
                && done(player, "forbidden_nesting") && CacheActions.snapshot(player).record().state().cells().getFirst().filter() == null,
                "Rejected template created a filter or failed to award the attempt");
        click(player, CacheActions.Action.SET_RETURN_ADDRESS, -1, "Factory");
        h.assertFalse(done(player, "return_to_self"), "A factory address counted as the player");
        click(player, CacheActions.Action.SET_RETURN_ADDRESS, -1, SupplyService.address(player));
        h.assertTrue(done(player, "return_to_self"), "Saving the player's own address did not count");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void deliveryRequiresActualIntakeAndDistinguishesTheOrder(GameTestHelper h) {
        var player = equipped(h);
        var f = ReceiveTests.setup(h, 0, player);
        var ordered = f.box(8);
        h.assertTrue(ReceiveService.canReceive(LogisticsEndpoint.player(player), ordered) && !done(player, "first_delivery"),
                "Simulating an intake awarded progress");
        h.assertTrue(ReceiveService.receive(player, ordered) && done(player, "first_delivery") && !done(player, "human_courier"),
                "Signed order was mistaken for an unsolicited package");
        var gift = PackageItem.containing(List.of(new ItemStack(Items.STONE, 2)));
        PackageItem.addAddress(gift, "@someone_else");
        h.assertFalse(ReceiveService.receive(player, gift) || done(player, "human_courier"), "Wrong address awarded a gift");
        PackageItem.addAddress(gift, SupplyService.address(player));
        h.assertTrue(ReceiveService.receive(player, gift) && done(player, "human_courier"), "Ordinary matching package did not count");
        player.getAdvancements().save();
        var restored = TestPlayers.real(h, player.getUUID());
        h.assertTrue(done(restored, "first_delivery") && done(restored, "human_courier"), "Vanilla advancement reload lost progress");
        h.succeed();
    }

    @GameTestGenerator public static Collection<TestFunction> optional() {
        if (!ModList.get().isLoaded("touhou_little_maid")) return List.of();
        return List.of(new TestFunction("maid", "advancement_maid_owner", FeedMePackages.MOD_ID + ":empty", 100, 0, true, h -> {
            var f = MaidTests.setup(h, new ItemStack(Items.BREAD), 4);
            var owner = TestPlayers.real(h, UUID.randomUUID());
            TestPlayers.nativePackets(owner);
            f.maid().setOwnerUUID(owner.getUUID());
            try {
                var players = BiotechTests.playerMap(owner);
                players.put(owner.getUUID(), owner);
                try {
                    var source = MaidMaterialSource.open(f.maid());
                    h.assertTrue(source.available(new ItemStack(Items.BREAD)) == 4 && !done(owner, "maid_logistics"), "A food query granted maid progress");
                    h.assertTrue(source.take(new ItemStack(Items.BREAD), 1) && done(owner, "maid_logistics"), "Maid's cache debit did not credit her owner");
                    f.maid().tickCount = 40; MaidCompatibility.tick(f.maid());
                    h.assertFalse(done(owner, "maid_boss"), "A low-level pendant counted as maximum");
                    for (int level = 2; level <= 5; level++) f.ledger().prepareOrdinaryUpgrade(f.ledger().find(f.handle().cacheId()), level).commit();
                    MaidCompatibility.tick(f.maid());
                    h.assertTrue(done(owner, "maid_boss"), "A max-level maid with an unequipped owner did not count");
                } finally { players.remove(owner.getUUID(), owner); }
            } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
            h.succeed();
        }));
    }
}
