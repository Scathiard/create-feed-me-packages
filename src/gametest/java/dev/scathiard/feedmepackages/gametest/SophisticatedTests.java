package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.compat.sophisticated.*;
import dev.scathiard.feedmepackages.network.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.*;

/** Real optional backpack, crafting upgrade, native transfer implementation and native result clicks. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class SophisticatedTests {
    @FunctionalInterface interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("sophisticatedbackpacks")) return List.of();
        return List.of(test("native_fill_and_craft", SophisticatedTests::fill), test("native_maximum", SophisticatedTests::maximum),
                test("native_refill_opt_in", SophisticatedTests::refill), test("native_remainder", SophisticatedTests::remainder),
                test("full_displacement", SophisticatedTests::full), test("stale_identity", SophisticatedTests::stale),
                test("config_and_wire", SophisticatedTests::wire));
    }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("sophisticated", "fmp_sophisticated_" + name, FeedMePackages.MOD_ID + ":empty", 100, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native Sophisticated fixture failed", failed); } });
    }
    private record Fixture(ServerPlayer player, ItemStack bag, IItemHandlerModifiable inventory, SophisticatedAccess.Context context, CacheHandle handle) {
        long stock() { return CacheLedger.get(player.getServer()).find(handle.cacheId()).state().cells().get(0).amount(); }
        int grid() { return context.grid().stream().mapToInt(s -> s.getItem().getCount()).sum(); }
        void refill(boolean enabled) throws ReflectiveOperationException {
            context.crafting().getClass().getMethod("setRefillCraftingGrid", boolean.class).invoke(context.crafting(), enabled);
        }
        Slot result() throws ReflectiveOperationException {
            @SuppressWarnings("unchecked") var slots = (List<Slot>) context.crafting().getClass().getMethod("getSlots").invoke(context.crafting());
            return slots.stream().filter(s -> s instanceof ResultSlot).findFirst().orElseThrow();
        }
        void clickResult() throws ReflectiveOperationException { context.menu().clicked(result().index, 0, ClickType.PICKUP, player); }
        SophisticatedTransfer.Request request(String recipe, boolean maximum) {
            return new SophisticatedTransfer.Request(context.menu().containerId, context.upgrade(), handle.cacheId(), ResourceLocation.withDefaultNamespace(recipe), maximum, TransferOrigin.Viewer.JEI);
        }
        boolean fill(String recipe, boolean maximum) {
            var result = SophisticatedTransfer.apply(player, request(recipe, maximum));
            FeedMePackages.LOGGER.info("FMP_SOPHISTICATED_TRANSFER recipe={} result={} cache={} grid={}", recipe, result, stock(), grid());
            return result == SophisticatedMaterials.Transfer.Result.OK;
        }
    }
    private static Fixture setup(GameTestHelper h, Item item, int count) throws ReflectiveOperationException {
        h.assertTrue(CompatibilityStatus.sophisticated() == CompatibilityStatus.State.READY, "Sophisticated native ports were not ready");
        var player = TestPlayers.create(h, FmpRegistries.PENDANT.toStack()); ConsumptionTests.seed(player, 0, new ItemStack(item), count);
        var handle = AccessGate.resolve(player).handle();
        h.assertTrue(CacheLedger.get(player.getServer()).find(handle.cacheId()).state().cells().get(0).amount() == count,
                "Fixture requested more material than its real cache can hold");
        var bag = createBackpack(player);
        var kind = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext");
        var nativeContext = Class.forName(kind.getName() + "$Item").getConstructor(String.class, int.class).newInstance("main", 8);
        var menu = (AbstractContainerMenu) Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer")
                .getConstructor(int.class, Player.class, kind).newInstance(31, player, nativeContext);
        // The first UUID initialization can replace the author's cached wrapper. Use the live menu's handler.
        var liveWrapper = menu.getClass().getMethod("getStorageWrapper").invoke(menu);
        var inventory = (IItemHandlerModifiable) liveWrapper.getClass().getMethod("getInventoryHandler").invoke(liveWrapper);
        player.containerMenu = menu; var context = SophisticatedAccess.resolve(player);
        h.assertTrue(context != null && menu.stillValid(player), "Real native crafting upgrade context did not open"); context.open();
        return new Fixture(player, bag, inventory, context, handle);
    }
    static ItemStack createBackpack(ServerPlayer player) throws ReflectiveOperationException {
        var bag = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack")));
        if (bag.isEmpty()) throw new IllegalStateException("Native backpack absent");
        player.getInventory().setItem(8, bag);
        var wrapper = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper")
                .getMethod("fromStack", ItemStack.class).invoke(null, bag);
        // A brand-new native backpack has no contents UUID until its first inventory write.
        var inventory = (IItemHandlerModifiable) wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
        inventory.setStackInSlot(0, new ItemStack(Items.DIRT)); inventory.setStackInSlot(0, ItemStack.EMPTY);
        var upgrades = (IItemHandlerModifiable) wrapper.getClass().getMethod("getUpgradeHandler").invoke(wrapper);
        upgrades.setStackInSlot(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:crafting_upgrade"))));
        return bag;
    }
    private static void fill(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_LOG, 8); f.inventory.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        h.assertTrue(f.fill("oak_planks", false) && f.stock() == 8 && f.inventory.getStackInSlot(0).isEmpty() && f.grid() == 1, "Native bag was not first source");
        f.clickResult();
        h.assertTrue(f.context.menu().getCarried().is(Items.OAK_PLANKS) && f.context.menu().getCarried().getCount() == 4
                && f.grid() == 0 && f.stock() == 8, "Default native craft changed result: cursor=" + f.context.menu().getCarried() + " grid=" + f.grid() + " cache=" + f.stock());
        f.context.menu().setCarried(ItemStack.EMPTY);
        h.assertTrue(f.fill("oak_planks", false) && f.stock() == 7 && f.grid() == 1, "Cache did not fill real native grid");
        f.clickResult();
        h.assertTrue(f.context.menu().getCarried().getCount() == 4 && f.grid() == 0 && f.stock() == 7
                && f.player.getInventory().getItem(8) == f.bag, "Native result spent twice or replaced the backpack object");
        h.succeed();
    }
    private static void maximum(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_PLANKS, 128); f.inventory.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 2));
        f.player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        h.assertTrue(f.fill("stick", true) && f.stock() == 3 && f.grid() == 128
                && f.inventory.getStackInSlot(0).isEmpty() && f.player.getInventory().getItem(0).isEmpty(),
                "Repeated-ingredient maximum lost native priority/capacity: cache=" + f.stock() + " grid=" + f.grid());
        f.clickResult();
        h.assertTrue(f.context.menu().getCarried().is(Items.STICK) && f.context.menu().getCarried().getCount() == 4
                && f.grid() == 126 && f.stock() == 3, "Maximum fill did not use the native result consumer"); h.succeed();
    }
    private static void refill(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_PLANKS, 10); f.refill(true);
        f.context.grid().get(0).set(new ItemStack(Items.OAK_PLANKS)); f.context.grid().get(3).set(new ItemStack(Items.OAK_PLANKS));
        f.inventory.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS)); f.player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        f.clickResult();
        h.assertTrue(f.stock() == 10 && f.grid() == 2 && f.inventory.getStackInSlot(0).isEmpty() && f.player.getInventory().getItem(0).isEmpty(), "Native refill lost physical-first ordering");
        f.clickResult(); h.assertTrue(f.stock() == 8 && f.grid() == 2 && f.context.menu().getCarried().getCount() == 8, "Opt-in native refill did not take exactly two cached ingredients");
        f.refill(false); f.clickResult(); h.assertTrue(f.stock() == 8 && f.grid() == 0 && f.context.menu().getCarried().getCount() == 12, "Disabled refill still consumed cache"); h.succeed();
    }
    private static void remainder(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.MILK_BUCKET, 2); f.refill(true);
        f.inventory.setStackInSlot(0, new ItemStack(Items.SUGAR, 2)); f.inventory.setStackInSlot(1, new ItemStack(Items.EGG));
        f.inventory.setStackInSlot(2, new ItemStack(Items.WHEAT, 3));
        f.inventory.setStackInSlot(3, new ItemStack(Items.MILK_BUCKET));
        h.assertTrue(f.fill("cake", false) && f.stock() == 0 && f.grid() == 9, "Unstackable cake inputs did not transfer"); f.clickResult();
        int buckets = f.context.grid().stream().filter(s -> s.getItem().is(Items.BUCKET)).mapToInt(s -> s.getItem().getCount()).sum();
        h.assertTrue(f.context.menu().getCarried().is(Items.CAKE) && buckets == 3 && f.stock() == 0, "Native recipe remainder was lost or duplicated"); h.succeed();
    }
    private static void full(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_LOG, 8);
        for (int i = 0; i < f.inventory.getSlots(); i++) f.inventory.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) if (i != 8) f.player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        for (var slot : f.context.grid()) slot.set(new ItemStack(Items.DIRT, 64));
        h.assertTrue(!f.fill("oak_planks", false) && f.stock() == 8 && f.grid() == 576
                && f.context.grid().stream().allMatch(s -> s.getItem().is(Items.DIRT)), "No-space operation published partial sources or displaced grid");
        h.assertTrue(SophisticatedMaterials.current() == null, "Native draft leaked out of failed transfer"); h.succeed();
    }
    @SuppressWarnings("unchecked")
    private static void stale(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_LOG, 8);
        var recipe = (RecipeHolder<CraftingRecipe>) f.player.serverLevel().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        var tx = SophisticatedMaterials.prepare(f.player, f.context.menu().containerId, f.context.upgrade(), f.handle.cacheId(), recipe, false);
        h.assertTrue(tx != null, "Native source draft missing");
        var pendant = TestPlayers.necklace(f.player).getStackInSlot(0); TestPlayers.necklace(f.player).setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(!tx.execute() && f.stock() == 8 && f.grid() == 0, "Unworn draft committed");
        TestPlayers.necklace(f.player).setStackInSlot(0, pendant);
        tx = SophisticatedMaterials.prepare(f.player, f.context.menu().containerId, f.context.upgrade(), f.handle.cacheId(), recipe, false);
        f.inventory.setStackInSlot(0, new ItemStack(Items.DIRT));
        h.assertTrue(tx != null && !tx.execute() && f.stock() == 8 && f.grid() == 0, "Changed native inventory did not invalidate draft");
        h.assertTrue(!SophisticatedTransfer.accept(f.player, new SophisticatedTransfer.Request(99, f.context.upgrade(), f.handle.cacheId(), recipe.id(), false, TransferOrigin.Viewer.JEI))
                && f.stock() == 8, "Wrong menu request consumed cache"); h.succeed();
    }
    private static void wire(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, Items.OAK_LOG, 8); var request = f.request("oak_planks", false);
        var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            SophisticatedTransfer.Request.CODEC.encode(wire, request);
            h.assertTrue(SophisticatedTransfer.Request.CODEC.decode(wire).equals(request), "Source request wire changed identity");
            wire.clear(); SophisticatedTransfer.Request.CODEC.encode(wire, request); wire.writeByte(0);
            boolean rejected = false;
            try { SophisticatedTransfer.Request.CODEC.decode(wire); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "Trailing source payload was accepted");
        } finally { wire.release(); }
        boolean configured = FmpConfig.SOPHISTICATED.get(), jei = FmpConfig.JEI.get();
        try {
            FmpConfig.SOPHISTICATED.set(false); FmpConfig.refreshServer();
            h.assertTrue(!f.fill("oak_planks", false) && f.stock() == 8 && f.grid() == 0, "Disabled adapter spent stock");
            FmpConfig.SOPHISTICATED.set(true); FmpConfig.JEI.set(false); FmpConfig.refreshServer();
            h.assertTrue(!f.fill("oak_planks", false) && f.stock() == 8 && f.grid() == 0, "Disabled viewer spent stock");
        } finally { FmpConfig.SOPHISTICATED.set(configured); FmpConfig.JEI.set(jei); FmpConfig.refreshServer(); }
        h.succeed();
    }
}
