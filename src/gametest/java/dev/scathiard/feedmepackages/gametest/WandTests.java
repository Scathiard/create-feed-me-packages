package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

/** Author-owned count, placement, material consumption and undo; adapters are never called directly. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class WandTests {
    private static final String ROOT = "dev.polaris_light.constructionwand.";
    @FunctionalInterface private interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("constructionwand")) return List.of();
        return List.of(test("native_sources", WandTests::sources), test("placement_undo", WandTests::placementUndo),
                test("canceled_placement", WandTests::canceledPlacement), test("stale_preview", WandTests::stalePreview),
                test("reserved_and_variants", WandTests::reservedAndVariants), test("native_container_and_spare", WandTests::nativeContainerAndSpare),
                test("config_disabled", WandTests::configDisabled), test("native_use_and_creative", WandTests::nativeUseAndCreative));
    }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("wand", "fmp_wand_" + name, FeedMePackages.MOD_ID + ":empty", 200, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native wand fixture failed", e); } });
    }
    private static ServerPlayer player(GameTestHelper h) {
        var p = TestPlayers.real(h, UUID.randomUUID()); TestPlayers.nativePackets(p);
        TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        p.setPos(h.absoluteVec(new Vec3(1, 2, 1))); return p;
    }
    private static int count(ServerPlayer p, Item item) throws ReflectiveOperationException {
        return (int) Class.forName(ROOT + "basics.WandUtil").getMethod("countItem", Player.class, Item.class).invoke(null, p, item);
    }
    private static void sources(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); p.getInventory().setItem(9, new ItemStack(Items.STONE, 3));
        h.assertTrue(count(p, Items.STONE) == 3, "Native inventory control failed");
        ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 120);
        h.assertTrue(count(p, Items.STONE) == 123 && ConsumptionTests.stock(p, 0) == 120, "Cache source not counted exactly once or query consumed it");
        int remaining = take(p, new ItemStack(Items.STONE, 100));
        h.assertTrue(remaining == 0 && p.getInventory().getItem(9).isEmpty() && ConsumptionTests.stock(p, 0) == 23,
                "Native extraction did not take inventory 3 then cache 97: " + remaining);
        h.succeed();
    }
    private static int take(ServerPlayer p, ItemStack material) throws ReflectiveOperationException {
        var wand = wand(); var options = Class.forName(ROOT + "basics.option.WandOptions");
        var supplier = Class.forName(ROOT + "wand.supplier.SupplierInventory").getConstructor(Player.class, options)
                .newInstance(p, options.getConstructor(ItemStack.class).newInstance(wand));
        return (int) supplier.getClass().getMethod("takeItemStack", ItemStack.class).invoke(supplier, material);
    }
    private static ItemStack wand() {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("constructionwand", "iron_wand"));
        if (item == Items.AIR) throw new IllegalStateException("Author iron wand missing");
        return new ItemStack(item);
    }
    private static Object job(GameTestHelper h, ServerPlayer p) throws ReflectiveOperationException {
        var support = h.absolutePos(new BlockPos(3, 1, 3)); h.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
        var wand = wand(); p.getInventory().setItem(0, wand);
        var job = Class.forName(ROOT + "wand.WandJob").getConstructor(Player.class, Level.class, BlockHitResult.class, ItemStack.class)
                .newInstance(p, h.getLevel(), hit, wand);
        job.getClass().getMethod("getSnapshots").invoke(job); return job;
    }
    private static void placementUndo(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 3);
        var job = job(h, p); var target = h.absolutePos(new BlockPos(3, 2, 3));
        h.assertTrue((int) job.getClass().getMethod("blockCount").invoke(job) == 1, "Native preview missed cache-only material");
        h.assertTrue(ConsumptionTests.stock(p, 0) == 3 && h.getLevel().isEmptyBlock(target), "Preview moved items or placed blocks");
        h.assertTrue((boolean) job.getClass().getMethod("doIt").invoke(job) && h.getLevel().getBlockState(target).is(Blocks.STONE)
                && ConsumptionTests.stock(p, 0) == 2, "Native build failed or did not charge once");
        var history = Class.forName(ROOT + "ConstructionWand").getField("undoHistory").get(null);
        history.getClass().getMethod("updateClient", Player.class, boolean.class, boolean.class).invoke(history, p, true, false);
        h.assertTrue((boolean) history.getClass().getMethod("undo", Player.class, Level.class, BlockPos.class).invoke(history, p, h.getLevel(), target)
                && h.getLevel().isEmptyBlock(target) && ConsumptionTests.stock(p, 0) == 2
                && p.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum() == 1,
                "Native undo did not return exactly one real inventory item");
        h.assertTrue(!(boolean) history.getClass().getMethod("undo", Player.class, Level.class, BlockPos.class).invoke(history, p, h.getLevel(), target),
                "Repeated undo duplicated materials");
        h.succeed();
    }
    private static void canceledPlacement(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 3); var job = job(h, p);
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> cancel = event -> {
            if (event.getEntity() == p) event.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try {
            h.assertTrue(!(boolean) job.getClass().getMethod("doIt").invoke(job)
                    && h.getLevel().isEmptyBlock(h.absolutePos(new BlockPos(3, 2, 3))) && ConsumptionTests.stock(p, 0) == 3
                    && p.getMainHandItem().getDamageValue() == 0, "Canceled native placement spent material or durability");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel); }
        h.succeed();
    }
    private static void stalePreview(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 1); var job = job(h, p);
        var tx = MaterialTransaction.open(p).orElseThrow();
        h.assertTrue(tx.takeCache(new ItemStack(Items.STONE), 1) && tx.commit(), "Depleted preview fixture failed");
        h.assertTrue(!(boolean) job.getClass().getMethod("doIt").invoke(job)
                && h.getLevel().isEmptyBlock(h.absolutePos(new BlockPos(3, 2, 3))) && ConsumptionTests.stock(p, 0) == 0,
                "Stale preview produced an unpaid block");
        ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 1); job = job(h, p);
        var pendant = TestPlayers.necklace(p).getStackInSlot(0); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(!(boolean) job.getClass().getMethod("doIt").invoke(job)
                && h.getLevel().isEmptyBlock(h.absolutePos(new BlockPos(3, 2, 3))), "Unequipped cache paid for stale preview");
        TestPlayers.necklace(p).setStackInSlot(0, pendant);
        h.assertTrue(ConsumptionTests.stock(p, 0) == 1, "Unequip consumed cached material"); h.succeed();
    }
    private static void reservedAndVariants(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); var named = new ItemStack(Items.STONE);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Keep this component"));
        ConsumptionTests.seed(p, 0, named, 4);
        h.assertTrue(count(p, Items.STONE) == 0 && take(p, new ItemStack(Items.STONE)) == 1 && ConsumptionTests.stock(p, 0) == 4,
                "Wand's plain block predicate consumed a different component variant");
        ConsumptionTests.seed(p, 1, new ItemStack(Items.STONE), 5);
        var view = CacheActions.open(p);
        h.assertTrue(CacheActions.execute(p, new CacheActions.Intent(view.session(), view.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 1, 3, -1, "")) == CacheActions.Result.OK, "Cursor reservation fixture failed");
        h.assertTrue(count(p, Items.STONE) == 2 && take(p, new ItemStack(Items.STONE, 4)) == 2
                && ConsumptionTests.stock(p, 1) == 3 && ConsumptionTests.stock(p, 0) == 4,
                "Native wand spent reserved or wrong-variant stock");
        p.closeContainer(); h.succeed();
    }
    private static void nativeContainerAndSpare(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 5);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 2))));
        p.getInventory().setItem(9, box); p.getInventory().setItem(10, new ItemStack(Items.STONE));
        p.getInventory().setItem(11, TestPlayers.necklace(p).getStackInSlot(0).copy());
        h.assertTrue(count(p, Items.STONE) == 8, "Spare pendant double-counted cache or native shulker source vanished");
        h.assertTrue(take(p, new ItemStack(Items.STONE, 4)) == 0 && ConsumptionTests.stock(p, 0) == 4
                && count(p, Items.STONE) == 4 && p.getInventory().getItem(9) == box && p.getInventory().getItem(10).isEmpty(),
                "Native container priority or exact remaining debit was replaced");
        h.succeed();
    }
    private static void configDisabled(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 5);
        boolean previous = FmpConfig.WAND.get();
        try {
            FmpConfig.WAND.set(false); FmpConfig.refreshServer();
            p.getInventory().setItem(9, new ItemStack(Items.STONE, 2));
            h.assertTrue(CompatibilityStatus.wand() == CompatibilityStatus.State.CONFIG_DISABLED && count(p, Items.STONE) == 2
                    && take(p, new ItemStack(Items.STONE, 3)) == 1 && ConsumptionTests.stock(p, 0) == 5,
                    "Disabled adapter spent cache or disabled native materials");
        } finally { FmpConfig.WAND.set(previous); FmpConfig.refreshServer(); }
        h.assertTrue(count(p, Items.STONE) == 5, "Reenabled source failed"); h.succeed();
    }
    private static void nativeUseAndCreative(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 3); job(h, p);
        var support = h.absolutePos(new BlockPos(3, 1, 3)); var target = support.above();
        var context = new net.minecraft.world.item.context.UseOnContext(p, net.minecraft.world.InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false));
        h.assertTrue(p.getMainHandItem().getItem().useOn(context).consumesAction() && h.getLevel().getBlockState(target).is(Blocks.STONE)
                && ConsumptionTests.stock(p, 0) == 2 && p.getMainHandItem().getDamageValue() == 1, "Actual wand use bypassed material or durability rules");
        h.getLevel().removeBlock(target, false); p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        h.assertTrue(p.getMainHandItem().getItem().useOn(context).consumesAction() && h.getLevel().getBlockState(target).is(Blocks.STONE)
                && ConsumptionTests.stock(p, 0) == 2 && p.getMainHandItem().getDamageValue() == 1, "Creative build spent cache or wand durability");
        h.succeed();
    }
}
