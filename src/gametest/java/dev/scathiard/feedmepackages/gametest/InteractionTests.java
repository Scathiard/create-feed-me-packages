package dev.scathiard.feedmepackages.gametest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CacheActions.Action;
import dev.scathiard.feedmepackages.interaction.CacheActions.Result;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class InteractionTests {
    @GameTest(template = "empty")
    public static void blockHitDoesNotAccidentallyClearHeldBinding(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 10); var player = f.player(); var before = f.record();
        var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var network = UUID.randomUUID(); pendant.set(FmpRegistries.NETWORK.get(), network); player.setItemInHand(InteractionHand.MAIN_HAND, pendant);
        player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 30, 2.5))); player.setXRot(0); player.xRotO = 0; player.setYRot(0); player.yRotO = 0;
        player.setShiftKeyDown(true);
        var target = net.minecraft.core.BlockPos.containing(player.getEyePosition().add(0, 0, 2));
        helper.getLevel().setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var ray = net.minecraft.world.item.Item.getPlayerPOVHitResult(helper.getLevel(), player, net.minecraft.world.level.ClipContext.Fluid.NONE);
        helper.assertTrue(ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && ray.getBlockPos().equals(target),
                "Fixture did not aim at its real block: eye=" + player.getEyePosition() + " vector=" + player.getLookAngle()
                        + " hit=" + ray.getType() + ":" + ray.getBlockPos() + " target=" + target);
        helper.assertTrue(pendant.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult() == net.minecraft.world.InteractionResult.PASS
                && network.equals(pendant.get(FmpRegistries.NETWORK.get())) && f.record() == before, "A block hit cleared binding or changed cache data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlySneakAirUseClearsBindingAndNeverCache(GameTestHelper h) {
        var f=ReceiveTests.setup(h,10); var p=f.player(); var before=f.record();
        var pendant=TestPlayers.necklace(p).getStackInSlot(0);TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);
        var network=UUID.randomUUID();pendant.set(FmpRegistries.NETWORK.get(),network);p.setItemInHand(InteractionHand.MAIN_HAND,pendant);
        var expected=pendant.copy();expected.remove(FmpRegistries.NETWORK.get());
        p.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5,30,2.5)));p.setYRot(0);p.setXRot(-90);
        p.setShiftKeyDown(false);
        h.assertTrue(p.gameMode.useItem(p,h.getLevel(),pendant,InteractionHand.MAIN_HAND)==net.minecraft.world.InteractionResult.PASS
                && network.equals(pendant.get(FmpRegistries.NETWORK.get())),"Ordinary air use cleared the network");
        p.setShiftKeyDown(true);p.setXRot(0);
        var target=net.minecraft.world.entity.EntityType.ARMOR_STAND.create(h.getLevel());
        target.setPos(p.position().add(0,0,2));h.getLevel().addFreshEntity(target);
        h.assertTrue(pendant.getItem().use(h.getLevel(),p,InteractionHand.MAIN_HAND).getResult()==net.minecraft.world.InteractionResult.PASS
                && network.equals(pendant.get(FmpRegistries.NETWORK.get())),"Entity aim was treated as air");target.discard();
        p.setXRot(-90);
        h.assertTrue(p.gameMode.useItem(p,h.getLevel(),pendant,InteractionHand.MAIN_HAND).consumesAction()
                && !pendant.has(FmpRegistries.NETWORK.get()),"Sneak air use did not clear the binding");
        h.assertTrue(ItemStack.isSameItemSameComponents(expected,pendant) && f.record()==before,"Clearing a network altered cache identity or inventory");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void curioEquipRejectsSecondPendantBeforeCreatingAnyCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        TestPlayers.necklace(player).grow(2);
        for (var candidate : java.util.List.of(FmpRegistries.PENDANT.toStack())) {
            var curio = top.theillusivec4.curios.api.CuriosApi.getCurio(candidate).orElseThrow();
            var replacement = new top.theillusivec4.curios.api.SlotContext("necklace", player, 0, false, true);
            var extra = new top.theillusivec4.curios.api.SlotContext("necklace", player, 2, false, true);
            helper.assertTrue(curio.canEquip(replacement) && !curio.canEquip(extra), "Curios accepted a second pendant or blocked a same-slot replacement");
            helper.assertTrue(!curio.canEquip(new top.theillusivec4.curios.api.SlotContext("necklace", player, 2, true, true)), "Cosmetic equip granted a functional entry");
        }
        var ordinary = TestPlayers.necklace(player).getStackInSlot(0); ordinary.set(FmpRegistries.DISABLED.get(), true);
        var second = FmpRegistries.PENDANT.toStack(); TestPlayers.necklace(player).setStackInSlot(2, second);
        helper.assertTrue(AccessGate.resolve(player).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && !ordinary.has(FmpRegistries.IDENTITY.get()) && !second.has(FmpRegistries.IDENTITY.get()), "Multiple gate ran after cache creation or honored the retired disable flag");
        TestPlayers.necklace(player).setStackInSlot(2, ItemStack.EMPTY);
        helper.assertTrue(AccessGate.resolve(player).active(), "Removing extra pendant did not restore ordinary access");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void singleDepositUsesLegalServerAmountInSurvivalAndCreative(GameTestHelper helper) {
        for (boolean creative : new boolean[]{false, true}) {
            var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 2); var player = f.player(); var sent = TestPlayers.nativePackets(player);
            if (creative) {
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                PanelNetwork.query(player, new PanelPackets.Query(UUID.randomUUID(), 0, true));
            }
            var source = new ItemStack(Items.STONE, 32);
            for (int step = 0; step < 3; step++) {
                if (!creative) player.containerMenu.setCarried(source.copy());
                var command = intent(player, Action.DEPOSIT, 0, 1, -1, "");
                var result = creative ? CacheActions.executeCreative(player, command, f.key().encoded(), source.getCount()) : CacheActions.execute(player, command);
                helper.assertTrue(result == (step < 2 ? Result.OK : Result.NO_SPACE) && stock(player, 0) == Math.min(ReceiveTests.FULL, ReceiveTests.FULL - 1 + step), "Right-click did not deposit exactly one up to capacity");
                if (step < 2) {
                    source.shrink(1);
                    if (creative) {
                        var packet = cursorUpdate(sent);
                        var returned = ItemVariantKey.decode(packet.template(), player.registryAccess()).stack(player.registryAccess(), packet.count());
                        helper.assertTrue(ItemStack.matches(returned, source) && player.containerMenu.getCarried().isEmpty(), "Creative single deposit duplicated or lost cursor ownership");
                    } else helper.assertTrue(ItemStack.matches(player.containerMenu.getCarried(), source), "Survival single deposit changed the wrong quantity");
                    var replay = creative ? CacheActions.executeCreative(player, command, f.key().encoded(), source.getCount()) : CacheActions.execute(player, command);
                    helper.assertTrue(replay == Result.STALE, "Single deposit replay applied twice");
                }
            }
            if (creative) player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.containerMenu.setCarried(new ItemStack(Items.EGG, 16));
            helper.assertTrue(execute(player, Action.DEPOSIT, 1, 1, "") == Result.OK && stock(player, 1) == 1 && player.containerMenu.getCarried().getCount() == 15, "Empty cell single deposit ignored native 16-stack item");
            for (int bad : new int[]{-1, 2, Integer.MAX_VALUE}) helper.assertTrue(execute(player, Action.DEPOSIT, 1, bad, "") == Result.INVALID_REQUEST && stock(player, 1) == 1, "Unrecognized deposit amount accepted");
            helper.assertTrue(execute(player, Action.DEPOSIT, 1, 0, "") == Result.OK && stock(player, 1) == 16 && player.containerMenu.getCarried().isEmpty(), "Left deposit no longer fills from the real stack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeWithdrawalThenNativePlacementAndCloseDoesNotDuplicate(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 1); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var command = intent(player, Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(CacheActions.executeCreative(player, command, "", 0) == Result.OK
                && f.record().state().cells().getFirst().amount() == 1, "Creative withdrawal failed or deducted on preview");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.STONE)));
        player.connection.handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
        int total = player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(total == 1 && f.record().state().cells().getFirst().amount() == 0 && player.containerMenu.getCarried().isEmpty(),
                "Creative native placement/close duplicated material: inventory=" + total);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        helper.assertTrue(player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum() == 1,
                "Creative duplicate persisted into survival");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bindingUsesDifferentNativeNetworkDevicesWithoutChangingCache(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); var before = f.record();
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        f.player().setItemInHand(InteractionHand.MAIN_HAND, pendant);
        var linkPos = new net.minecraft.core.BlockPos(1, 1, 1); var tickerPos = new net.minecraft.core.BlockPos(3, 1, 1);
        helper.setBlock(linkPos, com.simibubi.create.AllBlocks.STOCK_LINK.get().defaultBlockState());
        helper.setBlock(tickerPos, com.simibubi.create.AllBlocks.STOCK_TICKER.get().defaultBlockState());
        helper.runAfterDelay(3, () -> {
            UUID prior = null;
            for (var position : java.util.List.of(linkPos, tickerPos)) {
                var absolute = helper.absolutePos(position);
                var behavior = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(helper.getLevel(), absolute,
                        com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.TYPE);
                helper.assertTrue(behavior != null, "Native fixture does not expose a logistics network behavior");
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(absolute), net.minecraft.core.Direction.UP, absolute, false);
                var context = new net.minecraft.world.item.context.UseOnContext(f.player(), InteractionHand.MAIN_HAND, hit);
                f.player().setShiftKeyDown(true);
                helper.assertTrue(pendant.getItem().useOn(context) == net.minecraft.world.InteractionResult.PASS
                        && java.util.Objects.equals(prior, pendant.get(FmpRegistries.NETWORK.get())), "Sneaking device use changed network binding");
                f.player().setShiftKeyDown(false);
                helper.assertTrue(f.player().gameMode.useItemOn(f.player(), helper.getLevel(), pendant, InteractionHand.MAIN_HAND, hit).consumesAction()
                        && behavior.freqId.equals(pendant.get(FmpRegistries.NETWORK.get())) && f.player().containerMenu == f.player().inventoryMenu,
                        "Ordinary device use did not bind before opening its menu");
                helper.assertTrue(pendant.hasFoil() && !pendant.isEnchanted(), "Network binding did not derive its foil without adding enchantments");
                prior = behavior.freqId;
            }
            f.player().setShiftKeyDown(true);
            f.player().setXRot(-90); f.player().xRotO = -90;
            f.player().setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2, 40, 2)));
            var airUse = pendant.getItem().use(helper.getLevel(), f.player(), InteractionHand.MAIN_HAND).getResult();
            helper.assertTrue(airUse.consumesAction() && !pendant.has(FmpRegistries.NETWORK.get()),
                    "Air use could not clear the binding: result=" + airUse + " ray=" + f.player().pick(5, 1, false).getType()
                            + " held=" + f.player().getMainHandItem() + " position=" + f.player().position());
            f.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); TestPlayers.necklace(f.player()).setStackInSlot(0, pendant);
            helper.assertTrue(execute(f.player(), Action.CLEAR_NETWORK, 0, 0, "") == Result.INVALID_REQUEST, "Retired unbind command still enabled");
            helper.assertTrue(!pendant.hasFoil(), "Cleared binding retained a synthetic foil");
            helper.assertTrue(f.record() == before && AccessGate.resolve(f.player()).active(), "Binding or clearing changed cache stock, orders or identity");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void duplicatesConflictsAndOrdinaryTransfersUseOneAuthoritativeCache(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); UUID network = UUID.randomUUID();
        pendant.set(FmpRegistries.NETWORK.get(), network); TestPlayers.necklace(f.player()).grow(1);
        var duplicate = pendant.copy(); TestPlayers.necklace(f.player()).setStackInSlot(1, duplicate);
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS, "Abnormal same-ID copies retained access");
        helper.assertTrue(execute(f.player(), Action.TAKE_CURSOR, 0, 7, "") == Result.NOT_ACTIVE && f.record().state().cells().getFirst().amount() == 32, "Multiple pendants changed stock");
        duplicate.set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && execute(f.player(), Action.TAKE_CURSOR, 0, 1, "") == Result.NOT_ACTIVE, "Network conflict did not pause cache business");
        helper.assertTrue(execute(f.player(), Action.TERMINAL_ENABLED, 1, 0, "") == Result.INVALID_REQUEST, "Retired disable command bypassed unique wear");
        var second = FmpRegistries.PENDANT.toStack(); second.set(FmpRegistries.NETWORK.get(), network); TestPlayers.necklace(f.player()).setStackInSlot(1, second);
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && execute(f.player(), Action.TAKE_CURSOR, 0, 1, "") == Result.NOT_ACTIVE, "Same-network different-cache ambiguity silently selected a cache");
        helper.assertTrue(!second.has(FmpRegistries.IDENTITY.get()), "Rejected multiple pendants created a cache identity");
        TestPlayers.necklace(f.player()).setStackInSlot(1, ItemStack.EMPTY);
        helper.assertTrue(AccessGate.resolve(f.player()).active(), "Removing the extra pendant did not restore access");
        var stable = f.record(); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        var recipient = TestPlayers.create(helper, pendant);
        helper.assertTrue(!AccessGate.resolve(f.player()).active() && AccessGate.resolve(recipient).handle().cacheId().equals(f.handle().cacheId())
                && f.record() == stable, "Ordinary pendant handoff changed its complete cache");
        TestPlayers.necklace(recipient).setStackInSlot(0, ItemStack.EMPTY);
        var lost = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), 0, 0, 0, pendant); lost.discard();
        helper.assertTrue(!AccessGate.resolve(recipient).active() && f.record() == stable, "Destroyed last legal entry manufactured another access path");
        TestPlayers.necklace(recipient).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(!AccessGate.resolve(recipient).handle().cacheId().equals(f.handle().cacheId()), "New pendant recovered a lost ordinary cache");
        helper.succeed();
    }

    private static CacheActions.Intent intent(ServerPlayer player, Action action, int slot, int first, int second, String template) {
        var view = CacheActions.open(player);
        return new CacheActions.Intent(view.session(), view.record() == null ? -1 : view.record().state().revision(), action, slot, first, second, template);
    }
    private static Result execute(ServerPlayer player, Action action, int slot, int first, String template) {
        return CacheActions.execute(player, intent(player, action, slot, first, -1, template));
    }
    private static int stock(ServerPlayer player, int slot) {
        var access = AccessGate.resolve(player); return CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells().get(slot).amount();
    }

    @GameTest(template = "empty")
    public static void depositsAndSmallWithdrawalsStayOnCursor(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 64));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK, "First deposit failed");
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 4));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK && stock(player, 0) == 68, "Repeat deposit changed or rejected same filter");
        helper.assertTrue(execute(player, Action.TAKE_CURSOR, 0, 64, "") == Result.OK && stock(player, 0) == 68 && player.containerMenu.getCarried().getCount() == 64, "Full stack transfer is wrong");
        // A cursor preview only becomes a real deduction when the items leave the cursor. Move all 64
        // into the backpack (empty bag slot 0 -> cursor clears) then let the panel settle via snapshot.
        player.getInventory().setItem(0, player.containerMenu.getCarried()); player.containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(player);
        helper.assertTrue(stock(player, 0) == 4, "Placed preview did not deduct (expected S=4)");
        helper.assertTrue(execute(player, Action.TAKE_CURSOR, 0, 64, "") == Result.OK && player.containerMenu.getCarried().getCount() == 4 && stock(player, 0) == 4, "Small remainder was dropped or lost");
        player.getInventory().setItem(1, player.containerMenu.getCarried()); player.containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(player);
        helper.assertTrue(stock(player, 0) == 0, "Placed remainder did not deduct (expected S=0)");
        helper.assertTrue(player.getInventory().getItem(0).getCount() + player.getInventory().getItem(1).getCount() == 68, "Cursor withdrawal did not conserve total");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inventoryInsertionHonorsNativeLimitsAndNoRoom(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        // Level 2: groupCapacity=4, egg(16) capacity=64 — matches the 4×16 deposit sequence
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        var edit = before.state().edit(); edit.upgrade(); ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        for (int i = 0; i < 4; i++) {
            player.containerMenu.setCarried(new ItemStack(Items.EGG, 16));
            helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK, "Native stack deposit failed");
        }
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().setItem(0, new ItemStack(Items.EGG, 15)); player.getInventory().setItem(1, ItemStack.EMPTY);
        var move = intent(player, Action.TAKE_INVENTORY, 0, Integer.MAX_VALUE, -1, "");
        helper.assertTrue(CacheActions.execute(player, move) == Result.OK, "Partial insertion failed");
        // Shift-take settles immediately: the inserted 17 (slot0 15->16 + slot1 empty->16) is deducted now.
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 16 && player.getInventory().getItem(1).getCount() == 16 && stock(player, 0) == 47, "Native 16 stack limit was ignored");
        helper.assertTrue(CacheActions.execute(player, move) == Result.STALE && stock(player, 0) == 47, "Replayed transfer consumed twice");
        helper.assertTrue(execute(player, Action.TAKE_INVENTORY, 0, 64, "") == Result.NO_SPACE && stock(player, 0) == 47, "No-room operation discarded stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void browserTemplatesCreateNoItemsAndRealFiltersRemainSafe(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()).encoded();
        helper.assertTrue(execute(player, Action.SET_GHOST, 0, 0, key) == Result.OK && stock(player, 0) == 0, "Template must set an empty filter without creating items");
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 3)); execute(player, Action.DEPOSIT, 0, 0, "");
        player.containerMenu.setCarried(new ItemStack(Items.STONE));
        helper.assertTrue(execute(player, Action.DEPOSIT, 1, 1, "") == Result.DUPLICATE_FILTER && stock(player, 1) == 0, "Duplicate real variant accepted");
        helper.assertTrue(execute(player, Action.CLEAR_FILTER, 0, 0, "") == Result.FILTER_OCCUPIED, "Nonempty filter cleared");
        var other = ItemVariantKey.of(new ItemStack(Items.DIRT), player.registryAccess()).encoded();
        helper.assertTrue(execute(player, Action.SET_GHOST, 0, 0, other) == Result.FILTER_OCCUPIED && stock(player, 0) == 3, "Template replaced an occupied filter");
        player.containerMenu.setCarried(new ItemStack(Items.DIRT));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 1, "") == Result.FILTER_OCCUPIED && stock(player, 0) == 3, "Nonempty real replacement discarded material");
        helper.assertTrue(CacheActions.execute(player, intent(player, Action.THRESHOLDS, 0, 129, -1, "")) == Result.INVALID_REQUEST, "Out-of-range threshold accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void closedAndUnwornSessionsCannotAct(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32);
        var command = intent(f.player(), Action.TAKE_CURSOR, 0, 16, -1, "");
        f.player().closeContainer();
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.STALE && f.record().state().cells().getFirst().amount() == 32, "Closed menu retained action authority");
        command = intent(f.player(), Action.TAKE_CURSOR, 0, 16, -1, "");
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.NOT_ACTIVE, "Unequipped player could use old panel");
        CacheActions.validateOpenContext(f.player()); TestPlayers.necklace(f.player()).setStackInSlot(0, pendant);
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.STALE && f.record().state().cells().getFirst().amount() == 32, "Reequip revived invalidated intent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void residualIsNotExposedByRetiredManualIntent(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); var box = f.box(32);
        helper.assertTrue(ReceiveService.receive(f.player(), box), "Residual setup failed");
        helper.assertTrue(f.record().state().pending(0) == 32, "One 32-item arrival must leave the other half of the 64-item request pending");
        var before = f.record();
        helper.assertTrue(execute(f.player(), Action.TAKE_RESIDUAL, 0, 0, "") == Result.INVALID_REQUEST && f.record() == before,
                "Retired manual residual command changed ownership");
        f.player().getInventory().setItem(0, ItemStack.EMPTY);
        ReceiveService.resume(f.player());
        helper.assertTrue(f.record() == before && f.player().getInventory().getItem(0).isEmpty(), "A valid full-cache remainder was ejected unnecessarily");
        helper.assertTrue(f.record().state().pending(0) == 32, "Manual opening repeated arrival accounting or erased an undelivered fragment");
        f.extract(24); ReceiveService.resume(f.player());
        helper.assertTrue(f.residual().isEmpty() && f.record().state().cells().getFirst().amount() == ReceiveTests.FULL, "Background resume lost material");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCursorHasExplicitServerPermission(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var sent = TestPlayers.nativePackets(player);
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()).encoded();
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, 0, true));
        var deposit = intent(player, Action.DEPOSIT, 0, 0, -1, "");
        player.getAbilities().instabuild = false;
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.INVALID_REQUEST && stock(player, 0) == 0, "Survival forged a creative item source");
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 65) == Result.INVALID_ITEM && stock(player, 0) == 0, "Illegal creative stack accepted");
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.OK && stock(player, 0) == 32
                && player.containerMenu.getCarried().isEmpty(), "Authorized creative cursor failed to deposit");
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.STALE && stock(player, 0) == 32, "Creative replay changed the cache twice");
        var take = intent(player, Action.TAKE_CURSOR, 0, 16, -1, "");
        helper.assertTrue(CacheActions.execute(player, take) == Result.INVALID_REQUEST && stock(player, 0) == 32,
                "Creative caller bypassed native cursor ownership with a survival-shaped command");
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0)).result() == Result.OK && stock(player, 0) == 32
                && player.containerMenu.getCarried().isEmpty(), "Creative withdrawal created another server-owned cursor");
        var packet = cursorUpdate(sent);
        helper.assertTrue(packet.count() == 16 && packet.remaining() == 16 && packet.takeSequence() == 1
                && packet.operationSequence() == 1 && packet.window().equals(window)
                && ItemVariantKey.decode(packet.template(), player.registryAccess()).stack(player.registryAccess(), 1).is(Items.STONE),
                "Creative withdrawal did not carry exact grant identity and amount");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelWindowAndServerSessionAreIndependent(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(f.player(), new PanelPackets.Query(window, f.player().containerMenu.containerId, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 16, -1, "");
        var wrongWindow = new PanelPackets.Command(UUID.randomUUID(), 1, take, false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), wrongWindow) == null && stock(f.player(), 0) == 32, "Wrong window submitted an operation");
        var packet = new PanelPackets.Command(window, 1, take, false, "", 0);
        var reply = PanelNetwork.command(f.player(), packet);
        helper.assertTrue(reply.result() == Result.OK && reply.acknowledged() == 1 && stock(f.player(), 0) == 32, "Network action did not acknowledge its actual commit");
        // Settle the cursor preview by placing all 16 into the backpack and letting the panel validate.
        f.player().getInventory().setItem(0, f.player().containerMenu.getCarried()); f.player().containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(f.player());
        helper.assertTrue(stock(f.player(), 0) == 16, "Network take did not settle (expected S=16)");
        helper.assertTrue(PanelNetwork.command(f.player(), packet).result() == Result.STALE && stock(f.player(), 0) == 16, "Network replay consumed twice");
        f.player().tickCount += 4; UUID nextWindow = UUID.randomUUID();
        var next = PanelNetwork.query(f.player(), new PanelPackets.Query(nextWindow, f.player().containerMenu.containerId, true));
        PanelNetwork.query(f.player(), new PanelPackets.Query(window, 0, false));
        var preference = new CacheActions.Intent(next.session(), next.revision(), Action.PREFERENCE, 0, 1, -1, "");
        helper.assertTrue(PanelNetwork.command(f.player(), new PanelPackets.Command(nextWindow, 2, preference, false, "", 0)).result() == Result.INVALID_REQUEST,
                "Late close from old window revoked the new window");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releasePreviewIsAcceptedThroughWire(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(f.player(), new PanelPackets.Query(window, f.player().containerMenu.containerId, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 16, -1, "");
        helper.assertTrue(PanelNetwork.command(f.player(), new PanelPackets.Command(window, 1, take, false, "", 0)).result() == Result.OK
                && stock(f.player(), 0) == 32 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 16,
                "Preview take through wire failed");
        // A survival TAKE (creativeCursor=false) is NOT the creative hold the release path clears; the
        // public sequence entry is still exercised (0/sequence/replay STALE), but the release itself is
        // STALE for a non-creative hold — the survival fixture is never made to look like a creative one.
        var stale = new PanelPackets.Command(window, 0, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), stale).result() == Result.STALE
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 16,
                "Sequence-0 release was not STALE / released the hold");
        // A correct first target on a NON-creative hold -> STALE (survival is not the creative release path).
        var ok = new PanelPackets.Command(window, 2, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), ok).result() == Result.STALE
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 16,
                "Survival hold was released (must be STALE)");
        // Replay of the same release sequence is STALE (replay guard retained).
        helper.assertTrue(PanelNetwork.command(f.player(), ok).result() == Result.STALE, "Release replay was not STALE");
        f.player().containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releasePreviewAcceptedForCreativeHold(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, player.containerMenu.containerId, true));
        // Real creative TAKE through the wire (creativeCursor=true) -> creative hold P=1, S stays 3.
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0)).result() == Result.OK
                && stock(player, 0) == 3 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1,
                "Creative take through wire failed");
        // RELEASE_PREVIEW release of the creative hold (first=1, the take's seq) -> reserved 0, cache stays 3.
        var rel = new PanelPackets.Command(window, 2, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(player, rel).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0
                && stock(player, 0) == 3, "Creative hold release failed to clear the reservation");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeDropSettlesThroughWire(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var view = CacheActions.open(player);
        var take = new CacheActions.Intent(view.session(), view.record().state().revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(CacheActions.executeCreative(player, take, "", 0) == Result.OK
                && stock(player, 0) == 3
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1, "preview take failed");
        // Negative slot = creative discard: creativeDropped debits the preview and drops a real ItemEntity,
        // so the cache is NOT silently reduced with no world entity (the risk my negative-slot cancel caused).
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.STONE)));
        helper.assertTrue(stock(player, 0) == 2
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0,
                "creative drop did not settle cache/hold (S=" + stock(player, 0) + ")");
        int dropped = 0;
        for (var e : helper.getLevel().getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.getItem().is(Items.STONE)) dropped += ie.getItem().getCount();
        }
        helper.assertTrue(dropped >= 1, "creative drop did not leave the real item (dropped=" + dropped + ")");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oldReleaseDoesNotTouchNewerHold(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, player.containerMenu.containerId, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        // TAKE A (cmd seq 1) -> creative hold requestSeq=1.
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0)).result() == Result.OK
                && stock(player, 0) == 3 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1, "TAKE A failed");
        // RELEASE A (cmd seq 2, first=1) releases the A hold.
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 2, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, ""), false, "", 0)).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0 && stock(player, 0) == 3, "RELEASE A failed");
        // TAKE B (cmd seq 3) -> creative hold requestSeq=3.
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 3, take, true, "", 0)).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1 && stock(player, 0) == 3, "TAKE B failed");
        // A NEW command (seq 4, first=1) releasing A again -> STALE, B untouched.
        var relA = PanelNetwork.command(player, new PanelPackets.Command(window, 4, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, ""), false, "", 0));
        helper.assertTrue(relA.result() == Result.STALE
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1 && stock(player, 0) == 3,
                "Old RELEASE A was not STALE / touched B (reserved=" + dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) + ")");
        // RELEASE B (seq 5, first=3) succeeds.
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 5, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 3, -1, ""), false, "", 0)).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0 && stock(player, 0) == 3, "RELEASE B failed");
        // Replay seq 5 -> STALE (rejection, inventory unchanged).
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 5, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 3, -1, ""), false, "", 0)).result() == Result.STALE
                && stock(player, 0) == 3, "Replay RELEASE B was not STALE");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeMixedSourceAndIndependentOperations(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, 0, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 3, -1, "");
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0)).result() == Result.OK, "take");
        String stone = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()).encoded();
        // Actual independent creative-list drop must not consume the still-held preview.
        helper.assertTrue(creativeOp(player, window, view, 2, Action.CREATIVE_BEGIN, 3, 1, 2, stone) == Result.OK, "independent begin");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.STONE)));
        helper.assertTrue(creativeOp(player, window, view, 3, Action.CREATIVE_END, 3, 1, 2, stone) == Result.OK && stock(player, 0) == 3, "list drop charged cache");
        // Same-item list increment: P3 + independent1. END changes no cache stock or preview count.
        helper.assertTrue(creativeOp(player, window, view, 4, Action.CREATIVE_BEGIN, 3, 1, 1, stone) == Result.OK, "list begin");
        helper.assertTrue(creativeOp(player, window, view, 5, Action.CREATIVE_END, 4, 1, 4, stone) == Result.OK, "list end");
        helper.assertTrue(dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 3, "increment released P3");
        // First right placement transfers only real1; a second placement transfers preview1.
        helper.assertTrue(creativeOp(player, window, view, 6, Action.CREATIVE_BEGIN, 4, 1, 0, stone) == Result.OK, "place begin");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.STONE)));
        helper.assertTrue(creativeOp(player, window, view, 7, Action.CREATIVE_END, 3, 1, 6, stone) == Result.OK && stock(player, 0) == 3, "real1 charged cache");
        helper.assertTrue(creativeOp(player, window, view, 8, Action.CREATIVE_BEGIN, 3, 1, 0, stone) == Result.OK, "second begin");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.STONE, 2)));
        helper.assertTrue(creativeOp(player, window, view, 9, Action.CREATIVE_END, 2, 1, 8, stone) == Result.OK && stock(player, 0) == 2, "preview1 not charged");
        // Local list shrink releases another alias; it does not destroy its cache stock.
        helper.assertTrue(creativeOp(player, window, view, 10, Action.CREATIVE_BEGIN, 2, 1, 1, stone) == Result.OK, "shrink begin");
        helper.assertTrue(creativeOp(player, window, view, 11, Action.CREATIVE_END, 1, 1, 10, stone) == Result.OK
                && stock(player, 0) == 2 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1, "shrink lost stock");
        CacheActions.close(player);
        helper.assertTrue(stock(player, 0) == 2 && player.getInventory().getItem(0).getCount() == 2, "mixed conservation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectedNativeCreativeDropRestoresOnlyItsOperation(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        var sent = TestPlayers.nativePackets(player); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        // Exhaust the vanilla drop budget through its actual packet handler.
        for (int i = 0; i < 11; i++) player.connection.handleSetCreativeModeSlot(
                new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.DIRT)));
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, 0, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 3, -1, "");
        PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0));
        String stone = f.key().encoded();
        helper.assertTrue(creativeOp(player, window, view, 2, Action.CREATIVE_BEGIN, 3, 1, 0, stone) == Result.OK, "drop begin");
        long before = java.util.stream.StreamSupport.stream(helper.getLevel().getAllEntities().spliterator(), false)
                .filter(e -> e instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.STONE)).count();
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.STONE, 3)));
        helper.assertTrue(creativeOp(player, window, view, 3, Action.CREATIVE_END, 0, 1, 2, "") == Result.OK, "drop end");
        var reply = cursorUpdate(sent);
        helper.assertTrue(stock(player, 0) == 3 && reply.count() == 3 && reply.remaining() == 3
                && reply.takeSequence() == 1 && reply.operationSequence() == 3, "rejection lost stock or restored wrong identity");
        long after = java.util.stream.StreamSupport.stream(helper.getLevel().getAllEntities().spliterator(), false)
                .filter(e -> e instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.STONE)).count();
        helper.assertTrue(before == after, "rejected drop produced a stone entity");
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.CursorUpdate.CODEC.encode(wire, reply);
            helper.assertTrue(PanelPackets.CursorUpdate.CODEC.decode(wire).equals(reply), "cursor correlation codec changed identity");
        } finally { wire.release(); }
        CacheActions.close(player); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void previewCleanupSurvivesOrdinaryRateLimit(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, 0, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 3, -1, "");
        PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0));
        for (int sequence = 2; sequence < 22; sequence++) {
            var bad = new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 0, -1, "");
            PanelNetwork.command(player, new PanelPackets.Command(window, sequence, bad, false, "", 0));
        }
        helper.assertTrue(dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 3, "invalid target canceled hold");
        var release = new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, 1, -1, "");
        PanelNetwork.command(player, new PanelPackets.Command(window, 22, release, false, "", 0));
        helper.assertTrue(stock(player, 0) == 3 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0, "budget dropped cleanup");
        var deposit = new CacheActions.Intent(view.session(), view.revision(), Action.DEPOSIT, 0, 0, -1, "");
        var limited = PanelNetwork.command(player, new PanelPackets.Command(window, 23, deposit, true, f.key().encoded(), 1));
        var closed = PanelNetwork.command(player, new PanelPackets.Command(UUID.randomUUID(), 24, deposit, true, f.key().encoded(), 1));
        helper.assertTrue(limited != null && limited.result() == Result.TOO_COMPLEX && closed != null && closed.result() == Result.STALE
                && stock(player, 0) == 3, "real input got no explicit refusal or was consumed");
        helper.succeed();
    }

    private static PanelPackets.CursorUpdate cursorUpdate(java.util.List<net.minecraft.network.protocol.Packet<?>> sent) {
        return sent.stream().filter(p -> p instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket)
                .map(p -> ((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket)p).payload())
                .filter(p -> p instanceof PanelPackets.CursorUpdate).map(p -> (PanelPackets.CursorUpdate)p).toList().getLast();
    }

    private static Result creativeOp(net.minecraft.server.level.ServerPlayer player, UUID window, PanelPackets.Snapshot view,
            int sequence, Action action, int count, int take, int detail, String template) {
        var intent = new CacheActions.Intent(view.session(), view.revision(), action, count, take, detail, count == 0 ? "" : template);
        return PanelNetwork.command(player, new PanelPackets.Command(window, sequence, intent, false, "", 0)).result();
    }

    @GameTest(template = "empty")
    public static void panelWireIsBoundedAndRoundTripsLargeTemplates(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var access = AccessGate.resolve(player);
        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        var edit = before.state().edit(); for (int level = 1; level < 5; level++) edit.upgrade();
        for (int i = 0; i < 36; i++) {
            var item = new ItemStack(Items.STONE); item.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(860) + i));
            var key = ItemVariantKey.of(item, player.registryAccess()); edit.filter(i, key); edit.insert(i, key, 2048);
        }
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        var view = PanelNetwork.query(player, new PanelPackets.Query(UUID.randomUUID(), 0, true));
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.Snapshot.CODEC.encode(wire, view);
            helper.assertTrue(wire.readableBytes() < PanelPackets.S2C_LIMIT, "Largest current cache exceeded snapshot budget");
            var decoded = PanelPackets.Snapshot.CODEC.decode(wire);
            helper.assertTrue(decoded.equals(view) && decoded.cells().size() == 36, "Wire lost or truncated exact templates");
        } finally { wire.release(); }
        var oversized = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            oversized.writeZero(PanelPackets.C2S_LIMIT + 1); boolean refused = false;
            try { PanelPackets.Command.CODEC.decode(oversized); } catch (DecoderException expected) { refused = true; }
            helper.assertTrue(refused, "Oversized command reached allocation/interpretation");
        } finally { oversized.release(); }
        var trailing = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.Query.CODEC.encode(trailing, new PanelPackets.Query(UUID.randomUUID(), 0, true)); trailing.writeByte(0);
            boolean refused = false; try { PanelPackets.Query.CODEC.decode(trailing); } catch (DecoderException expected) { refused = true; }
            helper.assertTrue(refused, "Trailing packet data accepted");
        } finally { trailing.release(); }
        var change=new CacheActions.SettingsChange(0,view.cells().getFirst().filterRevision(),new dev.scathiard.feedmepackages.domain.CellSettings(65,1000,64,63));
        var packet=new PanelPackets.SettingsBatch(view.window(),view.session(),1,java.util.List.of(change));
        var controls=new RegistryFriendlyByteBuf(Unpooled.buffer(),player.registryAccess());
        try {
            PanelPackets.SettingsBatch.CODEC.encode(controls,packet);
            helper.assertTrue(controls.readableBytes()<256 && packet.equals(PanelPackets.SettingsBatch.CODEC.decode(controls)),"Settings batch did not round-trip within its bounded wire shape");
        } finally { controls.release(); }
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void cellSettingsAreAtomicAndBoundToTheFilterLifetime(GameTestHelper helper) {
        var player=TestPlayers.create(helper,FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(player,0,new ItemStack(Items.STONE),63);
        ConsumptionTests.seed(player,1,new ItemStack(Items.ENDER_PEARL),16);
        var view=PanelNetwork.query(player,new PanelPackets.Query(UUID.randomUUID(),0,true));
        var access=AccessGate.resolve(player);var ledger=CacheLedger.get(player.getServer());var before=ledger.find(access.handle().cacheId());
        var edit=before.state().edit();edit.extract(0,1);ledger.replace(access.handle(),before.state().revision(),before.withState(edit.finish()));
        var a=new CacheActions.SettingsChange(0,view.cells().get(0).filterRevision(),new dev.scathiard.feedmepackages.domain.CellSettings(65,128,64,63));
        var b=new CacheActions.SettingsChange(1,view.cells().get(1).filterRevision(),new dev.scathiard.feedmepackages.domain.CellSettings(17,32,16,144));
        var reply=PanelNetwork.settings(player,new PanelPackets.SettingsBatch(view.window(),view.session(),1,java.util.List.of(a,b)));
        helper.assertTrue(reply.result()==Result.OK && reply.cells().get(0).amount()==62 && reply.cells().get(0).minimum()==65
                && reply.cells().get(0).pendingLimit()==63 && reply.cells().get(1).batch()==16,"Concurrent stock change lost a legitimate settings batch");
        var saved=ledger.find(access.handle().cacheId());
        helper.assertTrue(CacheActions.settings(player,view.session(),java.util.List.of(a,a))==Result.INVALID_REQUEST
                && ledger.find(access.handle().cacheId()).equals(saved),"Duplicate settings slot committed a partial batch");
        var next=saved.state().edit();next.extract(1,16);next.filter(1,null);next.filter(1,ItemVariantKey.of(new ItemStack(Items.DIAMOND),player.registryAccess()));
        ledger.replace(access.handle(),saved.state().revision(),saved.withState(next.finish()));saved=ledger.find(access.handle().cacheId());
        helper.assertTrue(CacheActions.settings(player,view.session(),java.util.List.of(a,b))==Result.STALE
                && ledger.find(access.handle().cacheId()).equals(saved),"Old filter settings committed after replacement");
        TestPlayers.necklace(player).setStackInSlot(0,ItemStack.EMPTY);
        helper.assertTrue(CacheActions.settings(player,view.session(),java.util.List.of(a))!=Result.OK
                && ledger.find(access.handle().cacheId()).equals(saved),"Unworn pendant retained a settings capability");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void multiplePlayersKeepSnapshotsAndIdleWorkBounded(GameTestHelper helper) {
        var fixtures = new java.util.ArrayList<ReceiveTests.Fixture>();
        var windows = new java.util.ArrayList<UUID>();
        var views = new java.util.ArrayList<PanelPackets.Snapshot>();
        var initial = new java.util.ArrayList<dev.scathiard.feedmepackages.storage.CacheRecord>();
        for (int p = 0; p < 32; p++) {
            var f = ReceiveTests.setup(helper, p + 1); fixtures.add(f); windows.add(UUID.randomUUID()); views.add(null);
            var before = f.record(); var edit = before.state().edit();
            for (int level = 1; level < 5; level++) edit.upgrade();
            for (int slot = 1; slot < 36; slot++) {
                var stack = new ItemStack(Items.STONE);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(860) + slot));
                var key = ItemVariantKey.of(stack, f.player().registryAccess()); edit.filter(slot, key); edit.insert(slot, key, 2048);
            }
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish())); initial.add(f.record());
        }
        helper.assertTrue(fixtures.stream().map(f -> f.handle().cacheId()).distinct().count() == 32, "Players share a supposedly independent cache");
        int replies = 0, hints = 0;
        long started = System.nanoTime();
        for (int tick = 0; tick < 200; tick++) for (int p = 0; p < fixtures.size(); p++) {
            var f = fixtures.get(p); f.player().tickCount = tick;
            dev.scathiard.feedmepackages.logistics.SupplyService.tick(f.player());
            var view = PanelNetwork.query(f.player(), new PanelPackets.Query(windows.get(p), 0, true));
            if (view != null) {
                replies++; views.set(p, view);
                helper.assertTrue(view.cells().size() == 36 && view.cells().getFirst().amount() == p + 1,
                        "Another player's stock leaked into a snapshot");
            }
            var hint = dev.scathiard.feedmepackages.network.MaterialHints.next(f.player());
            if (hint != null) { hints++; helper.assertTrue(tick == 0 && hint.full(), "Idle hints were repeatedly transmitted"); }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        helper.assertTrue(replies == 32 * 50 && hints == 32, "Query rate limiting or idle material suppression failed");
        for (int p = 0; p < fixtures.size(); p++) helper.assertTrue(fixtures.get(p).record() == initial.get(p), "Idle work dirtied a cache or emitted a new request");
        var f = fixtures.getFirst(); var view = views.getFirst();
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        var result = PanelNetwork.command(f.player(), new PanelPackets.Command(windows.getFirst(), 1, take, false, "", 0));
        helper.assertTrue(result != null && result.result() == Result.OK && f.player().containerMenu.getCarried().getCount() == 1,
                "A real command failed after the multi-player query load");
        f.player().getInventory().setItem(0, f.player().containerMenu.getCarried()); f.player().containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(f.player());
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 0, "Bounded consumption take did not deduct");
        var delta = dev.scathiard.feedmepackages.network.MaterialHints.next(f.player());
        helper.assertTrue(delta != null && !delta.full() && delta.templates().isEmpty() && delta.amounts().getFirst() == 0,
                "A single-player consumption did not produce its bounded count-only delta");
        for (int p = 1; p < fixtures.size(); p++) helper.assertTrue(fixtures.get(p).record() == initial.get(p)
                && dev.scathiard.feedmepackages.network.MaterialHints.next(fixtures.get(p).player()) == null, "Consumption affected another player");
        FeedMePackages.LOGGER.info("FMP_LOAD_OBSERVATION players=32 cells=36 logicalTicks=200 queries=6400 replies={} initialHints={} elapsedMs={} simulated-server-players/no-network-throughput-claim",
                replies, hints, elapsedMillis);
        helper.succeed();
    }

    private static Item fluidProbe;
    private static Runnable fluidCallback;
    @SubscribeEvent public static void registerFluidProbe(net.neoforged.neoforge.registries.RegisterEvent event) {
        event.register(net.minecraft.core.registries.Registries.ITEM, h -> h.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"test_fluid_container"),
                fluidProbe=new Item(new Item.Properties().stacksTo(1))));
    }
    @SubscribeEvent public static void registerFluidCapability(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerItem(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM,(stack,context)->new FluidProbe(stack),fluidProbe);
    }
    private static ItemStack probeStack(int water,int lava,String mode) {
        var stack=new ItemStack(fluidProbe); var tag=new net.minecraft.nbt.CompoundTag();
        tag.putInt("water",water);tag.putInt("lava",lava);tag.putString("mode",mode);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    /** Test-only foreign capability: it uses the real item capability registry, never ships in the mod. */
    private static final class FluidProbe implements IFluidHandlerItem {
        private final ItemStack stack;
        FluidProbe(ItemStack stack){this.stack=stack;}
        private net.minecraft.nbt.CompoundTag tag(){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
        @Override public ItemStack getContainer(){return stack;}
        @Override public int getTanks(){return 2;}
        @Override public FluidStack getFluidInTank(int tank){return new FluidStack(tank==0?Fluids.WATER:Fluids.LAVA,tag().getInt(tank==0?"water":"lava"));}
        @Override public int getTankCapacity(int tank){return 8000;}
        @Override public boolean isFluidValid(int tank,FluidStack fluid){return fluid.getFluid()==(tank==0?Fluids.WATER:Fluids.LAVA);}
        private int move(FluidStack fluid,int delta,FluidAction action){
            if(!action.execute())return Math.abs(delta);
            if(fluidCallback!=null)fluidCallback.run();
            var data=tag(); String key=fluid.getFluid()==Fluids.WATER?"water":"lava";String mode=data.getString("mode");
            data.putInt(key,data.getInt(key)+delta);
            if(mode.equals("other"))data.putInt(key.equals("water")?"lava":"water",0);
            stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
            if(mode.equals("count"))stack.setCount(2);
            if(mode.equals("throw"))throw new IllegalStateException("test fluid capability failure");
            if(mode.equals("linkage"))throw new NoClassDefFoundError("test fluid optional dependency failure");
            return Math.abs(delta)+(mode.equals("lie")?1:0);
        }
        @Override public int fill(FluidStack fluid,FluidAction action){
            if(fluid.getFluid()!=Fluids.WATER && fluid.getFluid()!=Fluids.LAVA)return 0;
            int amount=Math.min(fluid.getAmount(),8000-tag().getInt(fluid.getFluid()==Fluids.WATER?"water":"lava"));
            return move(fluid,amount,action);
        }
        @Override public FluidStack drain(FluidStack wanted,FluidAction action){
            for(int tank=0;tank<2;tank++)if(FluidStack.isSameFluidSameComponents(wanted,getFluidInTank(tank))){
                int amount=Math.min(wanted.getAmount(),getFluidInTank(tank).getAmount());
                return wanted.copyWithAmount(move(wanted,-amount,action));
            }
            return FluidStack.EMPTY;
        }
        @Override public FluidStack drain(int maximum,FluidAction action){
            for(int tank=0;tank<2;tank++)if(!getFluidInTank(tank).isEmpty())return drain(getFluidInTank(tank).copyWithAmount(maximum),action);
            return FluidStack.EMPTY;
        }
    }

    @GameTest(template = "empty")
    public static void foreignFluidFailuresDoNotPublishCopiesOrStrandTheGuard(GameTestHelper h) {
        for(String mode:java.util.List.of("throw","linkage","lie","other","count")) {
            var p=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());seedFluid(p,0,water(2000));
            var full=probeStack(1000,250,mode);p.containerMenu.setCarried(full);var original=full.copy();
            h.assertTrue(fluidAction(p,1,Action.DEPOSIT_FLUID,0,0,0,"")==Result.INVALID_ITEM
                    && fluidStock(p,0)==2000 && p.containerMenu.getCarried()==full && ItemStack.matches(full,original),"Bad drain published a copy: "+mode);
            p.containerMenu.setCarried(ItemStack.EMPTY);var empty=probeStack(0,250,mode);p.getInventory().setItem(0,empty);original=empty.copy();
            h.assertTrue(fluidAction(p,2,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Failed drain stranded the guard: "+mode);
            String template=ItemVariantKey.of(empty,p.registryAccess()).encoded();
            h.assertTrue(fluidAction(p,3,Action.PLACE_FLUID,0,0,2,template)==Result.NO_SPACE && fluidStock(p,0)==2000
                    && p.getInventory().getItem(0)==empty && ItemStack.matches(empty,original)
                    && dev.scathiard.feedmepackages.interaction.FluidActions.preview(p).amount()==1000,"Bad fill published a copy: "+mode);
            p.getInventory().setItem(0,probeStack(0,250,"ok"));template=ItemVariantKey.of(p.getInventory().getItem(0),p.registryAccess()).encoded();
            h.assertTrue(fluidAction(p,4,Action.PLACE_FLUID,0,0,2,template)==Result.OK && fluidStock(p,0)==1000,"Failed fill stranded the guard: "+mode);
            var actual=net.neoforged.neoforge.fluids.FluidUtil.getFluidHandler(p.getInventory().getItem(0)).orElseThrow();
            h.assertTrue(actual.getFluidInTank(0).getAmount()==1000 && actual.getFluidInTank(1).getAmount()==250,"Successful fill lost an unrelated tank");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void foreignFluidCallbacksCannotReenterReceiptOrResidualResumption(GameTestHelper h) {
        for(boolean deposit:new boolean[]{false,true})for(boolean residual:new boolean[]{false,true}) {
            var f=ReceiveTests.setup(h,residual?ReceiveTests.FULL-8:0);var p=f.player();var box=f.box(16);var original=box.copy();
            if(residual){h.assertTrue(ReceiveService.receive(p,box),"Residual fixture rejected");f.extract(8);}
            seedFluid(p,1,water(2000));var before=f.record();var container=probeStack(deposit?1000:0,250,"ok");
            p.getInventory().setItem(0,deposit?ItemStack.EMPTY:container);p.containerMenu.setCarried(deposit?container:ItemStack.EMPTY);
            if(!deposit)h.assertTrue(fluidAction(p,1,Action.TAKE_FLUID,1,1000,0,"")==Result.OK,"Callback preview fixture failed");
            final boolean[] receiptBlocked={false};final Result[] nested={null};
            try {
                fluidCallback=()->{
                    nested[0]=fluidAction(p,90,Action.THRESHOLDS,1,1,-1,"");
                    if(residual){ReceiveService.resume(p);receiptBlocked[0]=f.record()==before;}
                    else receiptBlocked[0]=!ReceiveService.receive(p,box);
                };
                var result=fluidAction(p,2,deposit?Action.DEPOSIT_FLUID:Action.PLACE_FLUID,1,0,1,
                        deposit?"":ItemVariantKey.of(container,p.registryAccess()).encoded());
                h.assertTrue(receiptBlocked[0] && nested[0]==Result.STALE && result==Result.OK
                        && fluidStock(p,1)==(deposit?3000:1000) && f.record().state().cells().getFirst().amount()==before.state().cells().getFirst().amount(),
                        "Fluid callback reentered FMP: deposit="+deposit+" residual="+residual+" receiptBlocked="+receiptBlocked[0]+" nested="+nested[0]+" outer="+result);
            } finally {fluidCallback=null;}
            if(residual){ReceiveService.resume(p);h.assertTrue(f.residual().isEmpty() && f.record().state().cells().getFirst().amount()==ReceiveTests.FULL,"Guard stranded a real remainder");}
            else h.assertTrue(ItemStack.matches(box,original) && ReceiveService.receive(p,box) && box.isEmpty()
                    && f.record().state().cells().getFirst().amount()==16,"Guard changed or stranded a real incoming parcel");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void foreignFluidCallbackChangingTheActualSlotCancelsOnlyOurPublication(GameTestHelper h) {
        var p=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());seedFluid(p,0,water(2000));
        var empty=probeStack(0,0,"ok");p.getInventory().setItem(0,empty);
        h.assertTrue(fluidAction(p,1,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Stale-slot fixture failed");
        var foreign=new ItemStack(Items.DIAMOND);Result result;
        try{fluidCallback=()->p.getInventory().setItem(0,foreign);
            result=fluidAction(p,2,Action.PLACE_FLUID,0,0,1,ItemVariantKey.of(empty,p.registryAccess()).encoded());
        }finally{fluidCallback=null;}
        h.assertTrue(result==Result.STALE && p.getInventory().getItem(0)==foreign && fluidStock(p,0)==2000
                && dev.scathiard.feedmepackages.interaction.FluidActions.preview(p).amount()==1000,"Fluid publication overwrote a foreign slot edit or spent fluid");
        p.getInventory().setItem(0,new ItemStack(Items.BUCKET));
        h.assertTrue(fluidAction(p,3,Action.PLACE_FLUID,0,0,1,ItemVariantKey.of(new ItemStack(Items.BUCKET),p.registryAccess()).encoded())==Result.OK
                && fluidStock(p,0)==1000,"Stale foreign slot stranded fluid access");h.succeed();
    }

    @GameTest(template = "empty")
    public static void foreignFluidCallbacksCannotOpenOrCommitItemConsumption(GameTestHelper h) {
        var p=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());seedFluid(p,0,water(2000));
        ConsumptionTests.seed(p,1,new ItemStack(Items.STONE),8);var container=probeStack(0,0,"ok");p.getInventory().setItem(0,container);
        h.assertTrue(fluidAction(p,1,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Material callback preview fixture failed");
        var pending=dev.scathiard.feedmepackages.consumption.MaterialTransaction.open(p).orElseThrow();
        h.assertTrue(pending.takeCache(new ItemStack(Items.STONE),1),"Prepared item debit fixture failed");
        final boolean[] opened={false},committed={false};Result result;
        try{fluidCallback=()->{opened[0]=dev.scathiard.feedmepackages.consumption.MaterialTransaction.open(p).isPresent();committed[0]=pending.commit();};
            result=fluidAction(p,2,Action.PLACE_FLUID,0,0,1,ItemVariantKey.of(container,p.registryAccess()).encoded());
        }finally{fluidCallback=null;}
        h.assertTrue(!opened[0] && !committed[0] && result==Result.OK && fluidStock(p,0)==1000 && ConsumptionTests.stock(p,1)==8,
                "Fluid callback reentered material consumption: opened="+opened[0]+" committed="+committed[0]+" outer="+result);
        var after=dev.scathiard.feedmepackages.consumption.MaterialTransaction.open(p).orElseThrow();
        h.assertTrue(after.takeCache(new ItemStack(Items.STONE),1) && after.commit() && ConsumptionTests.stock(p,1)==7,"Completed fluid fill stranded item consumption");h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidPanelDepositsPreviewsAndFillsActualBucketExactlyOnce(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());
        var window=UUID.randomUUID();
        var initial=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true));
        player.containerMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        var deposited=fluidCommand(player,initial,1,Action.DEPOSIT_FLUID,0,0,0,"");
        h.assertTrue(deposited.result()==Result.OK && deposited.cells().getFirst().fluid()
                && deposited.cells().getFirst().amount()==1000 && player.containerMenu.getCarried().is(Items.BUCKET),
                "Actual bucket deposit did not conserve liquid and container");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0,new ItemStack(Items.BUCKET,2));
        var take=fluidCommand(player,deposited,2,Action.TAKE_FLUID,0,1000,0,"");
        h.assertTrue(take.result()==Result.OK && take.cells().getFirst().amount()==1000 && take.cells().getFirst().reserved()==1000
                && take.fluidPreview().amount()==1000 && player.containerMenu.getCarried().isEmpty(),"Fluid preview materialized or deducted stock");
        var bytes=new RegistryFriendlyByteBuf(Unpooled.buffer(),player.registryAccess());
        try { PanelPackets.Snapshot.CODEC.encode(bytes,take); h.assertTrue(PanelPackets.Snapshot.CODEC.decode(bytes).equals(take),"Typed fluid snapshot lost its preview"); }
        finally { bytes.release(); }
        String target=ItemVariantKey.of(player.getInventory().getItem(0),player.registryAccess()).encoded();
        var command=new PanelPackets.Command(window,3,new CacheActions.Intent(take.session(),take.revision(),Action.PLACE_FLUID,0,0,2,target),false,"",0);
        var placed=PanelNetwork.command(player,command);
        h.assertTrue(placed.result()==Result.OK  &&  placed.cells().getFirst().amount()==0  &&  placed.fluidPreview().amount()==0
                 &&  player.getInventory().countItem(Items.BUCKET)==1  &&  player.getInventory().countItem(Items.WATER_BUCKET)==1
                ,"Container placement duplicated liquid, lost a bucket or changed local contents");
        h.assertTrue(PanelNetwork.command(player,command).result()==Result.STALE && fluidStock(player,0)==0,"Fluid placement replay succeeded");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidPreviewFullInventoryPartialPlacementAndCancelConserveStock(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack()); seedFluid(player,0,water(12000));
        for (int i=0;i<36;i++) player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        player.getInventory().setItem(0,new ItemStack(Items.BUCKET,2));
        h.assertTrue(fluidAction(player,1,Action.TAKE_FLUID,0,8000,0,"")==Result.OK,"Fluid preview fixture failed");
        String bucket=ItemVariantKey.of(new ItemStack(Items.BUCKET),player.registryAccess()).encoded();
        h.assertTrue(fluidAction(player,2,Action.PLACE_FLUID,0,0,1,bucket)==Result.NO_SPACE && fluidStock(player,0)==12000
                && player.getInventory().getItem(0).getCount()==2,"Full inventory lost the split bucket or spent fluid");
        player.getInventory().setItem(1,ItemStack.EMPTY);
        h.assertTrue(fluidAction(player,3,Action.PLACE_FLUID,0,0,1,bucket)==Result.OK && fluidStock(player,0)==11000
                && dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==7000,"Partial placement did not retain only unspent preview");
        h.assertTrue(fluidAction(player,4,Action.RELEASE_FLUID,-1,99,0,"")==Result.STALE
                && dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==7000,"Stale release cancelled another preview");
        h.assertTrue(fluidAction(player,5,Action.RELEASE_FLUID,-1,1,0,"")==Result.OK && fluidStock(player,0)==11000
                && dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==0,"Cancel spent or restored already placed fluid");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidContainerPreservesComponentsAndRefusesLossyBucket(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack()); var named=water(1250);
        named.set(DataComponents.CUSTOM_NAME,Component.literal("Exact spring"));
        var tank=fluidContainer(h); if (tank==null) return;
        h.assertTrue(tank.fill(named,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)==1250,"Native tank fixture could not hold exact fluid");
        player.containerMenu.setCarried(tank.getContainer());
        h.assertTrue(fluidAction(player,1,Action.DEPOSIT_FLUID,0,0,0,"")==Result.OK && fluidStock(player,0)==1250,"Tank deposit failed");
        var emptyTank=player.containerMenu.getCarried().copy(); player.containerMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0,new ItemStack(Items.BUCKET)); player.getInventory().setItem(1,emptyTank);
        h.assertTrue(fluidAction(player,2,Action.TAKE_FLUID,0,8000,0,"")==Result.OK,"Exact preview failed");
        h.assertTrue(fluidAction(player,3,Action.PLACE_FLUID,0,0,2,ItemVariantKey.of(new ItemStack(Items.BUCKET),player.registryAccess()).encoded())==Result.NO_SPACE
                && fluidStock(player,0)==1250 && player.getInventory().getItem(0).is(Items.BUCKET),"Vanilla bucket silently stripped fluid components");
        h.assertTrue(fluidAction(player,4,Action.PLACE_FLUID,0,1,2,ItemVariantKey.of(emptyTank,player.registryAccess()).encoded())==Result.OK
                && fluidStock(player,0)==0,"Exact tank refill failed");
        var actual=net.neoforged.neoforge.fluids.FluidUtil.getFluidHandler(player.getInventory().getItem(1)).orElseThrow().getFluidInTank(0);
        h.assertTrue(actual.getAmount()==1250 && net.neoforged.neoforge.fluids.FluidStack.isSameFluidSameComponents(actual,named),"Tank roundtrip changed fluid identity");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidDepositRespectsCapacityAndDoesNotPartiallyEmptyBucket(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack()); seedFluid(player,0,water(15900));
        player.containerMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(fluidAction(player,1,Action.DEPOSIT_FLUID,0,0,0,"")==Result.NO_SPACE && fluidStock(player,0)==15900
                && player.containerMenu.getCarried().is(Items.WATER_BUCKET),"An indivisible bucket was partially drained");
        var tank=fluidContainer(h); if (tank==null) return;
        tank.fill(water(5000),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        player.containerMenu.setCarried(tank.getContainer());
        h.assertTrue(fluidAction(player,2,Action.DEPOSIT_FLUID,0,0,0,"")==Result.OK && fluidStock(player,0)==16000
                && net.neoforged.neoforge.fluids.FluidUtil.getFluidHandler(player.containerMenu.getCarried()).orElseThrow().getFluidInTank(0).getAmount()==4900,
                "Tank partial deposit did not conserve the remainder");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidPreviewLifecycleAndTypedFiltersStaySeparate(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());
        var template=dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(water(1),player.registryAccess()).encoded();
        h.assertTrue(fluidAction(player,1,Action.SET_FLUID_GHOST,0,0,0,template)==Result.OK && fluidStock(player,0)==0,"Fluid template generated stock");
        h.assertTrue(fluidAction(player,2,Action.SET_FLUID_GHOST,1,0,0,template)==Result.DUPLICATE_FILTER,"Duplicate fluid filter accepted");
        var stone=ItemVariantKey.of(new ItemStack(Items.STONE),player.registryAccess()).encoded();
        h.assertTrue(fluidAction(player,3,Action.SET_GHOST,0,0,0,stone)==Result.OK
                && fluidAction(player,4,Action.SET_FLUID_GHOST,0,0,0,template)==Result.OK,"Empty item/fluid filter switch failed");
        seedFluid(player,0,water(2000));
        h.assertTrue(fluidAction(player,5,Action.CLEAR_FILTER,0,0,0,"")==Result.FILTER_OCCUPIED,"Nonempty fluid filter cleared");
        h.assertTrue(fluidAction(player,6,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Lifecycle preview fixture failed");
        var old=CacheActions.snapshot(player); CacheActions.close(player);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==0 && fluidStock(player,0)==2000,"Close spent or retained a preview");
        CacheActions.open(player);
        h.assertTrue(CacheActions.execute(player,new CacheActions.Intent(old.session(),old.record().state().revision(),Action.TAKE_FLUID,0,1000,0,""),7)==Result.STALE,
                "Closed session reopened a fluid hold");
        h.assertTrue(fluidAction(player,8,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Reopened preview failed");
        player.containerMenu.setCarried(new ItemStack(Items.STONE)); CacheActions.validateOpenContext(player);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==0 && fluidStock(player,0)==2000,"A real cursor retained the fluid preview");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        h.assertTrue(fluidAction(player,9,Action.TAKE_FLUID,0,1000,0,"")==Result.OK,"Final preview failed");
        TestPlayers.necklace(player).setStackInSlot(0,ItemStack.EMPTY); CacheActions.validateOpenContext(player);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==0,"Unequipped pendant retained fluid access");
        h.succeed();
    }

    private static PanelPackets.Snapshot fluidCommand(ServerPlayer player,PanelPackets.Snapshot view,int seq,Action action,int slot,int first,int second,String template) {
        return PanelNetwork.command(player,new PanelPackets.Command(view.window(),seq,
                new CacheActions.Intent(view.session(),view.revision(),action,slot,first,second,template),false,"",0));
    }
    @GameTest(template = "empty")
    public static void creativeFluidDepositReturnsActualEmptyContainerWithoutServerCursor(GameTestHelper h) {
        var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack()); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var packets=TestPlayers.nativePackets(player); var window=UUID.randomUUID();
        var view=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true));
        var intent=new CacheActions.Intent(view.session(),view.revision(),Action.DEPOSIT_FLUID,0,0,0,"");
        String full=ItemVariantKey.of(new ItemStack(Items.WATER_BUCKET),player.registryAccess()).encoded();
        h.assertTrue(CacheActions.execute(player,intent,1)==Result.INVALID_REQUEST,"Creative deposit bypassed explicit cursor ownership");
        var result=PanelNetwork.command(player,new PanelPackets.Command(window,1,intent,true,full,1));
        var reply=packets.stream().filter(p -> p instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket)
                .map(p -> ((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket)p).payload())
                .filter(p -> p instanceof PanelPackets.CursorUpdate).map(p -> (PanelPackets.CursorUpdate)p).findFirst().orElseThrow();
        h.assertTrue(result.result()==Result.OK && fluidStock(player,0)==1000 && player.containerMenu.getCarried().isEmpty()
                && reply.count()==1 && ItemVariantKey.decode(reply.template(),player.registryAccess()).stack(player.registryAccess(),1).is(Items.BUCKET),
                "Creative fluid deposit lost the remainder or created a second server cursor");
        h.succeed();
    }

    private static Result fluidAction(ServerPlayer player,int seq,Action action,int slot,int first,int second,String template) {
        var view=CacheActions.snapshot(player);
        return CacheActions.execute(player,new CacheActions.Intent(view.session(),view.record().state().revision(),action,slot,first,second,template),seq);
    }
    private static net.neoforged.neoforge.fluids.FluidStack water(int amount) {
        return new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,amount);
    }
    private static net.neoforged.neoforge.fluids.capability.IFluidHandlerItem fluidContainer(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("fluidlogistics")) {
            FeedMePackages.LOGGER.info("FMP_FLUID_CONTAINER_SKIPPED: native copper bucket absent"); h.succeed(); return null;
        }
        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("fluidlogistics:copper_bucket"));
        return net.neoforged.neoforge.fluids.FluidUtil.getFluidHandler(new ItemStack(item)).orElseThrow();
    }
    private static void seedFluid(ServerPlayer player,int slot,net.neoforged.neoforge.fluids.FluidStack sample) {
        var handle=AccessGate.resolve(player).handle(); var ledger=CacheLedger.get(player.getServer()); var before=ledger.find(handle.cacheId());
        var edit=before.fluids().state().edit(); var key=dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(sample,player.registryAccess());
        edit.filter(slot,key); edit.insert(slot,key,sample.getAmount());
        ledger.replace(handle,before.state().revision(),before.withFluids(before.fluids().withState(edit.finish())));
    }
    @GameTest(template = "empty")
    public static void configuredShrinkUsesReadonlyPagedCellsAndRestoresWrites(GameTestHelper h) {
        var slots=dev.scathiard.feedmepackages.FmpConfig.SLOTS.get(0);var groups=dev.scathiard.feedmepackages.FmpConfig.GROUPS.get(0);
        int oldSlots=slots.get(),oldGroups=groups.get();
        try {
            slots.set(50);groups.set(32);dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());var handle=AccessGate.resolve(player).handle();var ledger=CacheLedger.get(player.getServer());
            var record=ledger.find(handle.cacheId());var key=ItemVariantKey.of(new ItemStack(Items.STONE),player.registryAccess());
            var edit=record.state().edit();edit.filter(49,key);edit.insert(49,key,1800);ledger.replace(handle,record.state().revision(),record.withState(edit.finish()));
            var window=UUID.randomUUID();var view=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true,999,6));
            h.assertTrue(view==null,"Oversized viewport was accepted");
            player.tickCount+=10;view=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true,48,6));
            var hints=dev.scathiard.feedmepackages.network.MaterialHints.next(player);
            h.assertTrue(hints!=null && hints.amounts().size()==50 && hints.amounts().get(49)==1800,"Extra cells missing from native crafting material hints");
            int local=view.grid().localSlot(49);
            h.assertTrue(local>=0 && view.cells().get(local).amount()==1800 && view.cells().size()<=36 && view.totalSlots()==50,"Last configured slot was missing from bounded page");
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),player.registryAccess());
            try {PanelPackets.Snapshot.CODEC.encode(buffer,view);h.assertTrue(view.equals(PanelPackets.Snapshot.CODEC.decode(buffer)),"Paged snapshot did not round trip");}finally{buffer.release();}
            slots.set(9);groups.set(2);dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            player.tickCount+=10;view=PanelNetwork.query(player,new PanelPackets.Query(window,player.containerMenu.containerId,true,48,6));local=view.grid().localSlot(49);
            h.assertTrue(view.totalSlots()==50 && !view.cells().get(local).writable() && view.cells().get(local).amount()==1800,"Shrinking config hid or rewrote retained stock");
            player.containerMenu.setCarried(new ItemStack(Items.STONE,3));
            h.assertTrue(execute(player,Action.DEPOSIT,49,0,"")==Result.READ_ONLY && player.containerMenu.getCarried().getCount()==3,"Readonly slot consumed cursor");
            player.containerMenu.setCarried(ItemStack.EMPTY);
            h.assertTrue(execute(player,Action.TAKE_INVENTORY,49,64,"")==Result.OK && stock(player,49)==1736,"Readonly slot could not be taken through native action");
            slots.set(50);groups.set(32);dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            player.containerMenu.setCarried(new ItemStack(Items.STONE,3));
            h.assertTrue(execute(player,Action.DEPOSIT,49,0,"")==Result.OK && stock(player,49)==1739,"Restored configuration did not restore writes");
            player.containerMenu.setCarried(new ItemStack(Items.STONE,3));var actionView=CacheActions.snapshot(player);
            h.assertTrue(CacheActions.execute(player,new CacheActions.Intent(actionView.session(),actionView.record().state().revision(),Action.DEPOSIT,0,0,CacheActions.AUTO_DEPOSIT,""))==Result.OK
                    && stock(player,49)==1742 && stock(player,0)==0,"Automatic drop failed to resolve an off-page exact filter");
        } finally { slots.set(oldSlots);groups.set(oldGroups);dev.scathiard.feedmepackages.FmpConfig.refreshServer(); }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void configuredAdmissionKeepsExistingStockAndHonorsExplicitAllow(GameTestHelper h) {
        var denied=dev.scathiard.feedmepackages.FmpConfig.DENIED;var allowed=dev.scathiard.feedmepackages.FmpConfig.ALLOWED;
        var oldD=denied.get();var oldA=allowed.get();
        try {
            var player=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());
            player.containerMenu.setCarried(new ItemStack(Items.STONE,8));
            h.assertTrue(execute(player,Action.DEPOSIT,0,0,"")==Result.OK,"Admission fixture failed");
            denied.set(java.util.List.of("minecraft:stone","#minecraft:logs"));allowed.set(java.util.List.of());dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            player.containerMenu.setCarried(new ItemStack(Items.STONE,2));
            h.assertTrue(execute(player,Action.DEPOSIT,0,0,"")==Result.DENIED && stock(player,0)==8 && player.containerMenu.getCarried().getCount()==2,"Config ban changed existing or incoming stock");
            player.containerMenu.setCarried(ItemStack.EMPTY);
            h.assertTrue(execute(player,Action.TAKE_INVENTORY,0,3,"")==Result.OK && stock(player,0)==5,"Ban prevented taking existing stock");
            var oak=ItemVariantKey.of(new ItemStack(Items.OAK_LOG),player.registryAccess());
            h.assertTrue(!dev.scathiard.feedmepackages.service.CachePolicy.admits(oak,player.registryAccess()),"Configured #tag denial not applied");
            allowed.set(java.util.List.of("minecraft:stone","#minecraft:logs"));dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            player.containerMenu.setCarried(new ItemStack(Items.STONE,2));
            h.assertTrue(execute(player,Action.DEPOSIT,0,0,"")==Result.OK && stock(player,0)==7
                    && dev.scathiard.feedmepackages.service.CachePolicy.admits(oak,player.registryAccess()),"Explicit ID/tag allow did not override configurable ban");
        } finally {denied.set(oldD);allowed.set(oldA);dev.scathiard.feedmepackages.FmpConfig.refreshServer();}
        h.succeed();
    }
    private static long fluidStock(ServerPlayer player,int slot) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).fluids().state().cells().get(slot).amount();
    }
}
