package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;
import java.util.function.Consumer;

/** Production inventory receipt, including ordinary parcels which cannot prove an order arrived. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class NativeReceiptTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        tests.add(test("ordinary_zero_stock", NativeReceiptTests::ordinary));
        tests.add(test("mixed_unknown_whole_refusal", NativeReceiptTests::mixed));
        tests.add(test("exact_components", NativeReceiptTests::components));
        tests.add(test("at_or_above_return_line", NativeReceiptTests::returnLine));
        tests.add(test("capacity_partial_repeat_and_manual_open", NativeReceiptTests::partial));
        tests.add(test("signed_partial_settles_once", NativeReceiptTests::signedPartial));
        tests.add(test("invalid_seal_never_falls_back", NativeReceiptTests::invalidSeal));
        tests.add(test("removed_seal_never_claims_order", NativeReceiptTests::removedSeal));
        tests.add(test("wrong_address_and_unworn", NativeReceiptTests::wrongAddress));
        tests.add(test("legacy_address_signed_delivery", NativeReceiptTests::legacy));
        tests.add(test("full_inventory_generic_handoff", NativeReceiptTests::fullInventory));
        if (ModList.get().isLoaded("fluidlogistics")) {
            tests.add(test("ordinary_fluid", h -> fluid(h, false)));
            tests.add(test("ordinary_fluid_partial", h -> fluid(h, true)));
            tests.add(test("fluid_return_line", NativeReceiptTests::fluidReturnLine));
            tests.add(test("fluid_mixed_refusal", NativeReceiptTests::fluidMixed));
        }
        FeedMePackages.LOGGER.info("FMP_NATIVE_RECEIPT_TESTS SELECTED count={}", tests.size());
        return tests;
    }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction("native_receipt", "native_receipt_" + name, FeedMePackages.MOD_ID + ":empty", 100, 0, true, body);
    }
    private static ItemStack box(ReceiveTests.Fixture f, ItemStack... contents) {
        var box = PackageItem.containing(List.of(contents)); PackageItem.addAddress(box, SupplyService.address(f.player())); return box;
    }
    private static void scan(ReceiveTests.Fixture f, ItemStack box) {
        f.player().getInventory().setItem(0, box); ReceiveService.resume(f.player());
    }
    private static int stock(ReceiveTests.Fixture f) { return f.record().state().cells().getFirst().amount(); }
    private static void reject(GameTestHelper h, ReceiveTests.Fixture f, ItemStack box) {
        var old = box.copy(); var record = f.record(); scan(f, box);
        h.assertTrue(record == f.record() && ItemStack.matches(old, f.player().getInventory().getItem(0)), "Refusal changed goods or ledger");
    }
    private static void ordinary(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = box(f, new ItemStack(Items.STONE, 8)); scan(f, parcel);
        h.assertTrue(SupplyService.address(f.player()).equals("@" + f.player().getGameProfile().getName()), "Not a native player address");
        h.assertTrue(stock(f) == 8 && parcel.isEmpty() && f.player().getInventory().getItem(0).isEmpty()
                && f.record().state().pending(0) == 64, "Ordinary receipt lost goods or settled an unrelated order");
        h.succeed();
    }
    private static void mixed(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = box(f, new ItemStack(Items.STONE, 8), new ItemStack(Items.IRON_INGOT, 3));
        reject(h, f, parcel);
        var before = f.record(); var edit = before.state().edit(); edit.filter(1, ItemVariantKey.of(new ItemStack(Items.IRON_INGOT), f.player().registryAccess()));
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        ReceiveService.resume(f.player());
        h.assertTrue(stock(f) == 8 && f.record().state().cells().get(1).amount() == 3 && f.record().state().pending(0) == 64, "Whole mixed receipt failed after both filters existed"); h.succeed();
    }
    private static void components(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var named = new ItemStack(Items.STONE, 8); named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact"));
        reject(h, f, box(f, named));
        var before = f.record(); var edit = before.state().edit(); edit.filter(1, ItemVariantKey.of(named, f.player().registryAccess()));
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish())); ReceiveService.resume(f.player());
        h.assertTrue(stock(f) == 0 && f.record().state().cells().get(1).amount() == 8, "Named goods collapsed into default filter"); h.succeed();
    }
    private static void returnLine(GameTestHelper h) {
        for (boolean sealed : List.of(false, true)) for (int initial : new int[]{64, 65}) {
            var f = ReceiveTests.setup(h, initial); var before = f.record(); var edit = before.state().edit(); edit.settings(0, new CellSettings(32, 64, 1, -1));
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
            var parcel = sealed ? f.box(8) : box(f, new ItemStack(Items.STONE, 8)); reject(h, f, parcel);
            f.extract(initial - 63); ReceiveService.resume(f.player());
            h.assertTrue(stock(f) == 71 && f.record().state().pending(0) == (sealed ? 56 : 64), "Return line clamped intake or settlement");
        }
        h.succeed();
    }
    private static void partial(GameTestHelper h) {
        var f = ReceiveTests.setup(h, ReceiveTests.FULL); var parcel = box(f, new ItemStack(Items.STONE, 8)); reject(h, f, parcel);
        f.extract(4); ReceiveService.resume(f.player());
        var rest = f.player().getInventory().getItem(0);
        h.assertTrue(stock(f) == ReceiveTests.FULL && PackageItem.getContents(rest).getStackInSlot(0).getCount() == 4 && f.residual().isEmpty(), "Ordinary remainder left its real inventory slot");
        var before = f.record(); ReceiveService.resume(f.player()); h.assertTrue(before == f.record() && f.player().getInventory().getItem(0) == rest, "Full repeated scan mutated remainder");
        f.extract(4); ReceiveService.resume(f.player());
        h.assertTrue(stock(f) == ReceiveTests.FULL && f.player().getInventory().getItem(0).isEmpty() && f.record().state().pending(0) == 64, "Final remainder duplicated or claimed pending");
        f.extract(2); scan(f, box(f, new ItemStack(Items.STONE, 8)));
        rest = f.player().getMainHandItem();
        ((PackageItem) rest.getItem()).open(h.getLevel(), f.player(), net.minecraft.world.InteractionHand.MAIN_HAND);
        ReceiveService.resume(f.player());
        h.assertTrue(f.player().getMainHandItem().is(Items.STONE) && f.player().getMainHandItem().getCount() == 6 && f.residual().isEmpty(), "Manually opened remainder left phantom receipt state"); h.succeed();
    }
    private static void signedPartial(GameTestHelper h) {
        var f = ReceiveTests.setup(h, ReceiveTests.FULL - 4); scan(f, f.box(8));
        h.assertTrue(stock(f) == ReceiveTests.FULL && f.record().state().pending(0) == 56 && !f.residual().isEmpty(), "Authenticated local arrival not settled in full");
        ReceiveService.resume(f.player()); f.extract(4); ReceiveService.resume(f.player());
        h.assertTrue(f.residual().isEmpty() && stock(f) == ReceiveTests.FULL && f.record().state().pending(0) == 56, "Residual settled twice"); h.succeed();
    }
    private static void invalidSeal(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = f.box(8);
        parcel.set(AllDataComponents.PACKAGE_CONTENTS, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 9))));
        reject(h, f, parcel); h.succeed();
    }
    private static void removedSeal(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = f.box(64); parcel.remove(FmpRegistries.PARCEL_SEAL.get()); scan(f, parcel); f.extract(64);
        h.assertTrue(stock(f) == 0 && f.record().state().pending(0) == 64 && f.record().state().requestable(0) == 64, "Stripped seal wrongly settled an order"); h.succeed();
    }
    private static void wrongAddress(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = box(f, new ItemStack(Items.STONE, 8));
        PackageItem.addAddress(parcel, "@SomeoneElse"); reject(h, f, parcel);
        PackageItem.addAddress(parcel, SupplyService.address(f.player())); var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0);
        TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY); reject(h, f, parcel);
        TestPlayers.necklace(f.player()).setStackInSlot(0, pendant); ReceiveService.resume(f.player());
        h.assertTrue(stock(f) == 8 && f.record().state().pending(0) == 64, "Reequip ordinary receipt failed"); h.succeed();
    }
    private static void legacy(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = f.box(8); var seal = parcel.get(FmpRegistries.PARCEL_SEAL.get()); var address = "FMP@" + f.player().getGameProfile().getName();
        PackageItem.addAddress(parcel, address); ParcelAuthentication.apply(f.ledger(), f.player().registryAccess(), parcel,
                new ParcelSeal(seal.parcelId(), seal.cacheId(), seal.playerId(), seal.requestId(), seal.variant(), seal.filterRevision(), address, false, ""));
        scan(f, parcel); h.assertTrue(stock(f) == 8 && f.record().state().pending(0) == 56, "Old authenticated address could not arrive"); h.succeed();
    }
    private static void fullInventory(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); var parcel = box(f, new ItemStack(Items.STONE, 8));
        h.assertTrue(f.player().getInventory().add(parcel) && parcel.isEmpty() && stock(f) == 8, "Generic inventory handoff failed");
        f.extract(8); var before = f.record(); var edit = before.state().edit(); edit.insert(0, f.key(), ReceiveTests.FULL - 4);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        parcel = box(f, new ItemStack(Items.STONE, 8)); before = f.record(); var original = parcel.copy();
        h.assertTrue(!f.player().getInventory().add(parcel) && f.record() == before && ItemStack.matches(original, parcel), "Partial ordinary full-bag handoff consumed without remainder space"); h.succeed();
    }
    private static void fluid(GameTestHelper h, boolean partial) {
        var f = ReceiveTests.fluidSetup(h, new FluidStack(Fluids.WATER, 1), partial ? 15950 : 0, 1000);
        var parcel = f.box(125); parcel.remove(FmpRegistries.FLUID_PARCEL_SEAL.get()); f.player().getInventory().setItem(0, parcel); ReceiveService.resume(f.player());
        h.assertTrue(f.amount() == (partial ? 16000 : 125) && f.record().fluids().state().pending(0) == 1000 && f.residual().isEmpty(), "Ordinary fluid receipt or pending incorrect");
        var rest = f.player().getInventory().getItem(0);
        h.assertTrue(partial ? FluidPackages.read(rest).getAmount() == 75 : rest.isEmpty(), "Ordinary fluid remainder lost");
        if (partial) {
            var before = f.record(); ReceiveService.resume(f.player()); h.assertTrue(before == f.record(), "Full fluid repeated receipt changed ledger");
            var edit = before.fluids().state().edit(); edit.extract(0, 75);
            f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish()))); ReceiveService.resume(f.player());
            h.assertTrue(f.amount() == 16000 && f.player().getInventory().getItem(0).isEmpty() && f.record().fluids().state().pending(0) == 1000, "Fluid final remainder settled unrelated pending");
        }
        h.succeed();
    }
    private static void fluidReturnLine(GameTestHelper h) {
        var f = ReceiveTests.fluidSetup(h, new FluidStack(Fluids.WATER, 1), 1000, 1000); var before = f.record(); var edit = before.fluids().state().edit();
        edit.settings(0, new CellSettings(500, 1000, 1, -1)); f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        var parcel = f.box(125); parcel.remove(FmpRegistries.FLUID_PARCEL_SEAL.get()); f.player().getInventory().setItem(0, parcel); before = f.record(); ReceiveService.resume(f.player());
        h.assertTrue(f.record() == before && FluidPackages.read(parcel).getAmount() == 125, "Fluid at return line accepted");
        edit = before.fluids().state().edit(); edit.extract(0, 1); f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish()))); ReceiveService.resume(f.player());
        h.assertTrue(f.amount() == 1124 && f.record().fluids().state().pending(0) == 1000, "Fluid intake incorrectly clamped to return line"); h.succeed();
    }
    private static void fluidMixed(GameTestHelper h) {
        var f = ReceiveTests.fluidSetup(h, new FluidStack(Fluids.WATER, 1), 0, 1000); var parcel = f.box(125); parcel.remove(FmpRegistries.FLUID_PARCEL_SEAL.get());
        var contents = net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY); contents.set(0, PackageItem.getContents(parcel).getStackInSlot(0).copy()); contents.set(1, new ItemStack(Items.DIAMOND));
        parcel.set(AllDataComponents.PACKAGE_CONTENTS, net.minecraft.world.item.component.ItemContainerContents.fromItems(contents));
        var original = parcel.copy(); var before = f.record(); f.player().getInventory().setItem(0, parcel); ReceiveService.resume(f.player());
        h.assertTrue(f.record() == before && ItemStack.matches(original, parcel), "Mixed fluid/item parcel was partially destroyed"); h.succeed();
    }
}
