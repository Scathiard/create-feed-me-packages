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
    public static void palletChoiceSurvivesNativePlayerSaveAndFreshPlayerLoad(GameTestHelper h) {
        var player = TestPlayers.real(h, java.util.UUID.randomUUID());
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        player.setItemInHand(InteractionHand.MAIN_HAND, FmpRegistries.POCKET_PALLET.toStack());
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer());
        var exact = new ItemStack(Items.CHEST);
        exact.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Remember this chest"));
        var key = ItemVariantKey.of(exact, player.registryAccess());
        var old = ledger.find(handle.cacheId()); var edit = old.state().edit();
        edit.filter(0, key); edit.insert(0, key, 2);
        ledger.replace(handle, old.state().revision(), old.withState(edit.finish()));
        h.assertTrue(PalletService.choose(player, handle.cacheId(), key.encoded()), "Could not select exact saved material");
        var players = TestPlayers.nativeSavePlayers(player.getServer()); players.add(player);
        try { player.getServer().getPlayerList().saveAll(); } finally { players.remove(player); }
        PalletService.forget(player);
        var restored = TestPlayers.real(h, player.getUUID());
        h.assertTrue(player.getServer().getPlayerList().load(restored).isPresent(), "Native player file missing");
        var choice = PalletService.choice(restored);
        h.assertTrue(choice != null && choice.cache().equals(handle.cacheId()) && key.equals(choice.key()),
                "Restart forgot the selected material or its exact components");
        h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 2,
                "Remembering selection moved material");
        h.succeed();
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
    public static void savedPalletChoiceRetainsEmptyStockButNeverRevivesAfterCacheSwitchOrRejection(GameTestHelper h) {
        var f=setup(h,new ItemStack(Items.STONE));var originalPendant=TestPlayers.necklace(f.player()).getStackInSlot(0);
        var old=f.record();var edit=old.state().edit();edit.extract(0,7);
        f.ledger().replace(f.handle(),old.state().revision(),old.withState(edit.finish()));
        PalletService.forget(f.player());
        h.assertTrue(PalletService.choice(f.player()).key().equals(f.key()) && !f.place().consumesAction() && f.stock()==0,
                "Empty saved choice disappeared or created material");
        TestPlayers.necklace(f.player()).setStackInSlot(0,FmpRegistries.PENDANT.toStack());PalletService.tick(f.player());
        TestPlayers.necklace(f.player()).setStackInSlot(0,originalPendant);PalletService.forget(f.player());
        h.assertTrue(PalletService.choice(f.player())==null,"Switching caches revived an old saved choice");
        old=f.record();edit=old.state().edit();edit.insert(0,f.key(),2);
        f.ledger().replace(f.handle(),old.state().revision(),old.withState(edit.finish()));
        h.assertTrue(PalletService.choose(f.player(),f.handle().cacheId(),f.key().encoded()),"Cannot select restocked material");
        PalletService.cancelChoice(f.player(),f.handle().cacheId());PalletService.forget(f.player());
        h.assertTrue(PalletService.choice(f.player())==null,"Rejected selection revived after reload");h.succeed();
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
    public static void palletAssemblyReplacesCheapWorkbenchAndRecognizesGrip(GameTestHelper h) {
        var inputs = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(9,ItemStack.EMPTY));
        inputs.set(1,new ItemStack(Items.IRON_INGOT));inputs.set(7,new ItemStack(Items.IRON_INGOT));
        inputs.set(3,com.simibubi.create.AllBlocks.SHAFT.asStack());inputs.set(5,com.simibubi.create.AllBlocks.SHAFT.asStack());
        inputs.set(4,new ItemStack(Items.OAK_PLANKS));
        var grid = net.minecraft.world.item.crafting.CraftingInput.of(3,3,inputs);
        var manager=h.getLevel().getRecipeManager();
        h.assertTrue(manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,grid,h.getLevel()).isEmpty(),"Obsolete cheap workbench recipe is still available");
        var recipe=manager.byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"pocket_pallet")).orElseThrow();
        h.assertTrue(recipe.value() instanceof com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe
                && recipe.value().getResultItem(h.getLevel().registryAccess()).is(FmpRegistries.POCKET_PALLET.get()),"New assembly does not produce a pallet");
        var p=TestPlayers.real(h,java.util.UUID.randomUUID());p.initInventoryMenu();
        p.getInventory().add(com.simibubi.create.AllItems.EXTENDO_GRIP.asStack());p.inventoryMenu.broadcastChanges();
        var advancement=p.getServer().getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"recipes/pocket_pallet"));
        h.assertTrue(advancement!=null && p.getAdvancements().getOrStartProgress(advancement).isDone(),"Extendo grip inventory event did not complete the recipe discovery criterion");
        // Create processing recipes are special recipes, not workbench recipe-book entries.
        h.assertTrue(recipe.value().isSpecial() && !p.getRecipeBook().contains(recipe.id()),"Assembly leaked into the workbench book");h.succeed();
    }

    @GameTest(template="empty")
    public static void nativeEyeAndAndesiteInteractionsUseCachedMaterial(GameTestHelper h) {
        var f=setup(h,new ItemStack(Items.ENDER_EYE));h.setBlock(2,1,2,Blocks.END_PORTAL_FRAME);
        h.assertTrue(f.place().consumesAction() && h.getBlockState(new BlockPos(2,1,2)).getValue(EndPortalFrameBlock.HAS_EYE)
                && f.stock()==6 && f.player().getMainHandItem()==f.tool(),"Eye insertion did not use exactly one cache eye");
        h.assertTrue(!f.place().consumesAction() && f.stock()==6,"Filled frame consumed another eye; stock="+f.stock());
        var alloy=setup(h,com.simibubi.create.AllItems.ANDESITE_ALLOY.asStack());h.setBlock(2,1,2,Blocks.STRIPPED_OAK_LOG);
        h.assertTrue(alloy.place().consumesAction() && h.getBlockState(new BlockPos(2,1,2)).is(com.simibubi.create.AllBlocks.ANDESITE_CASING.get())
                && alloy.stock()==6 && alloy.player().getMainHandItem()==alloy.tool(),"Create native casing interaction lost its material hand");h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeTimedFoodCancelFullHungerAndUnequipConserveItems(GameTestHelper h) throws Exception {
        var f=setup(h,new ItemStack(Items.BREAD));var p=f.player();p.getFoodData().setFoodLevel(10);
        h.assertTrue(p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND).consumesAction() && p.isUsingItem()
                && f.stock()==7 && CacheUseLease.reserved(f.handle().cacheId(),0)==1,"Food must reserve without instantly eating");
        nativeTicks(p,8);p.releaseUsingItem();PalletService.tick(p);
        h.assertTrue(f.stock()==7 && p.getFoodData().getFoodLevel()==10 && CacheUseLease.reserved(f.handle().cacheId(),0)==0,"Canceled food consumed or leaked a lease");
        p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND);nativeTicks(p,40);
        h.assertTrue(f.stock()==6 && p.getFoodData().getFoodLevel()>10 && p.getMainHandItem()==f.tool(),"Native timed food did not finish with the tool intact");
        p.getFoodData().setFoodLevel(20);p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND);
        h.assertTrue(!p.isUsingItem() && f.stock()==6,"Full hunger bypassed food's native refusal");
        p.getFoodData().setFoodLevel(10);p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND);
        TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);nativeTicks(p,40);
        h.assertTrue(f.stock()==6 && !p.isUsingItem() && p.getFoodData().getFoodLevel()==10,"Unequipped timed food still finished");h.succeed();
    }
    private static void nativeTicks(ServerPlayer p,int ticks) throws Exception {
        var method=net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("updatingUsingItem");method.setAccessible(true);
        for(int i=0;i<ticks;i++) { p.tickCount++;method.invoke(p);PalletService.tick(p); }
    }
    @GameTest(template="empty")
    public static void nativeDrinkAndThrownPotionPreserveComponentsAndRemainders(GameTestHelper h) throws Exception {
        var potion=new ItemStack(Items.POTION);potion.set(DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.LONG_SWIFTNESS));
        var f=setup(h,potion);long before=f.stock();var p=f.player();p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND);nativeTicks(p,40);
        h.assertTrue(f.stock()==before-1 && p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED)
                && p.getInventory().countItem(Items.GLASS_BOTTLE)==1 && p.getMainHandItem()==f.tool(),"Drink lost effect, bottle or conservation: stock="+f.stock()+" effect="+p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED)+" bottles="+p.getInventory().countItem(Items.GLASS_BOTTLE)+" tool="+(p.getMainHandItem()==f.tool())+" using="+p.isUsingItem()+" remaining="+p.getUseItemRemainingTicks());
        var splash=new ItemStack(Items.SPLASH_POTION);splash.set(DataComponents.POTION_CONTENTS,potion.get(DataComponents.POTION_CONTENTS));
        var thrown=setup(h,splash);long beforeThrow=thrown.stock();h.assertTrue(thrown.player().gameMode.useItem(thrown.player(),thrown.player().level(),thrown.tool(),InteractionHand.MAIN_HAND).consumesAction()
                && thrown.stock()==beforeThrow-1 && thrown.player().getInventory().countItem(Items.GLASS_BOTTLE)==0,"Thrown potion followed the drink path");
        var entities=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownPotion.class,new AABB(thrown.player().blockPosition()).inflate(4));
        h.assertTrue(entities.stream().anyMatch(e->e.getOwner()==thrown.player() && e.getItem().get(DataComponents.POTION_CONTENTS).equals(splash.get(DataComponents.POTION_CONTENTS))),"Thrown potion components changed");h.succeed();
    }
    @GameTest(template="empty")
    public static void realBucketReturnsNativeEmptyAndFullOutputRefuses(GameTestHelper h) {
        var f=setup(h,new ItemStack(Items.WATER_BUCKET));long before=f.stock();var p=f.player();p.setXRot(60);p.setYRot(0);
        // BucketItem uses the native eye ray even when invoked through useItem.
        h.setBlock(2,0,2,Blocks.STONE);h.setBlock(2,0,3,Blocks.STONE);
        h.assertTrue(p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND).consumesAction()
                && f.stock()==before-1 && p.getInventory().countItem(Items.BUCKET)==1 && p.getMainHandItem()==f.tool(),"Real water bucket did not return its empty bucket: stock="+f.stock()+" buckets="+p.getInventory().countItem(Items.BUCKET)+" hand="+p.getMainHandItem()+" target="+p.pick(5,0,false));
        for(int i=1;i<36;i++) p.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        h.assertTrue(p.gameMode.useItem(p,p.level(),f.tool(),InteractionHand.MAIN_HAND)==InteractionResult.FAIL && f.stock()==before-1,"Full remainder destination spent another bucket");h.succeed();
    }
    @GameTest(template="empty")
    public static void pureFluidPlacesWithoutBucketAndHonorsProtection(GameTestHelper h) {
        var f=setup(h,new ItemStack(Items.STONE));var p=f.player();
        var water=new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,2500);
        InteractionTests.seedFluid(p,1,water);
        var key=dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(water,p.registryAccess());
        h.assertTrue(PalletService.choose(p,f.handle().cacheId(),"fluid:"+key.encoded()),"Fluid was not selectable");
        event(f,e->e.setCanceled(true),()->h.assertTrue(f.place()==InteractionResult.FAIL,"Protected pure fluid placement succeeded"));
        h.assertTrue(InteractionTests.fluidStock(p,1)==2500 && h.getBlockState(new BlockPos(2,2,2)).isAir(),"Canceled liquid changed stock/world");
        h.assertTrue(f.place().consumesAction() && InteractionTests.fluidStock(p,1)==1500
                && h.getBlockState(new BlockPos(2,2,2)).getFluidState().isSource() && p.getInventory().countItem(Items.BUCKET)==0
                && p.getMainHandItem()==f.tool(),"Pure liquid created a bucket or lost native source placement");h.succeed();
    }

    @GameTest(template="empty")
    public static void pureFluidFillsNativeTankAndRevokesDuringPermissionCallback(GameTestHelper h) {
        var f=setup(h,new ItemStack(Items.STONE));var p=f.player();
        var water=new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,2500);
        InteractionTests.seedFluid(p,1,water);var key=dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(water,p.registryAccess());
        PalletService.choose(p,f.handle().cacheId(),"fluid:"+key.encoded());
        h.setBlock(2,1,2,com.simibubi.create.AllBlocks.FLUID_TANK.get());
        var target=h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,h.absolutePos(new BlockPos(2,1,2)),Direction.UP);
        h.assertTrue(target!=null,"Native tank fluid capability missing");
        h.assertTrue(f.place().consumesAction() && target.getFluidInTank(0).getAmount()==1000 && InteractionTests.fluidStock(p,1)==1500
                && p.getInventory().countItem(Items.BUCKET)==0,"Pure fluid did not transfer actual liquid into native target");
        h.setBlock(2,1,2,Blocks.STONE);
        event(f,e->TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY),()->h.assertTrue(f.place()==InteractionResult.FAIL,"Fluid permission callback lost access but still placed"));
        h.assertTrue(f.record().fluids().state().cells().get(1).amount()==1500 && h.getBlockState(new BlockPos(2,2,2)).isAir(),"Revoked fluid transfer left a mutation");h.succeed();
    }
}
