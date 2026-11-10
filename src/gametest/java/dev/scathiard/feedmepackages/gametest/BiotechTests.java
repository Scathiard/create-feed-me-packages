package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.biotech.*;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.lang.reflect.*;
import java.util.*;

/** Native ports, tasks and ticks, with all quantities checked at the real receiving endpoints. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class BiotechTests {
    private static final String ROOT = "com.nobodiiiii.createbiotech.content.allay.";
    @FunctionalInterface interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("create_biotech")) return List.of();
        var version = ModList.get().getModContainerById("create_biotech").orElseThrow().getModInfo().getVersion().toString();
        if (!version.equals("1.3.1")) {
            FeedMePackages.LOGGER.info("FMP_BIOTECH_TESTS adapter cases not applicable to {}; native player cycles are in NativeDeliveryTests", version);
            return List.of();
        }
        var tests = new ArrayList<TestFunction>();
        tests.add(test("player_full_inventory", BiotechTests::playerDelivery));
        tests.add(test("player_partial_receipt", h -> playerDelivery(h, true)));
        tests.add(test("player_return", h -> activeReturn(h, false)));
        tests.add(test("admitted_then_error", h -> activeReturn(h, false, false, true)));
        tests.add(test("return_rejection", BiotechTests::rejection));
        if (ModList.get().isLoaded("fluidlogistics")) tests.add(test("player_fluid", h -> fluidDelivery(h, false)));
        if (ModList.get().isLoaded("touhou_little_maid")) {
            tests.add(test("maid_moving_delivery", h -> maidDelivery(h, false)));
            tests.add(test("maid_saved_task_delivery", h -> maidDelivery(h, true)));
            tests.add(test("maid_return", h -> activeReturn(h, true)));
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                tests.add(test("maid_return_" + hand.name().toLowerCase(), h -> activeReturn(h, true, false, false, hand)));
                tests.add(test("maid_rejected_" + hand.name().toLowerCase(), h -> handRejection(h, hand)));
            }
            tests.add(test("maid_return_saved_owner", h -> activeReturn(h, true, true, false)));
            tests.add(test("maid_authentication", BiotechTests::authentication));
            if (ModList.get().isLoaded("fluidlogistics")) tests.add(test("maid_fluid", h -> fluidDelivery(h, true)));
        }
        FeedMePackages.LOGGER.info("FMP_BIOTECH_TESTS SELECTED count={}", tests.size());
        return tests;
    }
    private static TestFunction test(String name, Body body) {
        // Native recovery can take 600 ticks on EACH leg, plus port approach. Fluid cases then send a third leg.
        return new TestFunction("biotech", "fmp_biotech_" + name, FeedMePackages.MOD_ID + ":empty", name.contains("fluid") ? 2200 : 1500, 0, true, h -> {
            h.assertTrue(BiotechCompatibility.ready() && !BiotechCompatibility.carrier().isEmpty(), "Biotech native ports/carrier not ready");
            try { body.run(h); } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Biotech fixture failed", failed); }
        });
    }
    private static Object call(Object target, String name, Object... args) {
        try {
            Class<?> type = target instanceof Class<?> c ? c : target.getClass();
            var method = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
            return method.invoke(target instanceof Class<?> ? null : target, args);
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(name, failed); }
    }
    private static PackagePortBlockEntity port(GameTestHelper h, String address) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_biotech:allay_port"));
        h.assertTrue(block != net.minecraft.world.level.block.Blocks.AIR, "Allay port registry missing");
        // The north-facing port approaches 3.25 blocks outwards. Keep that path inside
        // this 5x5 test's ticking area; at z=1 it can strand the entity in an unticked chunk.
        var pos = new BlockPos(2, 1, 4); h.setBlock(pos, block);
        var port = (PackagePortBlockEntity)h.getBlockEntity(pos);
        port.addressFilter = address; port.acceptsPackages = true;
        try { call(Class.forName(ROOT + "block.allayport.AllayPortTargetRegistry"), "update", h.getLevel(), h.absolutePos(pos), address); }
        catch (ClassNotFoundException failed) { throw new IllegalStateException(failed); }
        return port;
    }
    private static ItemStackHandler carriers(Object port) { return (ItemStackHandler)call(port, "getCarrierInventory"); }
    private static void launch(GameTestHelper h, PackagePortBlockEntity port, ItemStack box) {
        port.inventory.setStackInSlot(0, box); carriers(port).setStackInSlot(0, BiotechCompatibility.carrier());
        h.assertTrue((boolean)call(port, "tryDispatch"), "Native allay port refused addressed package");
        h.assertTrue(port.inventory.getStackInSlot(0).isEmpty() && carriers(port).getStackInSlot(0).isEmpty(), "Native launch did not consume exactly one box/carrier");
    }
    private static Map<UUID, ServerPlayer> playerMap(ServerPlayer player) throws ReflectiveOperationException {
        var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID"); field.setAccessible(true);
        return (Map<UUID, ServerPlayer>)field.get(player.getServer().getPlayerList());
    }
    private static void playerDelivery(GameTestHelper h) throws ReflectiveOperationException {
        playerDelivery(h, false);
    }
    private static void playerDelivery(GameTestHelper h, boolean partial) throws ReflectiveOperationException {
        var f = ReceiveTests.setup(h, partial ? ReceiveTests.FULL - 3 : 0); var player = f.player();
        player.setPos(h.absoluteVec(new Vec3(4, 2, 4))); TestPlayers.nativePackets(player);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(player.connection.getConnection());
        TestPlayers.nativeSavePlayers(player.getServer()).add(player); var map = playerMap(player); map.put(player.getUUID(), player);
        var port = port(h, "home-" + player.getUUID().toString().substring(0, 8));
        launch(h, port, f.box(8));
        observeNativeDeadline(h, port);
        h.succeedWhen(() -> {
            h.assertTrue(f.record().state().cells().getFirst().amount() == (partial ? ReceiveTests.FULL : 8), "Native flight has not filled the full-inventory player's cache");
            if (partial) h.assertTrue(PackageItem.getContents(f.residual()).getStackInSlot(0).getCount() == 5, "Partially accepted envelope lost its five remaining items");
            h.assertTrue(carriers(port).getStackInSlot(0).getCount() == 1, "Courier did not return to original port exactly once: " + flightState(port));
            h.assertTrue(player.getInventory().items.stream().allMatch(s -> s.is(Items.DIRT) && s.getCount() == 64), "Full-inventory receipt displaced ordinary items");
            map.remove(player.getUUID()); TestPlayers.nativeSavePlayers(player.getServer()).remove(player); channel.finishAndReleaseAll();
        });
    }
    private static ItemStack maidBox(MaidTests.Fixture f, int amount) {
        var request = UUID.randomUUID(); var before = f.record(); var edit = before.state().edit();
        edit.settings(0, new CellSettings(64, -1, 1, -1, 1));
        edit.request(0, request, f.maid().getUUID(), amount, false);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, amount))); var address = MaidAccess.address(f.maid());
        PackageItem.addAddress(box, address);
        ParcelAuthentication.apply(f.ledger(), f.maid().registryAccess(), box, new ParcelSeal(UUID.randomUUID(), f.handle().cacheId(), f.maid().getUUID(),
                request, f.key().encoded(), f.record().state().cells().getFirst().filterRevision(), address, false, ""));
        return box;
    }
    private static List<Object> tasks() throws ReflectiveOperationException {
        var manager = Class.forName(ROOT + "logistics.courier.AllayCourierTaskManager");
        var field = manager.getDeclaredField("savedData"); field.setAccessible(true);
        return (List<Object>)call(field.get(null), "getTasks");
    }
    private static void maidDelivery(GameTestHelper h, boolean reload) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.STONE), 0);
        var port = port(h, "home-" + f.maid().getUUID().toString().substring(0, 8)); var box = maidBox(f, 8);
        launch(h, port, box);
        observeNativeDeadline(h, port);
        if (reload) {
            var task = tasks().stream().filter(t -> t instanceof BiotechTaskLink link && f.maid().getUUID().equals(link.fmp$target())).findFirst().orElseThrow();
            var tag = (CompoundTag)call(task, "save", f.maid().registryAccess(), new CompoundTag());
            var restored = call(task.getClass(), "load", f.maid().registryAccess(), tag);
            h.assertTrue(restored instanceof BiotechTaskLink link && f.maid().getUUID().equals(link.fmp$target())
                    && f.owner().getUUID().equals(link.fmp$owner()), "Native save/load lost maid identity");
            // Replace exactly this registered task with its native deserialized copy, retaining its ID and cargo.
            var list = tasks(); int index = list.indexOf(task); list.set(index, restored);
        }
        h.runAfterDelay(8, () -> f.maid().setPos(f.maid().position().add(3, 0, 1)));
        h.succeedWhen(() -> {
            h.assertTrue(f.stock() == 8, "Native flight has not delivered to moving maid");
            h.assertTrue(carriers(port).getStackInSlot(0).getCount() == 1, "Maid delivery did not return courier to original port: " + flightState(port));
            h.assertTrue(MaidCompatibility.inventory(f.maid()).getSlots() > 0, "Maid native inventory unavailable");
        });
    }
    private static void activeReturn(GameTestHelper h, boolean maid) {
        activeReturn(h, maid, false, false);
    }
    private static int admissionFaults;
    public static boolean admittedThenError(net.minecraft.server.MinecraftServer server, Object task) throws ClassNotFoundException {
        boolean admitted = (boolean)call(Class.forName(ROOT + "logistics.courier.AllayCourierTaskManager"), "addTask", server, task);
        if (admitted) { admissionFaults++; throw new IllegalStateException("FMP test: native task admitted before later notification failure"); }
        return false;
    }
    private static void activeReturn(GameTestHelper h, boolean maid, boolean returning, boolean fault) {
        activeReturn(h, maid, returning, fault, null);
    }
    private static void activeReturn(GameTestHelper h, boolean maid, boolean returning, boolean fault, net.minecraft.world.InteractionHand hand) {
        LivingEntity bearer; CacheHandle handle; CacheLedger ledger; ItemStack pendant;
        if (maid) {
            var f = MaidTests.setup(h, new ItemStack(Items.STONE), 20);
            bearer = f.maid(); handle = f.handle(); ledger = f.ledger(); pendant = f.pendant();
            if (hand == null) h.assertTrue(MaidCompatibility.insertInto(bearer, BiotechCompatibility.carrier()), "Fixture carrier missing");
            else {
                var inv = MaidCompatibility.inventory(bearer);
                for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
                bearer.setItemInHand(hand, BiotechCompatibility.carrier());
                bearer.setItemInHand(hand == net.minecraft.world.InteractionHand.MAIN_HAND ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            }
        } else {
            var player = TestPlayers.create(h, FmpRegistries.PENDANT.toStack()); player.setPos(h.absoluteVec(new Vec3(4, 2, 4)));
            ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE), 20); bearer = player;
            handle = AccessGate.resolve(player).handle(); ledger = CacheLedger.get(player.getServer()); pendant = TestPlayers.necklace(player).getStackInSlot(0);
            player.getInventory().setItem(0, BiotechCompatibility.carrier());
        }
        pendant.set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        String address = "return-" + bearer.getUUID().toString().substring(0, 8); var port = port(h, address);
        var old = ledger.find(handle.cacheId()); var edit = old.state().edit(); edit.settings(0, new dev.scathiard.feedmepackages.domain.CellSettings(-1, 8, 1, -1, 1));
        ledger.replace(handle, old.state().revision(), old.withState(edit.finish())); ledger.setReturnAddress(handle.cacheId(), address);
        Method original = null; Map<String, Method> ports = null; int faultsBefore = admissionFaults;
        try {
            if (fault) {
                var field = BiotechCompatibility.class.getDeclaredField("METHODS"); field.setAccessible(true);
                ports = (Map<String, Method>)field.get(null);
                original = ports.put("add", BiotechTests.class.getMethod("admittedThenError", net.minecraft.server.MinecraftServer.class, Object.class));
            }
            ReturnService.check(maid ? MaidAccess.endpoint(bearer) : LogisticsEndpoint.player((ServerPlayer)bearer));
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
        finally { if (original != null) ports.put("add", original); }
        if (fault) h.assertTrue(admissionFaults == faultsBefore + 1, "After-admission failure injection was not reached exactly once");
        h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 8 && ledger.returnEscrow(handle.cacheId()) == null,
                "Active allay return did not debit surplus exactly once");
        if (hand != null) h.assertTrue(bearer.getItemInHand(hand).isEmpty()
                && bearer.getItemInHand(hand == net.minecraft.world.InteractionHand.MAIN_HAND ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND).is(Items.IRON_SWORD),
                "Hand carrier was not debited exactly once or the other held item moved");
        if (returning) try {
            var task = tasks().stream().filter(t -> t instanceof BiotechTaskLink link && bearer.getUUID().equals(link.fmp$source())).findFirst().orElseThrow();
            var mode = task.getClass().getDeclaredField("returnMode"); mode.setAccessible(true);
            mode.set(task, Arrays.stream(mode.getType().getEnumConstants()).filter(v -> v.toString().equals("ALWAYS_RETURN")).findFirst().orElseThrow());
            var tag = (CompoundTag)call(task, "save", bearer.registryAccess(), new CompoundTag());
            var restored = call(task.getClass(), "load", bearer.registryAccess(), tag);
            h.assertTrue(restored instanceof BiotechTaskLink link && bearer.getUUID().equals(link.fmp$source())
                    && ((net.minecraft.world.entity.TamableAnimal)bearer).getOwnerUUID().equals(link.fmp$owner()), "Native saved return lost source maid/owner");
            var list = tasks(); list.set(list.indexOf(task), restored);
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
        h.succeedWhen(() -> {
            var delivered = port.inventory.getStackInSlot(0);
            h.assertTrue(PackageItem.isPackage(delivered), "Real destination port has not received return");
            int count = PackageItem.getContents(delivered).getStackInSlot(0).getCount();
            h.assertTrue(count == 12 && ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 8, "Return cargo is not conserved");
            if (returning) {
                var inv = MaidCompatibility.inventory(bearer); int countCarrier = 0;
                for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(BiotechCompatibility.carrier().getItem())) countCarrier += inv.getStackInSlot(i).getCount();
                h.assertTrue(countCarrier == 1 && carriers(port).getStackInSlot(0).isEmpty(), "Native return policy did not return exactly one courier to source maid");
            } else h.assertTrue(carriers(port).getStackInSlot(0).getCount() == 1, "Native player-launch dock policy lost or duplicated courier");
        });
    }
    private static int refusalCalls;
    public static boolean rejectAdmission(net.minecraft.server.MinecraftServer server, Object task) { refusalCalls++; return false; }
    private static void handRejection(GameTestHelper h, net.minecraft.world.InteractionHand hand) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.STONE), 20);
        f.pendant().set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        var old = f.record(); var edit = old.state().edit(); edit.settings(0, new CellSettings(-1, 8, 1, -1, 1));
        f.ledger().replace(f.handle(), old.state().revision(), old.withState(edit.finish()));
        String address = "hand-refusal-" + f.maid().getUUID(); port(h, address); f.ledger().setReturnAddress(f.handle().cacheId(), address);
        var inv = MaidCompatibility.inventory(f.maid());
        for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
        var other = hand == net.minecraft.world.InteractionHand.MAIN_HAND ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        var sword = new ItemStack(Items.IRON_SWORD); f.maid().setItemInHand(other, sword);
        f.maid().setItemInHand(hand, BiotechCompatibility.carrier());
        f.maid().startUsingItem(hand);
        h.assertTrue(f.maid().isUsingItem(), "Native hand-use guard fixture was not active");
        ReturnService.check(MaidAccess.endpoint(f.maid()));
        h.assertTrue(f.stock() == 20 && f.maid().getItemInHand(hand).getCount() == 1 && f.ledger().returnEscrow(f.handle().cacheId()) == null,
                "Return took a carrier currently being used");
        f.maid().stopUsingItem();
        var field = BiotechCompatibility.class.getDeclaredField("METHODS"); field.setAccessible(true);
        var ports = (Map<String, Method>)field.get(null);
        Method original = ports.put("add", BiotechTests.class.getMethod("rejectAdmission", net.minecraft.server.MinecraftServer.class, Object.class));
        int previous = refusalCalls;
        try {
            ReturnService.check(MaidAccess.endpoint(f.maid()));
            ReturnService.check(MaidAccess.endpoint(f.maid()));
        } finally { ports.put("add", original); }
        h.assertTrue(refusalCalls == previous + 2, "Hand dispatch never reached native admission refusal");
        h.assertTrue(f.stock() == 20 && f.ledger().returnEscrow(f.handle().cacheId()) == null
                && f.maid().getItemInHand(hand).getCount() == 1 && f.maid().getItemInHand(hand).is(BiotechCompatibility.carrier().getItem())
                && f.maid().getItemInHand(other) == sword, "Rejected hand return lost or duplicated carrier/cargo or changed the other hand");
        f.ledger().setReturnAddress(f.handle().cacheId(), ""); h.succeed();
    }
    private static void rejection(GameTestHelper h) {
        var player = TestPlayers.create(h, FmpRegistries.PENDANT.toStack());
        TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE), 20); player.getInventory().setItem(0, BiotechCompatibility.carrier());
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer());
        var old = ledger.find(handle.cacheId()); var edit = old.state().edit(); edit.settings(0, new dev.scathiard.feedmepackages.domain.CellSettings(-1, 8, 1, -1, 1));
        ledger.replace(handle, old.state().revision(), old.withState(edit.finish())); ledger.setReturnAddress(handle.cacheId(), "missing-" + UUID.randomUUID());
        ReturnService.check(player);
        h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 20 && player.getInventory().getItem(0).getCount() == 1
                && ledger.returnEscrow(handle.cacheId()) == null, "Unreachable return took stock or carrier");
        String address = "reject-" + UUID.randomUUID(); var target = port(h, address); ledger.setReturnAddress(handle.cacheId(), address);
        target.acceptsPackages = false; ReturnService.check(player);
        h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 20 && player.getInventory().getItem(0).getCount() == 1,
                "Closed receiving port consumed stock or courier");
        target.acceptsPackages = true;
        try {
            var loaded = (ItemStack)call(Class.forName(ROOT + "item.allaycourier.AllayCourierItem"), "createLoaded",
                    PackageItem.containing(List.of(new ItemStack(Items.DIAMOND))));
            player.getInventory().setItem(0, loaded); ReturnService.check(player);
            h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 20 && ItemStack.matches(loaded, player.getInventory().getItem(0)),
                    "An occupied courier was consumed as an empty carrier");
            player.getInventory().setItem(0, BiotechCompatibility.carrier());
            var nativeConfig = Class.forName("com.nobodiiiii.createbiotech.registry.CBConfigs").getField("SERVER").get(null);
            var cfg = nativeConfig.getClass().getField("allayCourier").get(nativeConfig);
            var limit = cfg.getClass().getField("maxActiveTasks").get(cfg); int oldLimit = (int)call(limit, "get");
            var quotaCargo = PackageItem.containing(List.of(new ItemStack(Items.STONE))); PackageItem.addAddress(quotaCargo, address);
            h.assertTrue(BiotechCompatibility.send(player, quotaCargo).outcome() == dev.scathiard.feedmepackages.compat.TransportDispatch.Outcome.SENT,
                    "Quota fixture flight was not admitted");
            try {
                // Temporary in-memory native config; restore before other callbacks or task ticking.
                call(limit, "set", 1);
                ReturnService.check(player);
                h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 20 && player.getInventory().getItem(0).getCount() == 1
                        && ledger.returnEscrow(handle.cacheId()) == null, "Native task quota refusal lost or duplicated the return reservation");
            } finally { call(limit, "set", oldLimit); }
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
        h.succeed();
    }

    private static void authentication(GameTestHelper h) {
        var f = MaidTests.setup(h, new ItemStack(Items.STONE), 0); var box = maidBox(f, 8);
        h.assertTrue(BiotechCompatibility.resolve(h.getLevel(), box, f.maid().position()) != null, "Authentic maid target unavailable");
        var forged = box.copy(); forged.remove(FmpRegistries.PARCEL_SEAL.get());
        h.assertTrue(BiotechCompatibility.resolve(h.getLevel(), forged, f.maid().position()) == null, "Unauthenticated parcel acquired maid target");
        var wrong = box.copy(); PackageItem.addAddress(wrong, "FMP-Maid/" + UUID.randomUUID());
        h.assertTrue(BiotechCompatibility.resolve(h.getLevel(), wrong, f.maid().position()) == null, "Wrong address acquired maid target");
        var inv = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(f.maid()).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
        inv.setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(BiotechCompatibility.resolve(h.getLevel(), box, f.maid().position()) == null && f.stock() == 0,
                "Unworn maid acquired a target or authentication probe moved stock"); h.succeed();
    }
    private static void fluidDelivery(GameTestHelper h, boolean maid) throws ReflectiveOperationException {
        h.assertTrue(FluidPackages.status() == FluidPackages.Status.READY, "Fluid package API unavailable");
        LivingEntity bearer; CacheHandle handle; CacheLedger ledger; Runnable cleanup;
        if (maid) {
            var f = MaidTests.setup(h, new ItemStack(Items.STONE), 0); bearer = f.maid(); handle = f.handle(); ledger = f.ledger(); cleanup = () -> {};
            var before = f.record(); var edit = before.state().edit(); edit.filter(0, null);
            ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        } else {
            var f = ReceiveTests.setup(h, 0); bearer = f.player(); handle = f.handle(); ledger = f.ledger();
            var before = f.record(); var edit = before.state().edit(); edit.reset(0); edit.filter(0, null);
            ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
            PhantomCompatibilityTests.register(h, f.player()); cleanup = () -> PhantomCompatibilityTests.unregister(h, f.player());
        }
        var sample = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        var key = FluidVariantKey.of(sample, bearer.registryAccess()); var request = UUID.randomUUID();
        var before = ledger.find(handle.cacheId()); var edit = before.fluids().state().edit();
        edit.filter(0, key); edit.settings(0, new CellSettings(1000, -1, 1000, -1, 1000)); edit.request(0, request, bearer.getUUID(), 1000, false);
        ledger.replace(handle, before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        String address = maid ? MaidAccess.address(bearer) : SupplyService.address((ServerPlayer)bearer);
        var box = FluidPackages.create(sample); PackageItem.addAddress(box, address);
        ParcelAuthentication.applyFluid(ledger, bearer.registryAccess(), box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), bearer.getUUID(), request,
                key.encoded(), ledger.find(handle.cacheId()).fluids().state().cells().getFirst().filterRevision(), address, false, ""));
        String returnAddress = "fluid-" + UUID.randomUUID();
        var port = port(h, returnAddress); launch(h, port, box); observeNativeDeadline(h, port); int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) {
                h.assertTrue(ledger.find(handle.cacheId()).fluids().state().cells().getFirst().amount() == 1000
                        && ledger.find(handle.cacheId()).fluids().state().pending(0) == 0 && carriers(port).getStackInSlot(0).getCount() == 1,
                        "Fluid flight did not conserve liquid, settle promise and return carrier: " + flightState(port));
                var current = ledger.find(handle.cacheId()); var change = current.fluids().state().edit();
                change.settings(0, new CellSettings(-1, 800, 1, -1, 200));
                ledger.replace(handle, current.state().revision(), current.withFluids(current.fluids().withState(change.finish())));
                var pendant = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(bearer).orElseThrow()
                        .getStacksHandler("necklace").orElseThrow().getStacks().getStackInSlot(0);
                pendant.set(FmpRegistries.NETWORK.get(), UUID.randomUUID()); ledger.setReturnAddress(handle.cacheId(), returnAddress);
                if (maid) h.assertTrue(MaidCompatibility.insertInto(bearer, BiotechCompatibility.carrier()), "Fluid return carrier did not fit");
                else ((ServerPlayer)bearer).getInventory().setItem(0, BiotechCompatibility.carrier());
                ReturnService.check(maid ? MaidAccess.endpoint(bearer) : LogisticsEndpoint.player((ServerPlayer)bearer));
                h.assertTrue(ledger.find(handle.cacheId()).fluids().state().cells().getFirst().amount() == 800, "Fluid return did not debit its exact batch"); phase[0] = 1;
            }
            h.assertTrue(FluidPackages.read(port.inventory.getStackInSlot(0)).getAmount() == 200 && carriers(port).getStackInSlot(0).getCount() == 2,
                    "Native return failed to conserve fluid and both receipt/return couriers: returned="
                            + FluidPackages.read(port.inventory.getStackInSlot(0)).getAmount() + " " + flightState(port)); cleanup.run();
        });
    }
    private static String flightState(PackagePortBlockEntity port) {
        var detail = new ArrayList<String>();
        try {
            for (var task : tasks()) {
                var src = task.getClass().getDeclaredField("sourceAllayPortPos"); src.setAccessible(true);
                if (!port.getBlockPos().equals(src.get(task))) continue;
                for (String name : List.of("mission", "phase", "position", "targetAllayPortPos", "returnMode", "deliveryElapsedTicks")) {
                    var field = task.getClass().getDeclaredField(name); field.setAccessible(true); detail.add(name + "=" + field.get(task));
                }
                var position = task.getClass().getDeclaredField("position"); position.setAccessible(true);
                detail.add("entityChunkTicking=" + ((net.minecraft.server.level.ServerLevel)port.getLevel())
                        .isPositionEntityTicking(BlockPos.containing((Vec3)position.get(task))));
            }
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
        return "port=" + port.getBlockPos() + " live=" + (port.getLevel().getBlockEntity(port.getBlockPos()) == port)
                + " carriers=" + carriers(port).getStackInSlot(0).getCount() + " flights=" + detail;
    }
    private static void observeNativeDeadline(GameTestHelper h, PackagePortBlockEntity port) {
        h.onEachTick(() -> { if (h.getTick() == 800) FeedMePackages.LOGGER.info("FMP_BIOTECH_NATIVE_DEADLINE {}", flightState(port)); });
    }
}
