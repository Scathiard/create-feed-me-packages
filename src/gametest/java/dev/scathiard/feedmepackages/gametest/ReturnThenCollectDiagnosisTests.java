package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.domain.CollectPlan;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

/**
 * Diagnosis for the user's report: "我放满一个格子后设置退货，多余物品被退货后，转移仍认为那格是满的".
 *
 * <p>This walks the real sequence and prints/asserts the numbers, so the answer is data rather than opinion:
 * filter a cell, fill it above its threshold, set {@code maximum = N}, let the return run, then look at the
 * ledger amount, the cell's {@code roomLeft} and what one-key collect actually does.
 *
 * <p>It changes <b>no behaviour</b>: the captain asked for numbers first ("先只诊断＋给数字，不改行为").
 */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReturnThenCollectDiagnosisTests {
    /** Only the real transports can actually dispatch a return, so that track exists only with the mod loaded. */
    @GameTestGenerator
    public static Collection<TestFunction> tracks() {
        var tests = new ArrayList<TestFunction>();
        tests.add(new TestFunction("diagnosis", "fmp_return_then_collect_numbers", FeedMePackages.MOD_ID + ":empty",
                1200, 0, true, ReturnThenCollectDiagnosisTests::numbersWithoutTransport));
        tests.add(new TestFunction("diagnosis", "fmp_both_policies_net_change", FeedMePackages.MOD_ID + ":empty",
                1200, 0, true, ReturnThenCollectDiagnosisTests::netChangeUnderTheNewPolicy));
        if (ModList.get().isLoaded("create_mobile_packages")) {
            tests.add(new TestFunction("diagnosis", "fmp_return_then_collect_real_dispatch", FeedMePackages.MOD_ID + ":empty",
                    2400, 0, true, ReturnThenCollectDiagnosisTests::numbersWithRealReturn));
            tests.add(new TestFunction("diagnosis", "fmp_user_policy_then_return_ships_it_away", FeedMePackages.MOD_ID + ":empty",
                    2400, 0, true, ReturnThenCollectDiagnosisTests::userPolicyThenReturnShipsItAway));
        }
        return tests;
    }

    private static ItemVariantKey stone(GameTestHelper helper, net.minecraft.server.level.ServerPlayer player) {
        return ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
    }

    /** The three numbers the captain asked for, taken from the shipped arithmetic (not re-implemented). */
    private static String numbers(CacheLedger ledger, UUID cacheId, int slot, ItemVariantKey key,
            net.minecraft.server.level.ServerPlayer player) {
        var cell = ledger.find(cacheId).state().cells().get(slot);
        int groupCapacity = CacheLevel.of(ledger.find(cacheId).state().level()).groupCapacity();
        int threshold = cell.maximum() < 0 ? -1 : cell.maximum() * key.stackSize();
        int room = CollectPlan.roomLeft(
                new CollectPlan.Target(slot, key, cell.amount(), cell.maximum()), groupCapacity);
        return "amount=" + cell.amount() + " maximum=" + cell.maximum() + " groups"
                + " (threshold=" + threshold + " items) capacity=" + (groupCapacity * key.stackSize())
                + " roomLeft=" + room;
    }

    /** The plan the server itself would build: the player's real inventory against the live cells. */
    private static CollectPlan.Plan plan(net.minecraft.server.level.ServerPlayer player, UUID cacheId) {
        var inventory = player.getInventory();
        var sources = new ArrayList<CollectPlan.Source>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemVariantKey variant = null;
            if (!stack.isEmpty()) {
                try { variant = ItemVariantKey.of(stack, player.registryAccess()); }
                catch (IllegalArgumentException unreadable) { variant = null; }
            }
            sources.add(new CollectPlan.Source(slot, variant, stack.isEmpty() ? 0 : stack.getCount()));
        }
        var state = CacheLedger.get(player.getServer()).find(cacheId).state();
        var targets = new ArrayList<CollectPlan.Target>();
        for (int slot = 0; slot < state.cells().size(); slot++) {
            var cell = state.cells().get(slot);
            if (cell.filter() != null)
                targets.add(new CollectPlan.Target(slot, cell.filter(), cell.amount(), cell.maximum()));
        }
        return CollectPlan.simulate(targets, sources, CacheLevel.of(state.level()).groupCapacity());
    }

    private static String counts(CollectPlan.Plan plan) {
        return "moved=" + plan.moved() + " noCell=" + plan.noCell() + " full=" + plan.full();
    }

    private static CacheActions.Result collect(net.minecraft.server.level.ServerPlayer player) {
        var view = CacheActions.open(player);
        return CacheActions.execute(player, new CacheActions.Intent(view.session(),
                view.record() == null ? -1 : view.record().state().revision(), CacheActions.Action.COLLECT_MATCHING,
                -1, 0, -1, ""));
    }

    private static void report(String phase, String numbers, CacheActions.Result result) {
        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS {} {} collect={}", phase, numbers, result);
    }

    /** No transport in this run: the return cannot dispatch, so the cell simply keeps its overage. */
    @GameTest(template = "empty")
    public static void numbersWithoutTransport(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player);
        var ledger = CacheLedger.get(player.getServer());
        var cacheId = access.handle().cacheId();
        var key = stone(helper, player);
        var before = ledger.find(cacheId);
        var edit = before.state().edit();
        // Level 1: capacity 2 groups = 128 items for a 64-stack item. Fill it, then set maximum = 1 group.
        edit.filter(0, key);
        edit.insert(0, key, 100);
        edit.thresholds(0, 0, 1);
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == 100, "fixture did not fill the cell");
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).maximum() == 1, "fixture did not set the threshold");

        // The user's set-up: a return address exists, but no carrier is available in a core run.
        ledger.setReturnAddress(cacheId, "FMP@origin");
        ReturnService.check(player);
        String afterReturn = numbers(ledger, cacheId, 0, key, player);
        report("no-carrier-return-attempted", afterReturn, null);
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == 100,
                "a carrier-less return must not deduct anything: " + afterReturn);

        // One-key collect on that cell, carrying a stack the cell filters: the numbers under the new policy.
        var carried = new ItemStack(Items.STONE, 64);
        player.getInventory().setItem(0, carried);
        CollectPlan.Plan plan = plan(player, cacheId);
        report("plan-after-return", afterReturn, null);
        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS counts-after-return {}", counts(plan));
        helper.assertTrue(plan.moved() == 28 && plan.full() == 1 && plan.noCell() == 0,
                "the collect must fill the cell to capacity (128 - 100 = 28), got " + counts(plan));
        helper.assertTrue(plan.aboveMaximum() == 28,
                "the 28 moved items all land above the return line (the cell was already 36 above it), got "
                        + plan.aboveMaximum());
        CacheActions.resetCollectReports();
        var result = collect(player);
        report("collect-after-return", afterReturn, result);
        helper.assertTrue(result == CacheActions.Result.OK, "collect was refused: " + result);
        helper.assertTrue(CacheActions.collectReports() == 1, "a moving collect is reported once");
        helper.assertTrue(CacheActions.lastCollectReported() == 28,
                "the message must carry the real intake, said " + CacheActions.lastCollectReported());
        helper.assertTrue(!CacheActions.lastCollectMentionedReturn(),
                "an unbound fixture has no return configured, so the message must not promise to send anything");
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == 128,
                "the cell should have filled to capacity");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 36, "the rest stays in the bag (64 - 28)");

        // And the same with the overage already trimmed down to the threshold, which is what a successful return
        // converges to (the exact debit ReturnService.dispatch applies: edit.extract(overage) + one replace).
        var live = ledger.find(cacheId);
        var debit = live.state().edit();
        // Bring it down to the return line (64) whatever the collect above left there: this is the state a
        // successful return converges to.
        debit.extract(0, Math.max(0, live.state().cells().get(0).amount() - 64));
        ledger.replace(access.handle(), live.state().revision(), live.withState(debit.finish()));
        String converged = numbers(ledger, cacheId, 0, key, player);
        report("return-converged", converged, null);
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == 64, "fixture did not converge");
        helper.assertTrue(CollectPlan.roomLeft(new CollectPlan.Target(0, key, 64, 1), CacheLevel.of(1).groupCapacity()) == 64,
                "a cell at its return line still has its capacity free: " + converged);

        player.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
        CacheActions.resetCollectReports();
        helper.assertTrue(collect(player) == CacheActions.Result.OK, "collect was refused");
        report("collect-after-converged-return", converged, CacheActions.Result.OK);
        helper.assertTrue(CacheActions.collectReports() == 1, "a moving collect is reported once");
        helper.assertTrue(CacheActions.lastCollectReported() == 64, "the message carries the real intake");
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == 128, "the cell filled to capacity");
        helper.succeed();
    }

    /** With a real transport: the return actually dispatches and the ledger really drops - then collect again. */
    public static void numbersWithRealReturn(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2)));
        helper.getLevel().addNewPlayer(f.player());
        var portPos = new net.minecraft.core.BlockPos(1, 1, 1);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
        helper.setBlock(portPos, block.defaultBlockState());
        block.setPlacedBy(helper.getLevel(), helper.absolutePos(portPos), helper.getBlockState(portPos), f.player(),
                new ItemStack(block));
        try {
            var port = helper.getBlockEntity(portPos);
            var network = (UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
            TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
            var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            join.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                    join.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            f.ledger().setReturnAddress(f.handle().cacheId(), SupplyService.address(f.player()));
            var before = f.record();
            var edit = before.state().edit();
            for (int level = 1; level < 5; level++) edit.upgrade();
            edit.thresholds(0, 0, 1);      // maximum = 1 group = 64 items
            edit.insert(0, f.key(), 1000); // far above the threshold, like the user's full cell
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
            f.player().getInventory().setItem(0, new ItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"))));

            helper.startSequence()
                    .thenExecute(() -> {
                        report("before-real-return", numbers(f.ledger(), f.handle().cacheId(), 0, f.key(), f.player()), null);
                        ReturnService.check(f.player());
                    })
                    .thenWaitUntil(() -> {
                        String now = numbers(f.ledger(), f.handle().cacheId(), 0, f.key(), f.player());
                        helper.assertTrue(f.record().state().cells().getFirst().amount() == 424,
                                "real return did not dispatch one package: " + now);
                        report("after-real-return", now, null);
                    })
                    .thenExecute(() -> {
                        // The ledger really dropped (1000 -> 424), and the cell still counts as full for collect.
                        CacheActions.resetCollectReports();
                        var result = collect(f.player());
                        report("collect-after-real-return",
                                numbers(f.ledger(), f.handle().cacheId(), 0, f.key(), f.player()), result);
                        helper.assertTrue(result == CacheActions.Result.OK, "collect was refused");
                        helper.assertTrue(CacheActions.collectReports() == 0,
                                "a full cell must stay silent, reports=" + CacheActions.collectReports());
                        helper.assertTrue(f.record().state().cells().getFirst().amount() == 424,
                                "collect moved items above the user's threshold");
                    })
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Return/collect diagnosis fixture failed", failure);
        }
    }

    /**
     * The shipped rule now: room is only the cell's remaining CAPACITY, and the part above the cell's own return
     * line is announced to the player and left to the return path. These are the numbers for the one click.
     */
    @GameTest(template = "empty")
    public static void netChangeUnderTheNewPolicy(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player);
        var ledger = CacheLedger.get(player.getServer());
        var cacheId = access.handle().cacheId();
        var key = stone(helper, player);
        var before = ledger.find(cacheId);
        var edit = before.state().edit();
        edit.filter(0, key);
        edit.insert(0, key, 64);      // exactly the return line: what a converged return leaves behind
        edit.thresholds(0, 0, 1);     // maximum = 1 group = 64 items
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
        int groupCapacity = CacheLevel.of(1).groupCapacity();

        int room = CollectPlan.roomLeft(new CollectPlan.Target(0, key, 64, 1), groupCapacity);
        CollectPlan.Plan plan = plan(player, cacheId);
        CacheActions.resetCollectReports();
        var result = collect(player);
        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS policy=new capacity={} maximum=1(64) amount=64"
                        + " roomLeft={} netCache=+{} netInventory=-{} aboveMaximum={} messageCarried={} result={} counts=[{}]",
                groupCapacity * 64, room, plan.moved(), plan.moved(), plan.aboveMaximum(),
                CacheActions.lastCollectReported(), result, counts(plan));
        helper.assertTrue(room == 64, "the new rule sees the free capacity, got " + room);
        helper.assertTrue(plan.moved() == 64 && plan.full() == 0 && plan.noCell() == 0,
                "the new rule fills the cell to capacity, got " + counts(plan));
        helper.assertTrue(plan.aboveMaximum() == 64,
                "everything above the return line must be announced, got " + plan.aboveMaximum());
        helper.assertTrue(CacheActions.collectReports() == 1 && CacheActions.lastCollectReported() == plan.moved(),
                "the single message must carry exactly what moved");
        helper.assertTrue(ledger.find(cacheId).state().cells().get(0).amount() == groupCapacity * 64,
                "the cell ends up at capacity");
        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS policy=new after-click cell={} (== capacity);"
                        + " the return line is still 64, so the next return pass has overage {}",
                groupCapacity * 64, (64 + plan.moved()) - 64);
        helper.succeed();
    }

    /**
     * The user's policy all the way through: fill the cell to capacity (what that click would produce), then let
     * the return run for real. This is the "点了等于没点" check - where the surplus actually goes.
     */
    public static void userPolicyThenReturnShipsItAway(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2)));
        helper.getLevel().addNewPlayer(f.player());
        var portPos = new net.minecraft.core.BlockPos(1, 1, 1);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
        helper.setBlock(portPos, block.defaultBlockState());
        block.setPlacedBy(helper.getLevel(), helper.absolutePos(portPos), helper.getBlockState(portPos), f.player(),
                new ItemStack(block));
        try {
            var port = helper.getBlockEntity(portPos);
            var network = (UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
            TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
            var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            join.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                    join.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            var address = SupplyService.address(f.player());
            f.ledger().setReturnAddress(f.handle().cacheId(), address);
            var before = f.record();
            var edit = before.state().edit();
            for (int level = 1; level < 5; level++) edit.upgrade();
            edit.thresholds(0, 0, 1);          // maximum = 1 group = 64 items
            edit.insert(0, f.key(), 128);      // what the user's policy would have collected into it
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
            f.player().getInventory().setItem(0, new ItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"))));

            helper.startSequence()
                    .thenExecute(() -> {
                        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS policy=user after-click cell=128"
                                + " (capacity, above the return line 64); overage to the return = 64"
                                + " -> the player already saw [已收 64 件]");
                        ReturnService.check(f.player());
                    })
                    .thenWaitUntil(() -> {
                        var cell = f.record().state().cells().getFirst();
                        FeedMePackages.LOGGER.info("FMP_RETURN_COLLECT_DIAGNOSIS policy=user after-next-return"
                                        + " cell={} netCacheChange={} goodsDispatchedTo=[{}]",
                                cell.amount(), 64 - cell.amount(), address);
                        helper.assertTrue(cell.amount() == 64,
                                "the return should have shipped the just-collected 64 away, cell=" + cell.amount());
                        helper.assertTrue(f.player().getInventory().getItem(0).getCount() == 0,
                                "the carrier should have been consumed");
                    })
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("User-policy return fixture failed", failure);
        }
    }

}
