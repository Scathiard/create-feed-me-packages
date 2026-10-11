package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.*;
import java.util.function.Predicate;

/** Loads real maids and author inventory/AI/transport entry points, never a successful absence placeholder. */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = FeedMePackages.MOD_ID)
public final class MaidTests {
    private static final UUID PROCESS = UUID.randomUUID();
    @net.neoforged.bus.api.SubscribeEvent
    public static void prepareFlightWorld(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        if (System.getProperty("fmp.maidFlight", "").isBlank()) return;
        // Load the fixed fixture before native flights tick, rather than waiting for the test callback.
        var level = event.getServer().overworld(); var chunk = new net.minecraft.world.level.ChunkPos(8, 8);
        level.setChunkForced(chunk.x, chunk.z, true);
        level.getChunkSource().updateChunkForced(chunk, true);
        level.getChunk(chunk.x, chunk.z);
    }
    @FunctionalInterface interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("touhou_little_maid")) return List.of();
        var tests = new ArrayList<>(List.of(test("native_shortage", MaidTests::shortage), test("access_boundaries", MaidTests::access),
                test("full_inventory", MaidTests::full), test("native_meal", MaidTests::meal), test("native_bow", MaidTests::bow),
                test("receipt_identity", MaidTests::receipt), test("native_air_and_residual", MaidTests::noFuel),
                test("native_death", MaidTests::death), test("entity_roundtrip", MaidTests::entityRoundtrip),
                test("network_permission", MaidTests::permission)));
        if (ModList.get().isLoaded("tacz")) tests.add(test("native_tacz_reload", MaidTests::tacz));
        if (ModList.get().isLoaded("cmpackagecouriers")) {
            tests.add(test("paper_direct_flight", h -> flight(h, false, false)));
            tests.add(test("paper_swapped_in_flight", h -> flight(h, false, true)));
        }
        if (ModList.get().isLoaded("create_mobile_packages")) {
            tests.add(test("bee_direct_flight", h -> flight(h, true, false)));
            tests.add(test("bee_swapped_in_flight", h -> flight(h, true, true)));
        }
        return tests;
    }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("maid", "fmp_maid_" + name, FeedMePackages.MOD_ID + ":empty", 650, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native maid fixture failed", failure); } });
    }
    private record Fixture(ServerPlayer owner, TamableAnimal maid, CacheLedger ledger, CacheHandle handle, ItemStack pendant, ItemVariantKey key) {
        CacheRecord record() { return ledger.find(handle.cacheId()); }
        long stock() { return record().state().cells().get(0).amount(); }
    }
    private static Fixture setup(GameTestHelper h, ItemStack item, int amount) {
        h.assertTrue(MaidCompatibility.ready(), "Maid native API was not registered");
        var owner = TestPlayers.create(h, FmpRegistries.PENDANT.toStack());
         ConsumptionTests.seed(owner, 0, item, amount);
        var handle = AccessGate.resolve(owner).handle();
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid"));
        var maid = (TamableAnimal) type.create(h.getLevel());
        h.assertTrue(maid != null, "Native maid entity not registered");
        maid.setTame(true, false); maid.setOwnerUUID(owner.getUUID()); maid.setNoAi(true); maid.setNoGravity(true);
        maid.setPos(h.absoluteVec(new Vec3(3.5, 1.2, 3.5)));
        h.assertTrue(h.getLevel().addFreshEntity(maid), "Native maid could not enter level");
        var curios = CuriosApi.getCuriosInventory(maid).orElseThrow(); curios.reset();
        var necklace = curios.getStacksHandler("necklace").orElseThrow().getStacks();
        var pendant = TestPlayers.necklace(owner).getStackInSlot(0);
        h.assertTrue(CuriosApi.getCurio(pendant).map(curio -> curio.canEquip(new top.theillusivec4.curios.api.SlotContext("necklace", maid, 0, false, true))).orElse(false),
                "Registered native Curios equip behavior refused the maid");
        TestPlayers.necklace(owner).setStackInSlot(0, ItemStack.EMPTY); necklace.setStackInSlot(0, pendant);
        var access = MaidAccess.resolve(maid);
        h.assertTrue(access.active() && access.handle().cacheId().equals(handle.cacheId()), "Native maid necklace did not expose its ordinary cache");
        return new Fixture(owner, maid, CacheLedger.get(h.getLevel().getServer()), access.handle(), pendant,
                ItemVariantKey.of(item, maid.registryAccess()));
    }
    private static int find(Fixture f, Predicate<ItemStack> filter, int limit) throws ReflectiveOperationException {
        return (int) Class.forName("com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil")
                .getMethod("findStackSlot", IItemHandler.class, Predicate.class, int.class)
                .invoke(null, MaidCompatibility.inventory(f.maid), filter, limit);
    }
    private static void shortage(GameTestHelper h) throws ReflectiveOperationException {
        ItemStack bread = new ItemStack(Items.BREAD); bread.set(DataComponents.CUSTOM_NAME, Component.literal("Exact maid food"));
        var f = setup(h, bread, 12); var inv = MaidCompatibility.inventory(f.maid);
        inv.setStackInSlot(0, bread.copyWithCount(2));
        h.assertTrue(find(f, s -> s.is(Items.BREAD), 4) == 0 && f.stock() == 12, "Native bag priority was bypassed");
        inv.setStackInSlot(0, ItemStack.EMPTY);
        int slot = find(f, s -> s.is(Items.BREAD), 4);
        h.assertTrue(slot >= 0 && inv.getStackInSlot(slot).getCount() == 4 && f.stock() == 8
                && ItemStack.isSameItemSameComponents(inv.getStackInSlot(slot), bread), "Native shortage event lost count or components");
        find(f, s -> s.is(Items.BREAD), 4);
        h.assertTrue(f.stock() == 8, "A second availability query restaged stock already in the native bag");
        h.succeed();
    }
    private static void access(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.BREAD), 10);
        var necklace = CuriosApi.getCuriosInventory(f.maid).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
        var personal = FmpRegistries.PERSONAL_PENDANT.toStack(); necklace.setStackInSlot(0, personal);
        h.assertTrue(find(f, s -> s.is(Items.BREAD), 1) == -1 && !personal.has(FmpRegistries.OWNER.get())
                && necklace.getStackInSlot(0) == personal && f.stock() == 10, "Maid claimed/degraded a personal key or spent ordinary cache");
        necklace.setStackInSlot(0, f.pendant); boolean enabled = FmpConfig.MAID.get();
        try {
            FmpConfig.MAID.set(false); FmpConfig.refreshServer();
            h.assertTrue(find(f, s -> s.is(Items.BREAD), 1) == -1 && f.stock() == 10, "Disabled maid source remained active");
        } finally { FmpConfig.MAID.set(enabled); FmpConfig.refreshServer(); }
        necklace.setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(find(f, s -> s.is(Items.BREAD), 1) == -1 && f.stock() == 10, "Unequipped maid spent the previous cache");
        necklace.setStackInSlot(0, f.pendant); f.maid.setTame(false, false);
        h.assertTrue(!MaidAccess.resolve(f.maid).active() && f.stock() == 10, "Owner UUID alone authorized an untamed entity");
        h.succeed();
    }
    private static void permission(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.BREAD), 0); var network = UUID.randomUUID();
        var factory = new BlockPos(2, 1, 2); SupplyTests.placeItemFactory(h, factory);
        ((ChestBlockEntity) h.getBlockEntity(factory.south())).setItem(0, new ItemStack(Items.BREAD, 64));
        h.runAfterDelay(5, () -> {
            SupplyTests.connect(h, factory, network); f.pendant.set(FmpRegistries.NETWORK.get(), network);
            com.simibubi.create.Create.LOGISTICS.linkAdded(network,
                    net.minecraft.core.GlobalPos.of(h.getLevel().dimension(), h.absolutePos(factory.above())), UUID.randomUUID());
            var policy = com.simibubi.create.Create.LOGISTICS.logisticsNetworks.get(network);
            policy.locked = true;
            var before = f.record(); var edit = before.state().edit(); edit.settings(0, new dev.scathiard.feedmepackages.domain.CellSettings(16, -1, 1, 16));
            f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish()));
            h.assertTrue(!MaidAccess.endpoint(f.maid).mayRequest(network)
                    && !com.simibubi.create.Create.LOGISTICS.mayInteract(network, f.owner), "Maid bypassed Create locked owner policy");
        });
        h.runAfterDelay(50, () -> {
            h.assertTrue(f.record().state().pending(0) == 0 && ((ChestBlockEntity) h.getBlockEntity(factory.south())).getItem(0).getCount() == 64,
                    "Locked foreign network actually dispatched goods");
            com.simibubi.create.Create.LOGISTICS.logisticsNetworks.get(network).owner = f.owner.getUUID();
            h.assertTrue(MaidAccess.endpoint(f.maid).mayRequest(network)
                    && com.simibubi.create.Create.LOGISTICS.mayInteract(network, f.owner), "Offline owner's network remained inaccessible");
        });
        h.runAfterDelay(390, () -> {
            h.assertTrue(f.record().state().pending(0) == 16 && ((ChestBlockEntity) h.getBlockEntity(factory.south())).getItem(0).getCount() == 48,
                    "Restored native permission did not resume real factory request"); h.succeed();
        });
    }
    private static void full(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.BREAD), 10); var inv = MaidCompatibility.inventory(f.maid);
        for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
        h.assertTrue(find(f, s -> s.is(Items.BREAD), 4) == -1 && f.stock() == 10, "Full native bag consumed or dropped cache stock");
        inv.setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(find(f, s -> s.is(Items.BREAD), 4) == 0 && f.stock() == 6, "Freeing one real native slot did not resume transfer");
        h.succeed();
    }
    private static void meal(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.MUSHROOM_STEW), 1);
        var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask");
        var start = type.getDeclaredMethod("start", ServerLevel.class, f.maid.getClass(), long.class); start.setAccessible(true);
        start.invoke(type.getConstructor().newInstance(), h.getLevel(), f.maid, h.getLevel().getGameTime());
        h.assertTrue(f.maid.isUsingItem() && f.stock() == 0, "Native meal task did not begin eating cached food");
        h.runAfterDelay(45, () -> {
            int bowls = 0; var inv = MaidCompatibility.inventory(f.maid);
            for (var stack : f.maid.getHandSlots()) if (stack.is(Items.BOWL)) bowls += stack.getCount();
            for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(Items.BOWL)) bowls += inv.getStackInSlot(i).getCount();
            h.assertTrue(bowls == 1 && f.stock() == 0 && !f.maid.isUsingItem(), "Native eating lost/repeated the bowl or did not finish");
            h.succeed();
        });
    }
    private static void bow(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.ARROW), 4); var bow = new ItemStack(Items.BOW);
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, bow);
        var target = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 3));
        var task = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskBowAttack").getConstructor().newInstance();
        task.getClass().getMethod("performRangedAttack", f.maid.getClass(), LivingEntity.class, float.class)
                .invoke(task, f.maid, target, 1f);
        int arrows = 0; var inv = MaidCompatibility.inventory(f.maid);
        for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(Items.ARROW)) arrows += inv.getStackInSlot(i).getCount();
        h.assertTrue(f.stock() + arrows == 3 && bow.getDamageValue() == 1 && f.maid.getMainHandItem() == bow,
                "Native maid bow did not consume one real arrow or retain the weapon instance");
        h.succeed();
    }
    private static ItemStack parcel(Fixture f, int amount) {
        var box = PackageItem.containing(List.of(f.key.stack(f.maid.registryAccess(), amount)));
        String address = MaidAccess.address(f.maid); PackageItem.addAddress(box, address);
        var seal = new ParcelSeal(UUID.randomUUID(), f.handle.cacheId(), f.maid.getUUID(), UUID.randomUUID(),
                f.key.encoded(), f.record().state().cells().get(0).filterRevision(), address, false, "");
        ParcelAuthentication.apply(f.ledger, f.maid.registryAccess(), box, seal); return box;
    }
    private static void noFuel(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.BREAD), 0);
        var inv = MaidCompatibility.inventory(f.maid);
        var tank = com.simibubi.create.AllItems.COPPER_BACKTANK.asStack();
        tank.set(com.simibubi.create.AllDataComponents.BACKTANK_AIR, 9); inv.setStackInSlot(1, tank);
        var expected = tank.copy(); inv.setStackInSlot(0, parcel(f, 3)); MaidCompatibility.tick(f.maid);
        h.assertTrue(inv.getStackInSlot(0).isEmpty() && !f.record().anyResidual() && f.stock() == 3,
                "Maid receipt incorrectly required fuel or another inventory slot");
        h.runAfterDelay(205, () -> {
            h.assertTrue(f.stock() == 3 && ItemStack.matches(tank, expected) && inv.getStackInSlot(1) == tank,
                    "Maid logistics drained or replaced its native backtank"); h.succeed();
        });
    }
    private static void death(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.BREAD), 10);
        var server = h.getLevel().getServer();
        h.assertTrue(server.getPlayerList().getPlayer(f.owner.getUUID()) == null, "Maid death must retain its offline-owner scenario");
        // Vanilla GameTestServer omits Services.profileCache, while real servers provide it.
        // Corail checks offline tame-animal owners before the maid's own death handler.
        // Supply the real vanilla cache only around that native event; never register the owner online.
        var servicesField = net.minecraft.server.MinecraftServer.class.getDeclaredField("services");
        servicesField.setAccessible(true);
        var originalServices = (net.minecraft.server.Services) servicesField.get(server);
        boolean needsProfileCache = ModList.get().isLoaded("tombstone") && server.getProfileCache() == null;
        try {
            if (needsProfileCache) {
                h.assertTrue(server instanceof net.minecraft.gametest.framework.GameTestServer,
                        "Only the vanilla test server may omit its profile cache");
                var profiles = new net.minecraft.server.players.GameProfileCache(null,
                        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("fmp-maid-profilecache.json").toFile());
                profiles.add(f.owner.getGameProfile());
                servicesField.set(server, new net.minecraft.server.Services(originalServices.sessionService(),
                        originalServices.servicesKeySet(), originalServices.profileRepository(), profiles));
            }
            f.maid.hurt(f.maid.damageSources().genericKill(), Float.MAX_VALUE);
        } finally {
            if (needsProfileCache) servicesField.set(server, originalServices);
        }
        h.assertTrue(!MaidAccess.resolve(f.maid).active() && f.stock() == 10, "Death retained active access or changed the ordinary cache");
        Entity grave = null;
        for (var entity : h.getLevel().getAllEntities())
            if (entity.getClass().getName().equals("com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone")
                    && entity.distanceToSqr(f.maid) < 25) { grave = entity; break; }
        h.assertTrue(grave != null, "Native maid tombstone was not created");
        var items = (IItemHandler) grave.getClass().getMethod("getItems").invoke(grave); int found = 0;
        for (int i = 0; i < items.getSlots(); i++) if (f.handle.cacheId().equals(items.getStackInSlot(i).get(FmpRegistries.IDENTITY.get()))) found++;
        h.assertTrue(found == 1, "Native tombstone did not retain exactly the physical ordinary pendant");
        h.assertTrue(server.getPlayerList().getPlayer(f.owner.getUUID()) == null,
                "Offline owner was made online to bypass a native death listener");
        dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_MAID_DEATH_PASSED offlineOwner=true nativeTombstonePendant={} stock={} testProfileCache={}", found, f.stock(), needsProfileCache);
        h.succeed();
    }
    private static void entityRoundtrip(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.BREAD), 10); var saved = new net.minecraft.nbt.CompoundTag();
        h.assertTrue(f.maid.save(saved), "Native maid save rejected a live entity");
        String address = MaidAccess.address(f.maid); f.maid.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.assertTrue(MaidAccess.find(h.getLevel().getServer(), address) == null && f.stock() == 10, "Unloaded maid remained addressable");
        h.runAfterDelay(2, () -> {
            var restored = (LivingEntity) EntityType.loadEntityRecursive(saved, h.getLevel(), entity -> entity);
            h.assertTrue(restored != null && h.getLevel().addFreshEntity(restored), "Native entity could not load saved data");
            var access = MaidAccess.resolve(restored);
            h.assertTrue(access.active() && access.handle().cacheId().equals(f.handle.cacheId()) && f.stock() == 10
                    && MaidAccess.find(h.getLevel().getServer(), address) == restored, "Native Curios/entity roundtrip changed the cache or address");
            h.succeed();
        });
    }
    private static void tacz(GameTestHelper h) throws ReflectiveOperationException {
        var ammo = TaczTests.ammo("762x39"); var f = setup(h, ammo, 100); var gun = TaczTests.gun(f.owner, "ak47");
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, gun); f.maid.restrictTo(f.maid.blockPosition(), 20);
        var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        op.getMethod("initialData").invoke(f.maid);
        op.getMethod("draw", java.util.function.Supplier.class).invoke(f.maid, (java.util.function.Supplier<ItemStack>) f.maid::getMainHandItem);
        var holder = op.getMethod("getDataHolder").invoke(f.maid); var data = holder.getClass();
        data.getField("drawTimestamp").setLong(holder, -1); data.getField("shootTimestamp").setLong(holder, -30_000);
        var target = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 3));
        var compat = Class.forName("com.github.tartaricacid.touhoulittlemaid.compat.gun.tacz.TacInnerCompat");
        var attack = compat.getDeclaredMethod("performGunAttack", f.maid.getClass(), LivingEntity.class, ItemStack.class); attack.setAccessible(true);
        attack.invoke(null, f.maid, target, gun);
        h.assertTrue(data.getField("reloadTimestamp").getLong(holder) >= 0 && TaczTests.totalLoaded(gun) == 0 && f.stock() < 100,
                "Native maid NO_AMMO did not request real ammo and start reload");
        var inv = MaidCompatibility.inventory(f.maid); long staged = 100 - f.stock();
        op.getMethod("cancelReload").invoke(f.maid);
        h.assertTrue(f.stock() + count(inv, ammo) == 100 && TaczTests.totalLoaded(gun) == 0,
                "Native cancellation lost staged ammo or refilled a canceled magazine");
        op.getMethod("initialData").invoke(f.maid);
        op.getMethod("draw", java.util.function.Supplier.class).invoke(f.maid, (java.util.function.Supplier<ItemStack>) f.maid::getMainHandItem);
        holder = op.getMethod("getDataHolder").invoke(f.maid);
        data.getField("drawTimestamp").setLong(holder, -1); data.getField("shootTimestamp").setLong(holder, -30_000);
        op.getMethod("reload").invoke(f.maid);
        var reload = TaczTests.reloadService(f.maid, holder);
        data.getField("reloadTimestamp").setLong(holder, System.currentTimeMillis() - 30_000);
        reload.getClass().getMethod("tickReloadState").invoke(reload);
        h.assertTrue(TaczTests.totalLoaded(gun) == 30 && f.stock() + count(inv, ammo) == 70 && f.stock() == 100 - staged,
                "Native maid reload did not conserve cache, staged ammo and magazine");
        h.succeed();
    }
    private static int count(IItemHandler inv, ItemStack item) {
        int count = 0; for (int i = 0; i < inv.getSlots(); i++)
            if (ItemStack.isSameItemSameComponents(inv.getStackInSlot(i), item)) count += inv.getStackInSlot(i).getCount();
        return count;
    }
    private static void receipt(GameTestHelper h) {
        var f = setup(h, new ItemStack(Items.BREAD), 0); var other = setup(h, new ItemStack(Items.BREAD), 0);
        var box = parcel(f, 4);
        h.assertTrue(!ReceiveService.receive(MaidAccess.endpoint(other.maid), box) && box.getCount() == 1 && other.stock() == 0,
                "Another maid received a parcel addressed to a different entity/cache");
        var slots = CuriosApi.getCuriosInventory(f.maid).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
        slots.setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        h.assertTrue(!ReceiveService.receive(MaidAccess.endpoint(f.maid), box) && box.getCount() == 1 && f.stock() == 0,
                "A replacement pendant received the old pendant's parcel");
        slots.setStackInSlot(0, f.pendant);
        h.assertTrue(ReceiveService.receive(MaidAccess.endpoint(f.maid), box)  &&  box.isEmpty()  &&  f.stock() == 4
                , "Original physical pendant did not receive its contents exactly once");
        h.succeed();
    }
    private static void flight(GameTestHelper h, boolean mobile, boolean swap) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.BREAD), 0);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
        h.assertTrue(mobile ? MaidCouriers.beeReady() : MaidCouriers.paperReady(), "Direct transport port failed to initialize");
        var inv = MaidCompatibility.inventory(f.maid);
        for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
        var network = new UUID[]{UUID.randomUUID()}; Object[] port = {null};
        if (mobile) {
            var pos = new BlockPos(1, 1, 1); var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
            h.setBlock(pos, block.defaultBlockState());
            block.setPlacedBy(h.getLevel(), h.absolutePos(pos), h.getBlockState(pos), f.owner, new ItemStack(block));
            port[0] = h.getBlockEntity(pos); var type = port[0].getClass();
            network[0] = (UUID) type.getMethod("getLogisticsNetworkId").invoke(port[0]);
            type.getMethod("addBeeToRoboBeeInventory", int.class).invoke(port[0], 1);
            type.getMethod("setBeeReturnModeEnabled", boolean.class).invoke(port[0], true);
            var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            join.getMethod("handle", ServerPlayer.class).invoke(join.getConstructor(UUID.class, UUID.class).newInstance(f.owner.getUUID(), network[0]), f.owner);
        }
        var factory = new BlockPos(2, 1, 2); SupplyTests.placeItemFactory(h, factory);
        ((ChestBlockEntity) h.getBlockEntity(factory.south())).setItem(0, new ItemStack(Items.BREAD, 64));
        var before = f.record(); var edit = before.state().edit(); edit.settings(0, new dev.scathiard.feedmepackages.domain.CellSettings(16, -1, 1, 16));
        f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish()));
        boolean[] connected = {false}, launched = {false}, swapped = {false}; long[] launchAt = {-1}, recoveredAt = {-1};
        UUID[] parcelId = {null}, replacementId = {null};
        h.onEachTick(() -> {
            try {
                if (h.getTick() < 5) return;
                if (!connected[0]) {
                    SupplyTests.connect(h, factory, network[0]); f.pendant.set(FmpRegistries.NETWORK.get(), network[0]); connected[0] = true;
                }
                if (!launched[0]) {
                    var box = SupplyTests.takeOutput(h, factory); if (box.isEmpty()) return;
                    var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
                    h.assertTrue(seal != null && seal.playerId().equals(f.maid.getUUID()) && seal.address().equals(MaidAccess.address(f.maid))
                            && f.record().state().pending(0) == 16, "Native request did not target and reserve the maid's own cache");
                    parcelId[0] = seal.parcelId();
                    if (mobile) h.assertTrue((boolean) port[0].getClass().getMethod("addItemStack", ItemStack.class, boolean.class)
                            .invoke(port[0], box.copy(), false), "Native bee port refused the real factory output");
                    else {
                        var manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
                        h.assertTrue((boolean) manager.getMethod("addPlane", Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class)
                                .invoke(null, h.getLevel(), h.absoluteVec(new Vec3(1, 2, 1)), 0f, 0f, box.copy(), false), "Native paper launch did not resolve the maid");
                    }
                    box.setCount(0); launched[0] = true; launchAt[0] = h.getTick();
                    f.maid.setCustomName(Component.literal("Renamed during flight"));
                    f.maid.setPos(h.absoluteVec(new Vec3(4, 1.2, 3.5)));
                }
                if (swap) {
                    var flights = transit(f, mobile);
                    if (!swapped[0] && !flights.isEmpty()) {
                        var replacement = FmpRegistries.PENDANT.toStack(); replacement.set(FmpRegistries.NETWORK.get(), network[0]);
                        CuriosApi.getCuriosInventory(f.maid).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, replacement);
                        replacementId[0] = MaidAccess.resolve(f.maid).handle().cacheId(); swapped[0] = true;
                    }
                    if (!swapped[0]) return;
                    h.assertTrue(f.stock() == 0  &&  f.record().state().pending(0) == 16  &&  f.ledger.find(replacementId[0]).state().cells().stream().allMatch(c -> c.amount() == 0),
                            "Target swap credited a different pendant or reset/billed the original order");
                    var recovered = new ArrayList<ItemStack>();
                    for (var entity : h.getLevel().getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(f.maid.blockPosition()).inflate(7))) {
                        if (entity instanceof com.simibubi.create.content.logistics.box.PackageEntity parcel) recovered.add(parcel.getBox());
                        else if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) recovered.add(item.getItem());
                    }
                    if (mobile) {
                        var handler = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                h.absolutePos(new BlockPos(1, 1, 1)), net.minecraft.core.Direction.UP);
                        h.assertTrue(handler != null, "Native bee cargo capability unavailable");
                        for (int i = 0; i < handler.getSlots(); i++) recovered.add(handler.getStackInSlot(i));
                    }
                    recovered = new ArrayList<>(recovered.stream().filter(b -> b.has(FmpRegistries.PARCEL_SEAL.get())
                            && b.get(FmpRegistries.PARCEL_SEAL.get()).parcelId().equals(parcelId[0])).toList());
                    if (!flights.isEmpty() || recovered.isEmpty()) return;
                    h.assertTrue(recovered.size() == 1 && recovered.getFirst().getCount() == 1
                                    && count(PackageItem.getContents(recovered.getFirst()), new ItemStack(Items.BREAD)) == 16
                                    && ((ChestBlockEntity) h.getBlockEntity(factory.south())).getItem(0).getCount() == 48,
                            "Failed native flight lost or duplicated the original factory cargo");
                    if (recoveredAt[0] < 0) recoveredAt[0] = h.getTick();
                    if (h.getTick() - recoveredAt[0] < 20) return;
                    FeedMePackages.LOGGER.info("FMP_MAID_SWAP_FLIGHT_PASSED transport={} originalCargo=16 cache=0 pending=16", mobile ? "bee" : "paper");
                    h.succeed(); return;
                }
                if (f.stock() != 16) return;
                h.assertTrue(h.getTick() > launchAt[0]  &&  f.record().state().pending(0) == 0
                                 &&  ((ChestBlockEntity) h.getBlockEntity(factory.south())).getItem(0).getCount() == 48
                                 &&  f.owner.getServer().getPlayerList().getPlayer(f.owner.getUUID()) == null,
                        "Direct native flight did not conserve source, cache or request with owner offline");
                if (mobile) {
                    var bees = (IItemHandler) port[0].getClass().getMethod("getRoboBeeInventory").invoke(port[0]);
                    if (bees.getStackInSlot(0).getCount() != 1) return;
                }
                FeedMePackages.LOGGER.info("FMP_MAID_DIRECT_FLIGHT_PASSED transport={} source=48 cache=16 ownerOffline=true", mobile ? "bee" : "paper");
                h.succeed();
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native maid flight failed", failure); }
        });
    }
    private static List<ItemStack> transit(Fixture f, boolean mobile) throws ReflectiveOperationException {
        var boxes = new ArrayList<ItemStack>();
        if (mobile) {
            var type = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            var manager = type.getMethod("get", ServerLevel.class).invoke(null, f.maid.level());
            for (var robo : ((Map<?, ?>) type.getField("robos").get(manager)).values())
                boxes.add((ItemStack) robo.getClass().getMethod("getItemStack").invoke(robo));
        } else {
            var type = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            var manager = type.getField("INSTANCE").get(null);
            for (var pair : (List<?>) manager.getClass().getField("pairedPlanes").get(manager)) {
                var plane = ((net.createmod.catnip.data.Pair<?, ?>) pair).getFirst();
                boxes.add((ItemStack) plane.getClass().getMethod("getPackage").invoke(plane));
            }
        }
        return boxes.stream().filter(box -> box.has(FmpRegistries.PARCEL_SEAL.get())
                && box.get(FmpRegistries.PARCEL_SEAL.get()).cacheId().equals(f.handle.cacheId())).toList();
    }

    /** Native world, entity, packager and carrier files across three JVMs; never re-create saved cargo. */
    // The bee's native 6 blocks/second needs over 1,200 ticks for this 180-block delivery and return.
    @GameTest(template = "empty", templateNamespace = "fmp_maid_lifecycle", timeoutTicks = 1800)
    public static void flightAcrossProcesses(GameTestHelper h) throws ReflectiveOperationException {
        String[] mode = System.getProperty("fmp.maidFlight", "").split(":");
        h.assertTrue(mode.length == 3, "Dedicated maid-flight invocation required");
        boolean mobile = mode[0].equals("bee"); String phase = mode[1];
        var level = h.getLevel(); var server = level.getServer();
        h.assertTrue(MaidCompatibility.ready() && (mobile ? MaidCouriers.beeReady() : MaidCouriers.paperReady()), "Real maid/carrier unavailable");
        var probe = level.getDataStorage().computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(FlightProbe::new, FlightProbe::load), "fmp_maid_flight_probe");
        var anchor = new BlockPos(128, 64, 128); var factory = anchor.offset(2, 1, 2); var portPos = anchor.offset(1, 1, 1);
        // The isolated fixture must exist while no real player is online; production adds no chunk ticket.
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        // Reassert this test-only ticket even when its persisted membership is already present.
        level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(anchor), true);
        level.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
        if (phase.equals("write")) {
            h.assertTrue(probe.data.isEmpty(), "Flight evidence already exists; use a new batch instead of overwriting");
            var f = setup(h, new ItemStack(Items.BREAD), 0);
            f.maid.setPos(anchor.getX() + 3.5, anchor.getY() + 180, anchor.getZ() + 3.5);
            f.maid.setPersistenceRequired();
            var inv = MaidCompatibility.inventory(f.maid);
            for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
            for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) level.setBlockAndUpdate(anchor.offset(x, 0, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            UUID network = UUID.randomUUID(); Object port = null;
            if (mobile) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
                level.setBlockAndUpdate(portPos, block.defaultBlockState());
                block.setPlacedBy(level, portPos, level.getBlockState(portPos), f.owner, new ItemStack(block));
                port = level.getBlockEntity(portPos); var type = port.getClass();
                network = (UUID) type.getMethod("getLogisticsNetworkId").invoke(port);
                type.getMethod("addBeeToRoboBeeInventory", int.class).invoke(port, 1);
                type.getMethod("setBeeReturnModeEnabled", boolean.class).invoke(port, true);
                var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
                join.getMethod("handle", ServerPlayer.class).invoke(join.getConstructor(UUID.class, UUID.class).newInstance(f.owner.getUUID(), network), f.owner);
            }
            // Vanilla relativePos applies an additional 180-degree rotation; this fixture has rotation zero.
            var relativeFactory = factory.subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(h.absolutePos(relativeFactory).equals(factory), "Fixed flight arena must use an unrotated template");
            SupplyTests.placeItemFactory(h, relativeFactory);
            ((ChestBlockEntity) level.getBlockEntity(factory.south())).setItem(0, new ItemStack(Items.BREAD, 64));
            var before = f.record(); var edit = before.state().edit(); edit.settings(0, new dev.scathiard.feedmepackages.domain.CellSettings(16, -1, 1, 16));
            f.ledger.replace(f.handle, before.state().revision(), before.withState(edit.finish()));
            UUID finalNetwork = network; Object finalPort = port;
            boolean[] connected = {false}, launched = {false}; UUID[] parcel = {null}; long[] launchAt = {-1};
            h.onEachTick(() -> {
                try {
                    if (h.getTick() < 5) return;
                    if (!connected[0]) {
                        SupplyTests.connect(h, relativeFactory, finalNetwork); f.pendant.set(FmpRegistries.NETWORK.get(), finalNetwork); connected[0] = true;
                    }
                    if (!launched[0]) {
                        var box = SupplyTests.takeOutput(h, relativeFactory); if (box.isEmpty()) return;
                        var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
                        h.assertTrue(seal != null && seal.playerId().equals(f.maid.getUUID()) && f.record().state().pending(0) == 16, "Native writer did not request a maid parcel");
                        parcel[0] = seal.parcelId();
                        if (mobile) h.assertTrue((boolean) finalPort.getClass().getMethod("addItemStack", ItemStack.class, boolean.class).invoke(finalPort, box.copy(), false), "Native port refused original cargo");
                        else {
                            var manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
                            h.assertTrue((boolean) manager.getMethod("addPlane", Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class)
                                    .invoke(null, level, Vec3.atCenterOf(anchor.above(2)), 0f, 0f, box.copy(), false), "Native paper launch refused saved target");
                        }
                        box.setCount(0); launched[0] = true; launchAt[0] = h.getTick();
                        // Isolate this one real order; changing a threshold does not cancel its in-flight goods.
                        var current = f.record(); var stop = current.state().edit(); stop.thresholds(0, -1, -1);
                        f.ledger.replace(f.handle, current.state().revision(), current.withState(stop.finish()));
                    }
                    // Let the author's next native manager tick mark its SavedData dirty before shutdown.
                    if (h.getTick() <= launchAt[0]) return;
                    var cargo = transit(f, mobile); if (cargo.isEmpty()) return;
                    h.assertTrue(cargo.size() == 1 && cargo.getFirst().get(FmpRegistries.PARCEL_SEAL.get()).parcelId().equals(parcel[0])
                            && count(PackageItem.getContents(cargo.getFirst()), new ItemStack(Items.BREAD)) == 16
                            && f.stock() == 0 && f.record().state().pending(0) == 16, "Save point is not one actual in-flight shipment");
                    probe.data.putUUID("maid", f.maid.getUUID()); probe.data.putUUID("owner", f.owner.getUUID());
                    probe.data.putUUID("cache", f.handle.cacheId()); probe.data.putUUID("parcel", parcel[0]); probe.data.putUUID("network", finalNetwork);
                    probe.data.putString("carrier", mode[0]); markFlight(probe, "write");
                    FeedMePackages.LOGGER.info("FMP_MAID_FLIGHT_FIRST_WRITE carrier={} parcel={} cache=0 pending=16 nativeCargo=16", mode[0], parcel[0]);
                    h.succeed();
                } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native flight writer failed", failed); }
            });
            return;
        }
        h.assertTrue(!probe.data.isEmpty() && !PROCESS.equals(probe.data.getUUID("process")) && mode[0].equals(probe.data.getString("carrier"))
                && probe.data.getString("phase").equals(phase.equals("read") ? "write" : "read"), "Prior separate JVM phase is missing");
        var ledger = CacheLedger.get(server); UUID cache = probe.data.getUUID("cache");
        boolean[] loaded = {false}; long[] deliveredAt = {-1};
        h.onEachTick(() -> {
            try {
                var maid = level.getEntity(probe.data.getUUID("maid")); if (!(maid instanceof LivingEntity living)) return;
                var access = MaidAccess.resolve(living);
                h.assertTrue(access.active() && access.handle().cacheId().equals(cache), "Saved native maid necklace did not reopen original cache");
                var record = ledger.find(cache); var stock = record.state().cells().get(0).amount();
                if (!loaded[0]) {
                    if (phase.equals("read")) {
                        h.assertTrue(stock == 0 && record.state().pending(0) == 16, "Writer finished delivery before the restart boundary");
                        var actual = new Fixture(null, (TamableAnimal) living, ledger, access.handle(), ItemStack.EMPTY,
                                ItemVariantKey.of(new ItemStack(Items.BREAD), level.registryAccess()));
                        var cargo = transit(actual, mobile);
                        h.assertTrue(cargo.size() == 1, "Original in-flight cargo count after native entity load: " + cargo.size());
                        h.assertTrue(cargo.getFirst().get(FmpRegistries.PARCEL_SEAL.get()).parcelId().equals(probe.data.getUUID("parcel")),
                                "Native restart replaced the original parcel identity");
                        h.assertTrue(ParcelAuthentication.valid(ledger, level.registryAccess(), cargo.getFirst(), cargo.getFirst().get(FmpRegistries.PARCEL_SEAL.get())),
                                "Native restart changed original parcel authentication");
                        living.setCustomName(Component.literal("Moved after JVM restart")); living.setPos(living.position().add(1, 2, 0));
                    }
                    loaded[0] = true;
                }
                h.assertTrue(((ChestBlockEntity) level.getBlockEntity(factory.south())).getItem(0).getCount() == 48, "Restart reissued or lost source goods");
                if (stock != 16) return;
                h.assertTrue(record.state().pending(0) == 0  &&  server.getPlayerList().getPlayer(probe.data.getUUID("owner")) == null, "Restart delivery changed request or offline-owner boundary");
                if (mobile) {
                    var port = level.getBlockEntity(portPos); var bees = (IItemHandler) port.getClass().getMethod("getRoboBeeInventory").invoke(port);
                    if (bees.getStackInSlot(0).getCount() != 1) return;
                }
                if (deliveredAt[0] < 0) deliveredAt[0] = h.getTick();
                if (h.getTick() - deliveredAt[0] < 60) return;
                markFlight(probe, phase);
                FeedMePackages.LOGGER.info("FMP_MAID_FLIGHT_PRIOR_JVM_PASSED carrier={} phase={} originalParcel={} source=48 cache=16 pending=0 ownerOffline=true", mode[0], phase, probe.data.getUUID("parcel"));
                h.succeed();
            } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native flight restore failed", failed); }
        });
    }
    private static void markFlight(FlightProbe probe, String phase) {
        probe.data.putString("phase", phase); probe.data.putUUID("process", PROCESS); probe.setDirty();
    }
    private static final class FlightProbe extends net.minecraft.world.level.saveddata.SavedData {
        net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
        static FlightProbe load(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            var probe = new FlightProbe(); probe.data = tag.copy(); return probe;
        }
        @Override public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) { return data.copy(); }
    }
}
