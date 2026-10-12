package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.function.Consumer;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PalletTests {
    private record Fixture(GameTestHelper helper, ServerPlayer player, CacheHandle handle, ItemVariantKey key, ItemStack tool) {
        CacheLedger ledger() { return CacheLedger.get(player.getServer()); }
        CacheRecord record() { return ledger().find(handle.cacheId()); }
        long stock() { return record().state().cells().getFirst().amount(); }
        BlockHitResult hit() { return new BlockHitResult(helper.absoluteVec(new Vec3(2.5, 2, 2.5)), Direction.UP, helper.absolutePos(new BlockPos(2, 1, 2)), false); }
        InteractionResult place() { return player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit()); }
        void unchanged() {
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 2)).isAir() && stock() == 7
                    && player.getMainHandItem() == tool && WorldPlacement.reserved(handle.cacheId(), 0) == 0
                    && !helper.getLevel().captureBlockSnapshots && helper.getLevel().capturedBlockSnapshots.isEmpty(), "Failed placement left a world, item or reservation mutation");
        }
    }
    private static Fixture setup(GameTestHelper h, ItemStack block) {
        var p = TestPlayers.create(h, FmpRegistries.PENDANT.toStack()); p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absoluteVec(new Vec3(2.5, 2, 1.2))); h.setBlock(2, 1, 2, Blocks.STONE);
        var tool = FmpRegistries.POCKET_PALLET.toStack(); p.setItemInHand(InteractionHand.MAIN_HAND, tool);
        var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer()); var old = ledger.find(handle.cacheId());
        var key = ItemVariantKey.of(block, p.registryAccess()); var edit = old.state().edit(); edit.filter(0, key); edit.insert(0, key, 7);
        ledger.replace(handle, old.state().revision(), old.withState(edit.finish()));
        h.assertTrue(PalletService.choose(p, handle.cacheId(), key.encoded()), "Could not select genuine stocked block");
        return new Fixture(h, p, handle, key, tool);
    }
    private static void event(Fixture f, Consumer<BlockEvent.EntityPlaceEvent> action, Runnable body) {
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> { if (event.getEntity() == f.player()) action.accept(event); };
        NeoForge.EVENT_BUS.addListener(listener); try { body.run(); } finally { NeoForge.EVENT_BUS.unregister(listener); }
    }
    @GameTest(template = "empty")
    public static void nativePalletPlacementDebitsOneAndKeepsToolAndZeroAir(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE));
        h.assertTrue(f.place().consumesAction()  &&  h.getBlockState(new BlockPos(2,2,2)).is(Blocks.STONE)
                 &&  f.stock() == 6  &&  f.player().getMainHandItem() == f.tool() , "Native placement failed conservation");
        h.assertTrue(WorldPlacement.reserved(f.handle().cacheId(),0) == 0, "Committed placement kept a reservation"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletPlacementHonorsCancellationAndCallbackExceptions(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE));
        event(f, e -> e.setCanceled(true), () -> h.assertTrue(f.place() == InteractionResult.FAIL, "Canceled placement reported success")); f.unchanged();
        boolean failed = false;
        try { event(f, e -> { throw new IllegalStateException("FMP fixture placement callback"); }, f::place); }
        catch (IllegalStateException expected) { failed = expected.getMessage().contains("FMP fixture"); }
        h.assertTrue(failed, "Fixture exception did not cross native callback"); f.unchanged();
        h.assertTrue(f.place().consumesAction() && f.stock() == 6, "Exception poisoned the next world placement"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletLeaseProtectsSourcesAndUsesLatestUnrelatedRevision(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE));
        event(f, e -> {
            var plan = MaterialTransaction.open(f.player()).orElseThrow();
            h.assertTrue(plan.cacheAvailable(new ItemStack(Items.STONE)) == 0 && !plan.takeCache(new ItemStack(Items.STONE), 1), "Another consumer stole leased placement stock");
            var ledger = CacheLedger.get(f.player().getServer()); var record = f.record();
            ledger.replace(f.handle(), record.state().revision(), record.withResidual(f.key(), com.simibubi.create.content.logistics.box.PackageItem.containing(java.util.List.of(new ItemStack(Items.STONE)))));
        }, () -> h.assertTrue(f.place().consumesAction(), "Unrelated residual revision canceled safe placement"));
        h.assertTrue(f.stock() == 6 && com.simibubi.create.content.logistics.box.PackageItem.isPackage(f.record().residual(f.key())), "Placement overwrote the unrelated callback residual"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletRechecksAccessAfterEveryPlaceListener(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE));
        event(f, e -> TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY),
                () -> h.assertTrue(f.place() == InteractionResult.FAIL, "Access lost during callback was ignored"));
        f.unchanged(); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletDoorUsesMultiBlockEventAndConsumesOneItem(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.OAK_DOOR)); var multi = new java.util.concurrent.atomic.AtomicBoolean();
        event(f, e -> multi.set(e instanceof BlockEvent.EntityMultiPlaceEvent), () -> h.assertTrue(f.place().consumesAction(), "Door placement failed"));
        h.assertTrue(multi.get() && h.getBlockState(new BlockPos(2,2,2)).is(Blocks.OAK_DOOR)
                && h.getBlockState(new BlockPos(2,3,2)).is(Blocks.OAK_DOOR) && f.stock() == 6, "Two-block door did not consume exactly one cached item"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletCreativeStillConsumesAndPreservesBlockEntityComponents(GameTestHelper h) {
        var chest = new ItemStack(Items.CHEST); chest.set(DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND, 3))));
        var f = setup(h, chest); f.player().setGameMode(GameType.CREATIVE);
        h.assertTrue(f.place().consumesAction(), "Creative backed chest placement failed");
        var block = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(new BlockPos(2,2,2));
        h.assertTrue(block.getItem(0).is(Items.DIAMOND) && block.getItem(0).getCount() == 3 && f.stock() == 6
                && f.record().state().cells().getFirst().filter().equals(f.key()), "Creative placement copied material or erased precise components"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void nativePalletSelectionSurvivesHotbarButClearsOnCacheSwitchAndRejectsDistance(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE)); f.player().getInventory().selected = 1; PalletService.tick(f.player());
        h.assertTrue(PalletService.choice(f.player()) != null, "Hotbar change forgot selection"); f.player().getInventory().selected = 0;
        f.player().setPos(h.absoluteVec(new Vec3(100, 2, 100))); h.assertTrue(f.place() == InteractionResult.FAIL, "Distant use bypassed reach check"); f.unchanged();
        TestPlayers.necklace(f.player()).setStackInSlot(0, FmpRegistries.PENDANT.toStack()); PalletService.tick(f.player());
        h.assertTrue(PalletService.choice(f.player()) == null, "Another cache inherited the previous block choice"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void nativePalletUsesExactAdventurePlacementPermission(GameTestHelper h) {
        var denied = setup(h, new ItemStack(Items.STONE)); denied.player().setGameMode(GameType.ADVENTURE);
        h.assertTrue(denied.place() == InteractionResult.FAIL, "Plain cached stone bypassed Adventure restrictions"); denied.unchanged();
        var allowed = new ItemStack(Items.STONE);
        allowed.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(java.util.List.of(
                net.minecraft.advancements.critereon.BlockPredicate.Builder.block().of(Blocks.STONE).build()), true));
        var f = setup(h, allowed); f.player().setGameMode(GameType.ADVENTURE);
        h.assertTrue(f.place().consumesAction() && f.stock() == 6, "Allowed exact cached variant lost its Adventure permission"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void nativePalletPreservesTrapdoorInteractionBeforePlacement(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE)); h.setBlock(2,1,2,Blocks.OAK_TRAPDOOR);
        h.assertTrue(f.place().consumesAction()
                && h.getBlockState(new BlockPos(2,1,2)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && h.getBlockState(new BlockPos(2,2,2)).isAir() && f.stock() == 7, "Tool replaced native block interaction with a paid placement"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectedPalletSelectionCannotFallBackToAnotherBlock(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.STONE));
        PalletService.cancelChoice(f.player(),java.util.UUID.randomUUID());
        h.assertTrue(PalletService.choice(f.player()) != null,"Stale selection response cleared a different cache");
        PalletService.cancelChoice(f.player(),f.handle().cacheId());
        h.assertTrue(f.place() == InteractionResult.FAIL,"Rejected selection reused the previous block");f.unchanged();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void palletSurvivalRecipeLoadsAndUnlocksFromShaft(GameTestHelper h) {
        var inputs = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(9,ItemStack.EMPTY));
        inputs.set(1,new ItemStack(Items.IRON_INGOT));inputs.set(7,new ItemStack(Items.IRON_INGOT));
        inputs.set(3,com.simibubi.create.AllBlocks.SHAFT.asStack());inputs.set(5,com.simibubi.create.AllBlocks.SHAFT.asStack());
        inputs.set(4,new ItemStack(Items.OAK_PLANKS));
        var grid = net.minecraft.world.item.crafting.CraftingInput.of(3,3,inputs);
        var recipe = h.getLevel().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,grid,h.getLevel()).orElseThrow();
        var output = recipe.value().assemble(grid,h.getLevel().registryAccess());
        h.assertTrue(output.is(FmpRegistries.POCKET_PALLET.get()) && output.getCount()==1,"Workbench inputs did not produce one pallet");
        var p=TestPlayers.real(h,java.util.UUID.randomUUID());p.initInventoryMenu();
        p.getInventory().add(com.simibubi.create.AllBlocks.SHAFT.asStack());p.inventoryMenu.broadcastChanges();
        h.assertTrue(p.getRecipeBook().contains(recipe.id()),"Native shaft inventory event did not unlock the pallet recipe");h.succeed();
    }
}
