package dev.scathiard.feedmepackages.gametest;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.material.Fluids;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.service.PrivateCacheMerge;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReceiveTests {
    @GameTest(template = "empty")
    public static void backgroundHandbackDoesNotDetachAnUnrelatedItemBeingUsed(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); ReceiveService.receive(f.player, f.box(16));
        var edit = f.record().state().edit(); edit.extract(0, Integer.MAX_VALUE); edit.filter(0, null);
        f.ledger.replace(f.handle, f.record().state().revision(), f.record().withState(edit.finish()));
        f.player.getInventory().setItem(0, new ItemStack(Items.BOW)); f.player.getInventory().setItem(1, ItemStack.EMPTY);
        f.player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.assertTrue(f.player.getUseItem() == f.player.getMainHandItem(), "Native use fixture did not retain its held stack");
        ReceiveService.resume(f.player);
        helper.assertTrue(f.residual().isEmpty() && contents(f.player.getInventory().getItem(1)) == 8, "Background parcel handback failed");
        helper.assertTrue(f.player.getUseItem() == f.player.getMainHandItem(), "Background handback detached the native item-use reference");
        f.player.getUseItem().hurtAndBreak(1, f.player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        helper.assertTrue(f.player.getMainHandItem().getDamageValue() == 1, "Continued item use damaged a detached copy instead of the held bow");
        f.player.stopUsingItem(); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unverifiableInternalResidualRemainsIntactEvenWithInventorySpace(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); ReceiveService.receive(f.player, f.box(16));
        var bad = f.residual(); bad.remove(FmpRegistries.PARCEL_SEAL.get());
        f.ledger.replace(f.handle, f.record().state().revision(), new CacheRecord(f.record().state(), null, Map.of(f.key, bad)));
        f.player.getInventory().setItem(0, ItemStack.EMPTY); var before = f.record();
        for (int tick = 0; tick < 10; tick++) ReceiveService.resume(f.player);
        helper.assertTrue(f.record() == before && ItemStack.matches(f.residual(), bad)
                && f.player.getInventory().getItem(0).isEmpty(), "Invalid internal seal was discarded, guessed or turned into spendable stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidatedResidualWaitsForInventoryThenReturnsWithoutCycling(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); ReceiveService.receive(f.player, f.box(64));
        var residual = f.residual();
        var edit = f.record().state().edit(); edit.extract(0, Integer.MAX_VALUE); edit.filter(0, null);
        f.ledger.replace(f.handle, f.record().state().revision(), f.record().withState(edit.finish()));
        var before = f.record(); ReceiveService.resume(f.player);
        helper.assertTrue(f.record() == before && ItemStack.matches(residual, f.residual()), "Full inventory lost an invalidated remainder");
        var saved = CacheLedger.load(f.ledger.save(new CompoundTag(), f.player.registryAccess()), f.player.registryAccess());
        helper.assertTrue(saved.problem().isEmpty() && ItemStack.matches(saved.find(f.handle.cacheId()).residual(f.key), residual), "Invalidated residual did not survive save/load");
        var pendant = TestPlayers.necklace(f.player).getStackInSlot(0); TestPlayers.necklace(f.player).setStackInSlot(0, ItemStack.EMPTY);
        f.player.getInventory().setItem(0, ItemStack.EMPTY); ReceiveService.resume(f.player);
        helper.assertTrue(f.record() == before && f.player.getInventory().getItem(0).isEmpty(), "Unworn cache performed background work");
        TestPlayers.necklace(f.player).setStackInSlot(0, pendant); ReceiveService.resume(f.player);
        var returned = f.player.getInventory().getItem(0);
        helper.assertTrue(f.residual().isEmpty() && ItemStack.matches(returned, residual) && contents(returned) == 56, "Whole invalidated parcel did not safely return to inventory");
        var settled = f.record(); for (int tick = 0; tick < 40; tick++) ReceiveService.resume(f.player);
        helper.assertTrue(f.record() == settled && ItemStack.matches(returned, f.player.getInventory().getItem(0)), "Returned parcel repeatedly changed ownership");
        ((PackageItem)returned.getItem()).open(helper.getLevel(), f.player, net.minecraft.world.InteractionHand.MAIN_HAND);
        ReceiveService.resume(f.player);
        helper.assertTrue(f.residual().isEmpty() && f.player.getMainHandItem().is(Items.STONE) && f.player.getMainHandItem().getCount() == 56,
                "Manual opening of automatically returned parcel left hidden material or lost contents");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmatchedComponentRemainderReturnsButMatchedFullRemainderWaits(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); var named = new ItemStack(Items.STONE, 5); named.set(DataComponents.CUSTOM_NAME, Component.literal("Unmatched"));
        var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, 16), named)); PackageItem.addAddress(box, SupplyService.address(f.player));
        ParcelAuthentication.apply(f.ledger, f.player.registryAccess(), box, f.box(1).get(FmpRegistries.PARCEL_SEAL.get()));
        helper.assertTrue(ReceiveService.receive(f.player, box) && contents(f.residual()) == 13, "Mixed remainder setup failed");
        ReceiveService.resume(f.player); helper.assertTrue(contents(f.residual()) == 13, "Full inventory destroyed mixed remainder");
        f.extract(4); f.player.getInventory().setItem(0, ItemStack.EMPTY); ReceiveService.resume(f.player);
        var returned = f.player.getInventory().getItem(0); var settled = f.record();
        helper.assertTrue(settled.residual(f.key).isEmpty() && settled.state().cells().getFirst().amount() == FULL && contents(returned) == 9,
                "Partial resume and native handback were not conservative");
        helper.assertTrue(ItemStack.matches(PackageItem.getContents(returned).getStackInSlot(1), named), "Handback changed unmatched components");
        for (int tick = 0; tick < 10; tick++) ReceiveService.resume(f.player);
        helper.assertTrue(f.record() == settled && ItemStack.matches(returned, f.player.getInventory().getItem(0)), "Zero-move arrived parcel was recaptured");
        f.extract(4); ReceiveService.resume(f.player);
        helper.assertTrue(f.record().state().cells().getFirst().amount() == FULL && contents(f.residual()) == 5 && f.player.getInventory().getItem(0).isEmpty(), "New capacity did not accept only the matching part");
        ReceiveService.resume(f.player);
        helper.assertTrue(f.residual().isEmpty() && contents(f.player.getInventory().getItem(0)) == 5, "Final unmatched contents remained inaccessible");
        helper.succeed();
    }

    record Fixture(ServerPlayer player, CacheLedger ledger, CacheHandle handle, ItemVariantKey key, UUID order) {
        CacheRecord record() { return ledger.find(handle.cacheId()); }
        // The fixture configures a single filter variant in cell 0, so its residual is always keyed by `key`.
        ItemStack residual() { return record().residual(key); }
        void extract(int count) {
            var before = record(); var edit = before.state().edit(); edit.extract(0, count);
            ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        }
        ItemStack box(int count) {
            var box = PackageItem.containing(List.of(key.stack(player.registryAccess(), count)));
            PackageItem.addAddress(box, SupplyService.address(player));
            var seal = new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(), order, key.encoded(),
                    record().state().cells().getFirst().filterRevision(), SupplyService.address(player), false, "");
            ParcelAuthentication.apply(ledger, player.registryAccess(), box, seal); return box;
        }
    }

    /** Level-1 stone physical capacity: 128 items. */
    static final int FULL = CacheLevel.of(1).capacity();

    static Fixture setup(GameTestHelper helper, int initialStock) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player);
        helper.assertTrue(access.active() && access.handle().networkId() == null, "Unbound functional pendant not active");
        var ledger = CacheLedger.get(helper.getLevel().getServer()); var handle = access.handle();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()); var order = UUID.randomUUID();
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        edit.filter(0, key); edit.thresholds(0, 2, -1); edit.request(0, order, player.getUUID(), 64, false); edit.insert(0, key, initialStock);
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        return new Fixture(player, ledger, handle, key, order);
    }

    @GameTest(template = "empty")
    public static void fullInventoryReceivesThroughVanillaHandoff(GameTestHelper helper) {
        var f = setup(helper, 0); var box = f.box(64);
        helper.assertTrue(f.player.getInventory().add(box), "Full inventory refused a valid complete delivery");
        helper.assertTrue(box.isEmpty() && f.record().state().cells().getFirst().amount() == 64, "Complete delivery did not conserve stock");
        helper.assertTrue(!ReceiveService.receive(f.player, box), "Repeated event consumed the same stack twice");
        var next = f.box(32); f.player.getInventory().placeItemBackInInventory(next);
        helper.assertTrue(next.isEmpty() && f.record().state().cells().getFirst().amount() == 96, "placeItemBackInInventory bypassed service port");
        for (int slot = 0; slot < 36; slot++) helper.assertTrue(f.player.getInventory().getItem(slot).is(Items.DIRT), "Delivery displaced existing inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialArrivalAndResumeAreConservative(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); var box = f.box(64);
        helper.assertTrue(ReceiveService.receive(f.player, box), "Partial package not accepted");
        helper.assertTrue(box.isEmpty() && f.record().state().cells().getFirst().amount() == FULL, "Wrong partial amount");
        helper.assertTrue(contents(f.residual()) == 56 && f.record().state().pending(0) == 0, "Local arrival receipt or residual is wrong");
        var saved = CacheLedger.load(f.ledger.save(new CompoundTag(), f.player.registryAccess()), f.player.registryAccess());
        var savedBox = saved.find(f.handle.cacheId()).residual(f.key);
        helper.assertTrue(saved.problem().isEmpty() && ItemStack.matches(savedBox, f.residual()), "Residual changed on ledger reload");
        helper.assertTrue(ParcelAuthentication.valid(saved, f.player.registryAccess(), savedBox, savedBox.get(FmpRegistries.PARCEL_SEAL.get())), "Saved seal no longer authentic");
        var blocked = f.box(32); var original = blocked.copy(); var revision = f.record().state().revision();
        helper.assertTrue(!f.player.getInventory().add(blocked), "Occupied residual slot accepted another partial package");
        helper.assertTrue(ItemStack.matches(original, blocked) && f.record().state().revision() == revision, "Refusal mutated a side");
        f.extract(30); ReceiveService.resume(f.player);
        helper.assertTrue(contents(f.residual()) == 26 && f.record().state().cells().getFirst().amount() == FULL, "Residual resume lost stock");
        ReceiveService.resume(f.player);
        helper.assertTrue(contents(f.residual()) == 26 && f.record().state().pending(0) == 0, "Repeated resume repeated receipt");
        f.extract(26); ReceiveService.resume(f.player);
        helper.assertTrue(f.residual().isEmpty() && f.record().state().cells().getFirst().amount() == FULL, "Final residual was not cleared");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidAndAmbiguousPackagesStayUntouched(GameTestHelper helper) {
        var f = setup(helper, 0);
        var ordinary = PackageItem.containing(List.of(new ItemStack(Items.STONE, 10)));
        PackageItem.addAddress(ordinary, SupplyService.address(f.player)); reject(helper, f, ordinary);
        var renamed = f.box(10); PackageItem.addAddress(renamed, "FMP@SomeoneElse"); reject(helper, f, renamed);
        var changed = f.box(10); changed.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 20))));
        reject(helper, f, changed);
        var extended = f.box(10); var lots = NonNullList.withSize(10, ItemStack.EMPTY); lots.set(0, new ItemStack(Items.STONE, 10)); lots.set(9, new ItemStack(Items.DIAMOND));
        extended.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(lots)); reject(helper, f, extended);
        var other = setup(helper, 0); reject(helper, other, f.box(10));
        var before = f.record(); var edit = before.state().edit(); edit.filter(0, null); edit.filter(0, f.key);
        var late = f.box(10); f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish())); reject(helper, f, late);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void manualResetAllowsLatePhysicalParcel(GameTestHelper helper) {
        var f = setup(helper, 0); var late = f.box(32); var before = f.record(); var edit = before.state().edit(); edit.reset(0);
        f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish()));
        helper.assertTrue(ReceiveService.receive(f.player, late), "Reset wrongly invalidated physical late package");
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 32 && f.record().state().orders().isEmpty(), "Late package recreated a request");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rebuildingFilterElsewhereDoesNotAcceptItsOldParcel(GameTestHelper helper) {
        var f = setup(helper, 0); var box = f.box(32); var before = f.record(); var edit = before.state().edit();
        edit.filter(0, null); edit.filter(1, f.key);
        f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish()));
        reject(helper, f, box);
        helper.assertTrue(f.record().state().cells().get(1).amount() == 0, "Old parcel targeted a different filter lifetime");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryResidualFollowsPendantButExternalRecipientDoesNot(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); var box = f.box(64); var external = f.box(16);
        helper.assertTrue(ReceiveService.receive(f.player, box), "Initial residual transfer failed");
        f.extract(56);
        var pendant = TestPlayers.necklace(f.player).getStackInSlot(0);
        TestPlayers.necklace(f.player).setStackInSlot(0, ItemStack.EMPTY);
        var buyer = TestPlayers.create(helper, pendant);
        helper.assertTrue(AccessGate.resolve(buyer).handle().cacheId().equals(f.handle.cacheId()), "Ordinary cache did not follow physical pendant");
        helper.assertTrue(!ReceiveService.receive(buyer, external), "Transfer rewrote external recipient authorization");
        ReceiveService.resume(buyer);
        helper.assertTrue(f.residual().isEmpty() && f.record().state().cells().getFirst().amount() == FULL,
                "New lawful holder could not resume the cache-owned residual");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unwearExtraPendantAndReequipGateIntake(GameTestHelper helper) {
        var f = setup(helper, 0); var pendant = TestPlayers.necklace(f.player).getStackInSlot(0); var box = f.box(32);
        TestPlayers.necklace(f.player).setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(!AccessGate.resolve(f.player).active(), "Inventory-only pendant grants access"); reject(helper, f, box);
        TestPlayers.necklace(f.player).setStackInSlot(0, pendant);
        TestPlayers.necklace(f.player).grow(1); TestPlayers.necklace(f.player).setStackInSlot(1, FmpRegistries.PENDANT.toStack());
        reject(helper, f, box); TestPlayers.necklace(f.player).setStackInSlot(1, ItemStack.EMPTY);
        pendant.set(FmpRegistries.DISABLED.get(), true); // The retired flag cannot hide or disable an equipped pendant.
        helper.assertTrue(ReceiveService.receive(f.player, box) && f.record().state().cells().getFirst().amount() == 32, "Reequip failed to resume same cache");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void componentIdentitySeparatesMixedContents(GameTestHelper helper) {
        var f = setup(helper, 0); var named = new ItemStack(Items.STONE, 5); named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact variant"));
        var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, 8), named)); PackageItem.addAddress(box, SupplyService.address(f.player));
        var route = f.box(1).get(FmpRegistries.PARCEL_SEAL.get()); ParcelAuthentication.apply(f.ledger, f.player.registryAccess(), box, route);
        helper.assertTrue(ReceiveService.receive(f.player, box), "Mixed package not accepted");
        var residue = PackageItem.getContents(f.residual()).getStackInSlot(1);
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 8 && ItemStack.matches(residue, named), "Distinct components silently collapsed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privateInheritancePlansWholeStateWithoutCreatingOwnershipOrConsumingResidual(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); var box = f.box(32);
        helper.assertTrue(ReceiveService.receive(f.player, box), "Inheritance fixture did not create a residual"); f.extract(40);
        var before = f.ledger.save(new CompoundTag(), f.player.registryAccess());
        var plan = PrivateCacheMerge.simulate(f.ledger, f.player.registryAccess(), f.player.getUUID(), f.handle.cacheId());
        helper.assertTrue(plan.target() == null && plan.replacement().state().id().equals(f.handle.cacheId()), "First private cache changed its identity");
        helper.assertTrue(plan.replacement().owner().equals(f.player.getUUID()) && plan.replacement().state().cells().getFirst().amount() == FULL - 16
                && plan.replacement().residual(f.key).isEmpty(), "Inheritance candidate lost stock or owner");
        helper.assertTrue(plan.replacement().state().pending(0) == 32, "Already-arrived residual was counted as another delivery");
        helper.assertTrue(before.equals(f.ledger.save(new CompoundTag(), f.player.registryAccess())) && f.ledger.personal(f.player.getUUID()) == null,
                "Read-only inheritance changed the ledger or created personal ownership");
        helper.assertTrue(contents(f.residual()) == 24 && f.record().state().cells().getFirst().amount() == FULL - 40, "Simulation consumed its source");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privateMergeRemapsAndResealsItsOnePhysicalResidualWithoutCommitting(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); helper.assertTrue(ReceiveService.receive(f.player, f.box(32)), "Merge fixture failed"); f.extract(100);
        UUID personal = LegacyOwnership.on(f.ledger).personalOrCreate(f.player.getUUID()); var original = f.ledger.find(personal); var edit = original.state().edit();
        edit.filter(0, f.key); edit.filter(0, null); edit.filter(1, f.key); edit.thresholds(1, 2, -1); edit.insert(1, f.key, 90);
        var handle = new CacheHandle(personal, f.player.getUUID(), null);
        f.ledger.replace(handle, original.state().revision(), original.withState(edit.finish()));
        var before = f.ledger.save(new CompoundTag(), f.player.registryAccess()); var sourceSeal = f.residual().get(FmpRegistries.PARCEL_SEAL.get());
        var plan = PrivateCacheMerge.simulate(f.ledger, f.player.registryAccess(), f.player.getUUID(), f.handle.cacheId());
        var merged = plan.replacement(); ItemStack rest = merged.residual(f.key); var seal = rest.get(FmpRegistries.PARCEL_SEAL.get());
        helper.assertTrue(merged.state().cells().get(1).amount() == FULL && contents(rest) == 14 && merged.state().pending(1) == 32,
                "Partial merge lost stock or recounted an arrived parcel");
        helper.assertTrue(seal.cacheId().equals(personal) && seal.filterRevision() == merged.state().cells().get(1).filterRevision()
                && seal.filterRevision() != sourceSeal.filterRevision() && seal.parcelId().equals(sourceSeal.parcelId())
                && seal.playerId().equals(sourceSeal.playerId()) && seal.requestId().equals(sourceSeal.requestId())
                && ParcelAuthentication.valid(f.ledger, f.player.registryAccess(), rest, seal), "Residual remapping lost identity or authentication");
        helper.assertTrue(merged.state().revision() == plan.target().state().revision() + 1, "Simulation exposed intermediate revisions");
        rest.setCount(0);
        helper.assertTrue(contents(merged.residual(f.key)) == 14, "Caller mutated candidate residual through a getter");
        helper.assertTrue(before.equals(f.ledger.save(new CompoundTag(), f.player.registryAccess())), "Merge preview changed a real cache");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privateMergeRefusesTwoLeftoverBoxesButCanResumeBothWhenEverythingFits(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); helper.assertTrue(ReceiveService.receive(f.player, f.box(32)), "Merge fixture failed"); f.extract(100);
        UUID personal = LegacyOwnership.on(f.ledger).personalOrCreate(f.player.getUUID()); var original = f.ledger.find(personal); var edit = original.state().edit();
        edit.filter(0, f.key); edit.thresholds(0, 2, -1); edit.insert(0, f.key, 100); var targetState = edit.finish();
        ItemStack targetBox = PackageItem.containing(List.of(new ItemStack(Items.STONE, 16))); String address = SupplyService.address(f.player);
        PackageItem.addAddress(targetBox, address);
        ParcelAuthentication.apply(f.ledger, f.player.registryAccess(), targetBox, new ParcelSeal(UUID.randomUUID(), personal, f.player.getUUID(),
                UUID.randomUUID(), f.key.encoded(), targetState.cells().getFirst().filterRevision(), address, true, ""));
        var handle = new CacheHandle(personal, f.player.getUUID(), null);
        f.ledger.replace(handle, original.state().revision(), new CacheRecord(targetState, f.player.getUUID(), Map.of(f.key, targetBox)));
        rejectMerge(helper, f, PrivateCacheMerge.Reason.TWO_RESIDUALS);
        var target = f.ledger.find(personal); edit = target.state().edit(); edit.extract(0, 40);
        f.ledger.replace(handle, target.state().revision(), target.withState(edit.finish()));
        var before = f.ledger.save(new CompoundTag(), f.player.registryAccess());
        var plan = PrivateCacheMerge.simulate(f.ledger, f.player.registryAccess(), f.player.getUUID(), f.handle.cacheId());
        helper.assertTrue(plan.replacement().residual(f.key).isEmpty() && plan.replacement().state().cells().getFirst().amount() == FULL,
                "Two fully resumable boxes were lost or rejected");
        helper.assertTrue(before.equals(f.ledger.save(new CompoundTag(), f.player.registryAccess())), "Successful proposal consumed real boxes");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privateMergeRejectsTamperedAndObsoleteResidualsWithoutDroppingThem(GameTestHelper helper) {
        var f = setup(helper, FULL - 8); helper.assertTrue(ReceiveService.receive(f.player, f.box(32)), "Merge fixture failed");
        var original = f.record(); ItemStack tampered = original.residual(f.key);
        tampered.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 25))));
        f.ledger.replace(f.handle, original.state().revision(), new CacheRecord(original.state(), null, Map.of(f.key, tampered)));
        rejectMerge(helper, f, PrivateCacheMerge.Reason.INVALID_RESIDUAL);
        var current = f.record(); f.ledger.replace(f.handle, current.state().revision(), new CacheRecord(current.state(), null, Map.of(f.key, original.residual(f.key))));
        f.extract(Integer.MAX_VALUE); current = f.record(); var edit = current.state().edit(); edit.filter(0, null); edit.filter(1, f.key);
        f.ledger.replace(f.handle, current.state().revision(), current.withState(edit.finish()));
        rejectMerge(helper, f, PrivateCacheMerge.Reason.INVALID_RESIDUAL);
        helper.assertTrue(contents(f.residual()) == 24, "Refused obsolete residual disappeared");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void perCellResidualsDoNotContend(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player);
        helper.assertTrue(access.active(), "Active pendant required");
        var ledger = CacheLedger.get(helper.getLevel().getServer()); var handle = access.handle();
        var registries = player.registryAccess();
        var keyA = ItemVariantKey.of(new ItemStack(Items.STONE), registries);
        var keyB = ItemVariantKey.of(new ItemStack(Items.DIAMOND), registries);
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        edit.filter(0, keyA); edit.thresholds(0, 2, -1); edit.request(0, UUID.randomUUID(), player.getUUID(), 64, false); edit.insert(0, keyA, FULL - 8);
        edit.filter(1, keyB); edit.thresholds(1, 2, -1); edit.request(1, UUID.randomUUID(), player.getUUID(), 64, false); edit.insert(1, keyB, FULL - 8);
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        String address = SupplyService.address(player);

        ItemStack boxA = box(helper, player, ledger, handle, keyA, address);
        helper.assertTrue(ReceiveService.receive(player, boxA), "First partial parcel refused");
        helper.assertTrue(!ledger.find(handle.cacheId()).residual(keyA).isEmpty(), "A cell residual missing");

        // A parcel for a *different* item must be received into its own cell even though cell A's residual is pending.
        ItemStack boxB = box(helper, player, ledger, handle, keyB, address);
        helper.assertTrue(ReceiveService.receive(player, boxB), "Parcel for another cell was blocked by an unrelated pending residual");
        helper.assertTrue(!ledger.find(handle.cacheId()).residual(keyA).isEmpty() && !ledger.find(handle.cacheId()).residual(keyB).isEmpty(),
                "Two different cells could not hold residuals concurrently");

        // Absorbing cell A must not disturb cell B's pending residual.
        var full = ledger.find(handle.cacheId()); var drain = full.state().edit(); drain.extract(0, Integer.MAX_VALUE);
        ledger.replace(handle, full.state().revision(), full.withState(drain.finish()));
        ReceiveService.resume(player);
        helper.assertTrue(ledger.find(handle.cacheId()).residual(keyA).isEmpty() && !ledger.find(handle.cacheId()).residual(keyB).isEmpty(),
                "Resuming one cell cleared or blocked the other cell's residual");

        // Both residual states, plus the remaining one, survive a save/load round trip.
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), registries), registries);
        var reloaded = saved.find(handle.cacheId());
        helper.assertTrue(saved.problem().isEmpty() && reloaded.residual(keyA).isEmpty() && !reloaded.residual(keyB).isEmpty(),
                "Per-cell residuals did not survive ledger reload");
        helper.succeed();
    }

    private static ItemStack box(GameTestHelper helper, ServerPlayer player, CacheLedger ledger, CacheHandle handle, ItemVariantKey key, String address) {
        var cell = ledger.find(handle.cacheId()).state().cells().stream().filter(c -> Objects.equals(c.filter(), key)).findFirst().orElseThrow();
        var box = PackageItem.containing(List.of(key.stack(player.registryAccess(), 64)));
        PackageItem.addAddress(box, address);
        var seal = new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(), UUID.randomUUID(), key.encoded(),
                cell.filterRevision(), address, false, "");
        ParcelAuthentication.apply(ledger, player.registryAccess(), box, seal); return box;
    }

    private static void rejectMerge(GameTestHelper helper, Fixture f, PrivateCacheMerge.Reason reason) {
        var before = f.ledger.save(new CompoundTag(), f.player.registryAccess()); boolean rejected = false;
        try { PrivateCacheMerge.simulate(f.ledger, f.player.registryAccess(), f.player.getUUID(), f.handle.cacheId()); }
        catch (PrivateCacheMerge.Rejected failure) { rejected = failure.reason() == reason; }
        helper.assertTrue(rejected, "Private merge did not report the expected refusal: " + reason);
        helper.assertTrue(before.equals(f.ledger.save(new CompoundTag(), f.player.registryAccess())), "Refused merge changed the ledger");
    }

    private static int contents(ItemStack box) {
        var contents = PackageItem.getContents(box); int result = 0;
        for (int i = 0; i < contents.getSlots(); i++) result += contents.getStackInSlot(i).getCount();
        return result;
    }
    private static void reject(GameTestHelper helper, Fixture f, ItemStack box) {
        var before = box.copy(); var revision = f.record().state().revision();
        helper.assertTrue(!ReceiveService.receive(f.player, box), "Invalid or inaccessible parcel accepted");
        helper.assertTrue(ItemStack.matches(before, box) && f.record().state().revision() == revision, "Rejected parcel mutated inventory or ledger");
    }
    @GameTest(template = "empty")
    public static void logisticsFeedbackTracksPaidIntakeNotLocalChangesAndDoesNotReplay(GameTestHelper h) {
        var f=setup(h,FULL-8);var windows=new UUID[]{UUID.randomUUID()};
        java.util.function.Supplier<dev.scathiard.feedmepackages.network.PanelPackets.Snapshot> query=()->{
            f.player.tickCount+=4;
            return dev.scathiard.feedmepackages.network.PanelNetwork.query(f.player,
                    new dev.scathiard.feedmepackages.network.PanelPackets.Query(windows[0],f.player.containerMenu.containerId,true));
        };
        try {
            h.assertTrue(query.get().cells().getFirst().signal().sequence()==0,"Initial snapshot replayed logistics history");
            f.extract(4);h.assertTrue(query.get().cells().getFirst().signal().sequence()==0,"Local extraction emitted logistics feedback");
            h.assertTrue(ReceiveService.receive(f.player,f.box(16)),"Real parcel failed to enter the fixture cache");
            var arrived=query.get();var first=arrived.cells().getFirst().signal();
            h.assertTrue(first.sequence()>0 && first.kind()==dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.ARRIVAL
                    && arrived.cells().getFirst().amount()==FULL,"Intake did not reach the authoritative snapshot");
            f.extract(3);h.assertTrue(query.get().cells().getFirst().signal().equals(first),"Manual take replaced the logistics signal");
            ReceiveService.resume(f.player);var resumed=query.get();
            h.assertTrue(resumed.cells().getFirst().signal().sequence()>first.sequence(),"Partial parcel continuation did not produce a new arrival");
            var bytes=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),f.player.registryAccess());
            try {
                dev.scathiard.feedmepackages.network.PanelPackets.Snapshot.CODEC.encode(bytes,resumed);
                var decoded=dev.scathiard.feedmepackages.network.PanelPackets.Snapshot.CODEC.decode(bytes);
                h.assertTrue(decoded.cells().getFirst().signal().equals(resumed.cells().getFirst().signal()),"Snapshot codec lost logistics cause or order");
            } finally { bytes.release(); }
            dev.scathiard.feedmepackages.network.PanelNetwork.query(f.player,
                    new dev.scathiard.feedmepackages.network.PanelPackets.Query(windows[0],f.player.containerMenu.containerId,false));
            f.extract(4);ReceiveService.resume(f.player);windows[0]=UUID.randomUUID();
            h.assertTrue(query.get().cells().getFirst().signal().sequence()==0,"Reopened panel replayed closed-window intake");
        } finally { dev.scathiard.feedmepackages.network.PanelNetwork.forget(f.player); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeFluidParcelEntersFullInventoryAndRetainsExactRemainder(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var named = new FluidStack(Fluids.WATER, 1); named.set(DataComponents.CUSTOM_NAME, Component.literal("exact water"));
        var f = fluidSetup(h, named, 15950, 125); var box = f.box(125);
        box.set(DataComponents.CUSTOM_NAME, Component.literal("original fluid parcel"));
        var tank = PackageItem.getContents(box).getStackInSlot(0).copy(); tank.set(DataComponents.CUSTOM_NAME, Component.literal("original inner tank"));
        var contents = NonNullList.withSize(9, ItemStack.EMPTY); contents.set(0, tank); box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(contents));
        ParcelAuthentication.applyFluid(f.ledger(), f.player().registryAccess(), box, box.get(FmpRegistries.FLUID_PARCEL_SEAL.get()));
        for (int i = 0; i < 36; i++) f.player().getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        var held = f.player().getInventory().getItem(0);
        h.assertTrue(box.getItem().getClass().getName().endsWith("FluidPackageItem") && f.player().getInventory().add(box) && box.isEmpty(),
                "Native fluid package did not enter the real full-inventory receive hook");
        var rest = f.residual(); var saved = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        h.assertTrue(f.amount() == 16000  &&  FluidPackages.read(rest).getAmount() == 75  &&  f.record().fluids().state().pending(0) == 0
                 &&  f.player().getInventory().getItem(0) == held,
                "Partial fluid receipt lost stock or touched a full bag");
        h.assertTrue(rest.get(DataComponents.CUSTOM_NAME).getString().equals("original fluid parcel")
                && PackageItem.getContents(rest).getStackInSlot(0).get(DataComponents.CUSTOM_NAME).getString().equals("original inner tank")
                && FluidStack.isSameFluidSameComponents(named, FluidPackages.read(rest))
                && ParcelAuthentication.validFluid(f.ledger(), f.player().registryAccess(), rest, rest.get(FmpRegistries.FLUID_PARCEL_SEAL.get()))
                && saved.problem().isEmpty() && ItemStack.matches(saved.find(f.handle().cacheId()).fluids().residual(f.key()), rest),
                "Remainder lost outer/inner metadata, fluid components, authentication or persistence"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidReceiptNeedsNoEquipmentAndDoesNotArriveTwice(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 1000);
        h.assertTrue(ReceiveService.receive(f.player(), f.box(1000)) && f.amount() == 1000 && f.residual().isEmpty(),
                "No-air receipt did not accept a full bucket without equipment");
        var current = f.record(); var edit = current.fluids().state().edit(); edit.request(0, UUID.randomUUID(), f.player().getUUID(), 1, false);
        f.ledger().replace(f.handle(), current.state().revision(), current.withFluids(current.fluids().withState(edit.finish())));
        current = f.record(); ReceiveService.resume(f.player()); ReceiveService.resume(f.player());
        h.assertTrue(f.record() == current && f.record().fluids().state().pending(0) == 1 && f.amount() == 1000,
                "Repeated receipt continuation changed goods or a later order"); h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void splitFluidParcelsConserveTheSameExactContentsWithoutEquipment(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var whole = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 125);
        var split = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 125);
        h.assertTrue(ReceiveService.receive(whole.player(), whole.box(125)), "Whole native fluid parcel refused");
        for (int i = 0; i < 125; i++) h.assertTrue(ReceiveService.receive(split.player(), split.box(1)), "One-mB native parcel refused");
        h.assertTrue(whole.amount() == 125 && split.amount() == 125 && whole.residual().isEmpty() && split.residual().isEmpty()
                && whole.record().fluids().state().pending(0) == 0 && split.record().fluids().state().pending(0) == 0,
                "Splitting changed fluid contents or order settlement"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidFluidPackagesPassUnchangedAndCannotBorrowItemAuthentication(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 1000);
        for (int failure = 0; failure < 7; failure++) {
            var box = f.box(1000); var seal = box.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
            if (failure == 0) box.remove(FmpRegistries.FLUID_PARCEL_SEAL.get());
            if (failure == 1) PackageItem.addAddress(box, "not-the-owner");
            if (failure == 2) box.setCount(2);
            if (failure == 3) {
                box = FluidPackages.create(new FluidStack(Fluids.LAVA, 1000)); PackageItem.addAddress(box, seal.address());
                ParcelAuthentication.applyFluid(f.ledger(), f.player().registryAccess(), box, seal);
            }
            if (failure == 4) {
                var mixed = NonNullList.withSize(9, ItemStack.EMPTY); mixed.set(0, PackageItem.getContents(box).getStackInSlot(0).copy());
                mixed.set(1, new ItemStack(Items.DIAMOND)); box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(mixed));
                ParcelAuthentication.applyFluid(f.ledger(), f.player().registryAccess(), box, seal);
            }
            if (failure == 5) box.set(FmpRegistries.PARCEL_SEAL.get(), seal);
            if (failure == 6) {
                box.remove(FmpRegistries.FLUID_PARCEL_SEAL.get()); ParcelAuthentication.apply(f.ledger(), f.player().registryAccess(), box, seal);
                var itemSigned = box.get(FmpRegistries.PARCEL_SEAL.get()); box.remove(FmpRegistries.PARCEL_SEAL.get()); box.set(FmpRegistries.FLUID_PARCEL_SEAL.get(), itemSigned);
            }
            rejectFluid(h, f, box);
        }
        h.assertTrue(FluidPackages.status() == FluidPackages.Status.READY && ReceiveService.receive(f.player(), f.box(1000)),
                "Invalid input disabled valid native fluid reception"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidResidualSlotsAreBoundedAndDoNotBlockOtherVariants(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 15999, 1000);
        h.assertTrue(ReceiveService.receive(f.player(), f.box(1000)), "Initial fluid parcel refused");
        rejectFluid(h, f, f.box(1000));
        var before = f.record(); var edit = before.fluids().state().edit();
        var lava = FluidVariantKey.of(new FluidStack(Fluids.LAVA, 1), f.player().registryAccess());
        edit.filter(1, lava); edit.insert(1, lava, 15999);
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        var lavaBox = FluidPackages.create(new FluidStack(Fluids.LAVA, 500)); var address = SupplyService.address(f.player()); PackageItem.addAddress(lavaBox, address);
        ParcelAuthentication.applyFluid(f.ledger(), f.player().registryAccess(), lavaBox, new ParcelSeal(UUID.randomUUID(), f.handle().cacheId(),
                f.player().getUUID(), UUID.randomUUID(), lava.encoded(), f.record().fluids().state().cells().get(1).filterRevision(), address, false, ""));
        h.assertTrue(ReceiveService.receive(f.player(), lavaBox) && FluidPackages.read(f.record().fluids().residual(lava)).getAmount() == 499
                && FluidPackages.read(f.residual()).getAmount() == 999, "Unrelated fluid cells contended for one residual slot");
        before = f.record(); edit = before.fluids().state().edit(); edit.extract(0, 2000);
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        ReceiveService.resume(f.player());
        h.assertTrue(f.amount() == 14999 && f.residual().isEmpty() && FluidPackages.read(f.record().fluids().residual(lava)).getAmount() == 499,
                "Resuming one fluid residual removed or charged another"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidReceiptFollowsWornAccessButOwnedRemainderCanFollowOrdinaryPendant(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 16000, 125); var box = f.box(125);
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        rejectFluid(h, f, box); TestPlayers.necklace(f.player()).setStackInSlot(0, pendant);
        var stranger = TestPlayers.create(h, pendant.copy());
        h.assertTrue(!ReceiveService.receive(stranger, box) && box.getCount() == 1, "A new holder intercepted someone else's incoming parcel");
        TestPlayers.necklace(stranger).setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(ReceiveService.receive(f.player(), box) && f.amount() == 16000 && FluidPackages.read(f.residual()).getAmount() == 125,
                "A full cache lost its authenticated fluid remainder");
        TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY); TestPlayers.necklace(stranger).setStackInSlot(0, pendant);
        var before = f.record(); var edit = before.fluids().state().edit();
        h.assertTrue(edit.extract(0, 125) == 125, "Fixture failed to free exactly the residual volume");
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        ReceiveService.resume(stranger);
        h.assertTrue(f.amount() == 16000 && f.residual().isEmpty(),
                "Owned fluid remainder did not follow the ordinary pendant"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void unrelatedNativePackageWithAFluidSealIsNeverConsumed(GameTestHelper h) {
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 1000);
        var box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 4))); var address = SupplyService.address(f.player()); PackageItem.addAddress(box, address);
        ParcelAuthentication.applyFluid(f.ledger(), f.player().registryAccess(), box, new ParcelSeal(UUID.randomUUID(), f.handle().cacheId(), f.player().getUUID(),
                f.request(), f.key().encoded(), f.record().fluids().state().cells().get(0).filterRevision(), address, false, ""));
        rejectFluid(h, f, box); h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidatedFluidResidualWaitsThenReturnsWholeWithoutCycling(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 15950, 125);
        h.assertTrue(ReceiveService.receive(f.player(), f.box(125)), "Fluid residual setup failed");
        var residual = f.residual(); var before = f.record(); var edit = before.fluids().state().edit();
        edit.extract(0, 16000); edit.filter(0, null);
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        for (int i = 0; i < 36; i++) f.player().getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        before = f.record(); ReceiveService.resume(f.player());
        var saved = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        h.assertTrue(f.record() == before && ItemStack.matches(residual, f.residual()) && saved.problem().isEmpty()
                && ItemStack.matches(residual, saved.find(f.handle().cacheId()).fluids().residual(f.key())),
                "Invalidated fluid residual was lost or could not survive persistence");
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        f.player().getInventory().setItem(0, ItemStack.EMPTY); ReceiveService.resume(f.player());
        h.assertTrue(f.record() == before && f.player().getInventory().getItem(0).isEmpty(), "Unworn fluid cache returned a parcel");
        TestPlayers.necklace(f.player()).setStackInSlot(0, pendant); ReceiveService.resume(f.player());
        var returned = f.player().getInventory().getItem(0); before = f.record();
        for (int tick = 0; tick < 10; tick++) ReceiveService.resume(f.player());
        h.assertTrue(f.residual().isEmpty() && ItemStack.matches(returned, residual) && FluidPackages.read(returned).getAmount() == 75
                && f.record() == before && f.player().getInventory().getItem(0) == returned,
                "Fluid handback altered the original parcel, cycled ownership or duplicated contents"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void unverifiableFluidResidualRemainsIntactWithFreeInventory(GameTestHelper h) {
        if (!fluidAvailable(h)) return;
        var f = fluidSetup(h, new FluidStack(Fluids.WATER, 1), 15950, 125);
        h.assertTrue(ReceiveService.receive(f.player(), f.box(125)), "Fluid residual setup failed");
        var bad = f.residual(); bad.remove(FmpRegistries.FLUID_PARCEL_SEAL.get()); var before = f.record();
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withResidual(f.key(), bad)));
        before = f.record();
        for (int tick = 0; tick < 10; tick++) ReceiveService.resume(f.player());
        h.assertTrue(f.record() == before && ItemStack.matches(bad, f.residual()) && f.player().getInventory().isEmpty(),
                "Unverifiable fluid became spendable or disappeared"); h.succeed();
    }

    private static boolean fluidAvailable(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("fluidlogistics")) {
            FeedMePackages.LOGGER.info("FMP_FLUID_TEST_SKIPPED: optional author artifact absent"); h.succeed(); return false;
        }
        h.assertTrue(FluidPackages.status() == FluidPackages.Status.READY, "Installed FluidLogistics interface unavailable"); return true;
    }

    private record FluidFixture(ServerPlayer player, CacheLedger ledger, CacheHandle handle, FluidVariantKey key, UUID request) {
        CacheRecord record() { return ledger.find(handle.cacheId()); }
        long amount() { return record().fluids().state().cells().get(0).amount(); }
        ItemStack residual() { return record().fluids().residual(key); }
        ItemStack box(int amount) {
            var box = FluidPackages.create(key.stack(player.registryAccess(), amount)); var address = SupplyService.address(player); PackageItem.addAddress(box, address);
            ParcelAuthentication.applyFluid(ledger, player.registryAccess(), box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(),
                    request, key.encoded(), record().fluids().state().cells().get(0).filterRevision(), address, false, "")); return box;
        }
    }

    private static FluidFixture fluidSetup(GameTestHelper h, FluidStack sample, int initial, int request) {
        var player = TestPlayers.create(h, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
        var key = FluidVariantKey.of(sample, player.registryAccess()); var order = UUID.randomUUID(); var edit = before.fluids().state().edit();
        edit.filter(0, key); edit.thresholds(0, 2, -1); edit.request(0, order, player.getUUID(), request, false); edit.insert(0, key, initial);
        ledger.replace(handle, before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        return new FluidFixture(player, ledger, handle, key, order);
    }

    private static void rejectFluid(GameTestHelper h, FluidFixture f, ItemStack box) {
        var original = box.copy(); var record = f.record();
        h.assertTrue(!ReceiveService.receive(f.player(), box) && ItemStack.matches(original, box) && f.record() == record,
                "Rejected fluid parcel changed caller contents or cache state");
    }
}
