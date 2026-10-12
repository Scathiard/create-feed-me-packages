package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.compat.tacz.TaczMaterials;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;
import java.util.function.Supplier;

/** Runs the pinned mod's own gun, ammo predicate and script API; no TaCZ classes in the shipped JAR. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class TaczTests {
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("tacz")) return List.of();
        return List.of(test("native_sources", TaczTests::nativeSources), test("source_boundaries", TaczTests::sourceBoundaries),
                test("script_reload", h -> reload(h, "ak47", "762x39", 30)),
                test("default_reload", h -> reload(h, "m249", "556x45", 75)),
                test("box_and_variants", TaczTests::boxAndVariants), test("inventory_firing", TaczTests::inventoryFiring),
                test("canceled_reload_event", TaczTests::canceledReload), test("partial_reload", TaczTests::partialReload),
                test("creative_reload", TaczTests::creativeReload), test("shape_guard", TaczTests::shapeGuard),
                test("canceled_shoot", TaczTests::canceledShoot), test("infinite_reload", TaczTests::infiniteReload));
    }
    @FunctionalInterface private interface Body { void run(GameTestHelper helper) throws ReflectiveOperationException; }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("tacz", "fmp_tacz_" + name, FeedMePackages.MOD_ID + ":empty", 200, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native TaCZ fixture failed", e); } });
    }
    private static ServerPlayer player(GameTestHelper h) {
        var player = TestPlayers.real(h, UUID.randomUUID()); TestPlayers.nativePackets(player);
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack()); return player;
    }
    private static void nativeSources(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h);
        var gun = gun(player, "ak47"); var ammo = ammo("762x39");
        h.assertTrue(!gun.isEmpty() && !ammo.isEmpty(), "Native default gun pack did not initialize");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var gunType = Class.forName("com.tacz.guns.api.item.gun.AbstractGunItem");
        var canReload = gunType.getMethod("canReload", LivingEntity.class, ItemStack.class);
        h.assertTrue(!(boolean)canReload.invoke(gun.getItem(), player, gun), "Empty fixture already reloadable");
        player.getInventory().setItem(1, ammo.copyWithCount(3));
        h.assertTrue((boolean)canReload.invoke(gun.getItem(), player, gun), "Native inventory control failed");
        player.getInventory().setItem(1, ItemStack.EMPTY);
        ConsumptionTests.seed(player, 0, ammo, 120);
        h.assertTrue(ConsumptionTests.stock(player, 0) == 120, "Ammo fixture exceeded level-one group capacity");
        h.assertTrue((boolean)canReload.invoke(gun.getItem(), player, gun), "Cache-only native reload query rejected valid ammo");
        h.assertTrue(ConsumptionTests.stock(player, 0) == 120, "Reload query consumed cached ammo");
        var script = script(player, gun);
        h.assertTrue((boolean)script.getClass().getMethod("hasAmmoToConsume").invoke(script), "Native script could not see cached ammo");
        player.getInventory().setItem(1, ammo.copyWithCount(3));
        int taken = (int)script.getClass().getMethod("consumeAmmoFromPlayer", int.class).invoke(script, 100);
        h.assertTrue(taken == 100 && player.getInventory().getItem(1).isEmpty() && ConsumptionTests.stock(player, 0) == 23,
                "Native multi-stack extraction did not take inventory 3 then cache 97: got " + taken);
        h.assertTrue(player.getMainHandItem() == gun, "Ammo extraction replaced native gun instance");
        h.succeed();
    }
    private static void sourceBoundaries(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "ak47"); var ammo = ammo("762x39");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var wrong = ammo("9mm"); ConsumptionTests.seed(player, 0, wrong, 10);
        var script = script(player, gun); var consume = script.getClass().getMethod("consumeAmmoFromPlayer", int.class);
        h.assertTrue((int)consume.invoke(script, 5) == 0 && ConsumptionTests.stock(player, 0) == 10, "Wrong component-defined ammo type was consumed");
        ConsumptionTests.seed(player, 1, ammo, 10);
        var view = CacheActions.open(player);
        h.assertTrue(CacheActions.execute(player, new CacheActions.Intent(view.session(), view.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 1, 8, -1, "")) == CacheActions.Result.OK, "Ammo reservation fixture failed");
        h.assertTrue((int)consume.invoke(script, 5) == 2 && ConsumptionTests.stock(player, 1) == 8,
                "Native extraction spent cursor-reserved ammo or invented shortage rounds");
        player.closeContainer();
        boolean previous = FmpConfig.TACZ.get();
        try {
            FmpConfig.TACZ.set(false); FmpConfig.refreshServer();
            h.assertTrue(CompatibilityStatus.tacz() == CompatibilityStatus.State.CONFIG_DISABLED
                    && (int)consume.invoke(script, 2) == 0 && ConsumptionTests.stock(player, 1) == 8, "Disabled TaCZ port spent cache");
            player.getInventory().setItem(1, ammo.copyWithCount(3));
            h.assertTrue((int)consume.invoke(script, 2) == 2 && player.getInventory().getItem(1).getCount() == 1,
                    "Disabled adapter broke native inventory extraction");
        } finally { FmpConfig.TACZ.set(previous); FmpConfig.refreshServer(); }
        var nativeHandler = player.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY);
        var combined = (net.neoforged.neoforge.items.IItemHandler)TaczMaterials.append(player, gun, nativeHandler, 8);
        int first = nativeHandler.getSlots();
        h.assertTrue(combined.getSlots() > first && combined.extractItem(first, 4, true).getCount() == 4
                && ConsumptionTests.stock(player, 1) == 8, "Simulated extraction consumed ammo");
        TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(combined.extractItem(first, 4, false).isEmpty(), "Stale handler retained cache access after unequip");
        h.succeed();
    }
    private static void reload(GameTestHelper h, String gunId, String ammoId, int capacity) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, gunId); var ammo = ammo(ammoId);
        player.setItemInHand(InteractionHand.MAIN_HAND, gun); ConsumptionTests.seed(player, 0, ammo, 100);
        h.assertTrue(ConsumptionTests.stock(player, 0) == 100, "Reload fixture capacity too small");
        var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        op.getMethod("initialData").invoke(player);
        op.getMethod("draw", Supplier.class).invoke(player, (Supplier<ItemStack>)player::getMainHandItem);
        var holder = op.getMethod("getDataHolder").invoke(player); var holderType = holder.getClass();
        holderType.getField("drawTimestamp").setLong(holder, -1);
        holderType.getField("shootTimestamp").setLong(holder, -30_000);
        op.getMethod("reload").invoke(player);
        var timestamp = holderType.getField("reloadTimestamp");
        h.assertTrue(timestamp.getLong(holder) >= 0 && ConsumptionTests.stock(player, 0) == 100,
                "Native reload did not start or charged before feed: timestamp=" + timestamp.getLong(holder)
                        + " state=" + holderType.getField("reloadStateType").get(holder) + " stock=" + ConsumptionTests.stock(player, 0));
        op.getMethod("cancelReload").invoke(player);
        h.assertTrue(ConsumptionTests.stock(player, 0) == 100 && magazine(gun) == 0, "Cancel before feed consumed ammunition");
        // A fresh native draw clears the canceled animation state without reconstructing the gun or inventory.
        op.getMethod("initialData").invoke(player); op.getMethod("draw", Supplier.class).invoke(player, (Supplier<ItemStack>)player::getMainHandItem);
        holder = op.getMethod("getDataHolder").invoke(player);
        holderType.getField("drawTimestamp").setLong(holder, -1); holderType.getField("shootTimestamp").setLong(holder, -30_000);
        op.getMethod("reload").invoke(player);
        var nativeReload = reloadService(player, holder);
        var tick = nativeReload.getClass().getMethod("tickReloadState");
        // Advance only the native elapsed-time input; the author's tick/script decides the feed and quantity.
        timestamp.setLong(holder, System.currentTimeMillis() - 30_000);
        tick.invoke(nativeReload);
        int loaded = magazine(gun) + ((boolean)gun.getItem().getClass().getMethod("hasBulletInBarrel", ItemStack.class).invoke(gun.getItem(), gun) ? 1 : 0);
        h.assertTrue(loaded == capacity && ConsumptionTests.stock(player, 0) == 100 - capacity,
                "Native reload did not conserve magazine/cache: " + loaded + "/" + ConsumptionTests.stock(player, 0));
        tick.invoke(nativeReload);
        h.assertTrue(ConsumptionTests.stock(player, 0) == 100 - capacity, "Native subsequent tick charged the loaded magazine twice");
        var shot = fire(player, holder);
        h.assertTrue(shot.equals("SUCCESS") && totalLoaded(gun) == capacity - 1 && ConsumptionTests.stock(player, 0) == 100 - capacity,
                "Firing a loaded magazine charged cache again or did not fire: " + shot + "/" + totalLoaded(gun));
        FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD gun={} loaded={} remaining={} side=server-cache", gunId, loaded, ConsumptionTests.stock(player, 0));
        h.succeed();
    }
    private static int magazine(ItemStack gun) throws ReflectiveOperationException {
        return (int)gun.getItem().getClass().getMethod("getCurrentAmmoCount", ItemStack.class).invoke(gun.getItem(), gun);
    }
    static int totalLoaded(ItemStack gun) throws ReflectiveOperationException {
        return magazine(gun) + ((boolean)gun.getItem().getClass().getMethod("hasBulletInBarrel", ItemStack.class).invoke(gun.getItem(), gun) ? 1 : 0);
    }
    private static Object draw(ServerPlayer player) throws ReflectiveOperationException {
        var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        op.getMethod("initialData").invoke(player); op.getMethod("draw", Supplier.class).invoke(player, (Supplier<ItemStack>)player::getMainHandItem);
        var holder = op.getMethod("getDataHolder").invoke(player);
        holder.getClass().getField("drawTimestamp").setLong(holder, System.currentTimeMillis() - 30_000);
        holder.getClass().getField("shootTimestamp").setLong(holder, -30_000); return holder;
    }
    private static String fire(ServerPlayer player, Object holder) throws ReflectiveOperationException {
        holder.getClass().getField("shootTimestamp").setLong(holder, -30_000);
        long time = System.currentTimeMillis() - holder.getClass().getField("baseTimestamp").getLong(holder);
        return Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("shoot", Supplier.class, Supplier.class, long.class)
                .invoke(player, (Supplier<Float>)() -> 0f, (Supplier<Float>)() -> 0f, time).toString();
    }
    private static void inventoryFiring(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "minigun"); var ammo = ammo("308");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun); ConsumptionTests.seed(player, 0, ammo, 3);
        var holder = draw(player);
        player.getInventory().setItem(1, ammo.copyWithCount(1));
        var first = fire(player, holder);
        h.assertTrue(first.equals("SUCCESS") && player.getInventory().getItem(1).isEmpty() && ConsumptionTests.stock(player, 0) == 3,
                "Native inventory-fed first shot did not prefer inventory: " + first);
        var second = fire(player, holder);
        h.assertTrue(second.equals("SUCCESS") && ConsumptionTests.stock(player, 0) == 2,
                "Native inventory-fed second shot did not use cache exactly once: " + second);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        h.assertTrue(fire(player, holder).equals("SUCCESS") && ConsumptionTests.stock(player, 0) == 2, "Native creative firing spent cache");
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        gun.getItem().getClass().getMethod("setDummyAmmoAmount", ItemStack.class, int.class).invoke(gun.getItem(), gun, 5);
        h.assertTrue(fire(player, holder).equals("SUCCESS") && ConsumptionTests.stock(player, 0) == 2
                && (int)gun.getItem().getClass().getMethod("getDummyAmmoAmount", ItemStack.class).invoke(gun.getItem(), gun) == 4,
                "Native dummy-ammo shot consumed cache or changed its own accounting");
        FeedMePackages.LOGGER.info("FMP_TACZ_INVENTORY_FIRE nativeFirst=1 cacheNext=1 creative=free dummy=own side=server-cache"); h.succeed();
    }
    private static void boxAndVariants(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "ak47"); var ammo = ammo("762x39");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var boxApi = Class.forName("com.tacz.guns.api.item.IAmmoBox");
        var box = new ItemStack(BuiltInRegistries.ITEM.stream().filter(boxApi::isInstance).findFirst().orElseThrow());
        boxApi.getMethod("setAmmoId", ItemStack.class, ResourceLocation.class).invoke(box.getItem(), box, ResourceLocation.fromNamespaceAndPath("tacz", "762x39"));
        boxApi.getMethod("setAmmoCount", ItemStack.class, int.class).invoke(box.getItem(), box, 5);
        player.getInventory().setItem(1, box); player.getInventory().setItem(2, ammo.copyWithCount(3));
        ConsumptionTests.seed(player, 0, ammo("9mm"), 10); ConsumptionTests.seed(player, 1, ammo, 4);
        var named = ammo.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact cartridge component"));
        ConsumptionTests.seed(player, 2, named, 4);
        var script = script(player, gun); var consume = script.getClass().getMethod("consumeAmmoFromPlayer", int.class);
        h.assertTrue((int)consume.invoke(script, 10) == 10 && ConsumptionTests.stock(player, 0) == 10
                && ConsumptionTests.stock(player, 1) == 2 && ConsumptionTests.stock(player, 2) == 4,
                "Native box/loose inventory was not exhausted before cache");
        h.assertTrue(player.getInventory().getItem(1) == box && (int)boxApi.getMethod("getAmmoCount", ItemStack.class).invoke(box.getItem(), box) == 0,
                "Native ammo-box component mutation affected a detached copy");
        h.assertTrue((int)consume.invoke(script, 4) == 4 && ConsumptionTests.stock(player, 1) == 0 && ConsumptionTests.stock(player, 2) == 2,
                "Native matching variants were merged, lost or over-consumed");
        var nativeHandler = player.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY);
        var combined = (net.neoforged.neoforge.items.IItemHandler)TaczMaterials.append(player, gun, nativeHandler, 1);
        var preview = combined.extractItem(nativeHandler.getSlots(), 1, true);
        h.assertTrue(ItemStack.isSameItemSameComponents(preview, named) && ConsumptionTests.stock(player, 2) == 2,
                "Cached cartridge components were not preserved at extraction");
        h.assertTrue((int)consume.invoke(script, 99) == 2, "Short source invented additional ammunition");
        var cachedBox = box.copy(); boxApi.getMethod("setAmmoId", ItemStack.class, ResourceLocation.class).invoke(cachedBox.getItem(), cachedBox, ResourceLocation.fromNamespaceAndPath("tacz", "762x39"));
        boxApi.getMethod("setAmmoCount", ItemStack.class, int.class).invoke(cachedBox.getItem(), cachedBox, 5);
        ConsumptionTests.seed(player, 3, cachedBox, 1);
        h.assertTrue((int)consume.invoke(script, 5) == 0 && ConsumptionTests.stock(player, 3) == 1, "Cached box was exposed as a mutable detached ammo source"); h.succeed();
    }
    private static void partialReload(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "m870"); var ammo = ammo("12g");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun); ConsumptionTests.seed(player, 0, ammo, 3);
        var holder = draw(player); Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("reload").invoke(player);
        var nativeReload = reloadService(player, holder); var tick = nativeReload.getClass().getMethod("tickReloadState");
        var timestamp = holder.getClass().getField("reloadTimestamp");
        int maxSeen = 0;
        for (int i = 0; i < 600 && timestamp.getLong(holder) >= 0; i++) {
            timestamp.setLong(holder, timestamp.getLong(holder) - 100); tick.invoke(nativeReload);
            int loaded = totalLoaded(gun); maxSeen = Math.max(maxSeen, loaded);
            h.assertTrue(loaded + ConsumptionTests.stock(player, 0) == 3, "Per-shell native script lost or duplicated ammunition");
        }
        h.assertTrue(maxSeen == 3 && ConsumptionTests.stock(player, 0) == 0 && timestamp.getLong(holder) == -1,
                "Per-shell reload did not stop at actual cache shortage: " + maxSeen + "/" + timestamp.getLong(holder));
        h.succeed();
    }
    private static void creativeReload(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "ak47");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun); ConsumptionTests.seed(player, 0, ammo("762x39"), 40);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var holder = draw(player); Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("reload").invoke(player);
        holder.getClass().getField("reloadTimestamp").setLong(holder, System.currentTimeMillis() - 30_000);
        var nativeReload = reloadService(player, holder); nativeReload.getClass().getMethod("tickReloadState").invoke(nativeReload);
        h.assertTrue(totalLoaded(gun) == 30 && ConsumptionTests.stock(player, 0) == 40, "Creative reload did not retain the native free-ammo rule");
        h.succeed();
    }
    private static void shapeGuard(GameTestHelper h) {
        String[] types = {"com.tacz.guns.api.item.gun.AbstractGunItem", "com.tacz.guns.item.ModernKineticGunScriptAPI", "com.tacz.guns.entity.shooter.LivingEntityShoot"};
        String[][] ports = {{"canReload", "hasInventoryAmmo"}, {"hasAmmoToConsume", "consumeAmmoFromPlayer"}, {"consumeAmmoFromPlayer"}};
        var nodes = Arrays.stream(types).map(TaczTests::bytecode).toArray(org.objectweb.asm.tree.ClassNode[]::new);
        h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.hasTaczPorts(nodes[0], nodes[1], nodes[2]), "Actual native bytecode failed the complete shape guard");
        for (int i = 0; i < types.length; i++) for (var methodName : ports[i]) {
            var altered = bytecode(types[i]); boolean removed = false;
            for (var method : altered.methods) if (method.name.equals(methodName)) for (var instruction : method.instructions.toArray())
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("getCapability")) { method.instructions.remove(call); removed = true; }
            h.assertTrue(removed, "Shape counterexample failed to remove its actual native invocation");
            var original = nodes[i]; nodes[i] = altered;
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasTaczPorts(nodes[0], nodes[1], nodes[2]), "Partial TaCZ adapter accepted missing port " + methodName);
            nodes[i] = original;
        }
        var hud = bytecode("com.tacz.guns.client.gui.overlay.GunHudOverlay");
        var clientShoot = bytecode("com.tacz.guns.client.gameplay.LocalPlayerShoot");
        h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.hasTaczClientPorts(hud, clientShoot), "Native client quantity ports failed shape guard");
        for (String method : List.of("handleCacheCount", "render")) {
            var missing = bytecode("com.tacz.guns.client.gui.overlay.GunHudOverlay"); missing.methods.removeIf(m -> m.name.equals(method));
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasTaczClientPorts(missing, clientShoot), "Missing HUD port remained enabled: " + method);
        }
        clientShoot.methods.removeIf(m -> m.name.equals("doShoot"));
        h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasTaczClientPorts(hud, clientShoot), "Missing client scheduler port remained enabled");
        h.succeed();
    }
    private static Object gunData(String id) throws ReflectiveOperationException {
        var index = ((Optional<?>)Class.forName("com.tacz.guns.api.TimelessAPI").getMethod("getCommonGunIndex", ResourceLocation.class)
                .invoke(null, ResourceLocation.fromNamespaceAndPath("tacz", id))).orElseThrow();
        return index.getClass().getMethod("getGunData").invoke(index);
    }
    private static void infiniteReload(GameTestHelper h) throws ReflectiveOperationException {
        for (String id : List.of("ak47", "m249")) {
            var data = gunData(id); var reload = data.getClass().getMethod("getReloadData").invoke(data);
            var infinite = reload.getClass().getDeclaredField("infinite"); infinite.setAccessible(true); boolean previous = infinite.getBoolean(reload);
            try {
                // In-memory native gun-pack option; restore synchronously so other fixtures never see it.
                infinite.setBoolean(reload, true);
                var nativeResult = reloadInfiniteOption(h, id, false);
                var cachedResult = reloadInfiniteOption(h, id, true);
                h.assertTrue(nativeResult.equals(cachedResult), "Infinite-option cached reload diverged from ordinary inventory: " + id + "/" + nativeResult + "/" + cachedResult);
                int expected = id.equals("ak47") ? 30 : 75;
                h.assertTrue(cachedResult.loaded == expected && cachedResult.afterShotLoaded == expected - 1
                                && cachedResult.source == cachedResult.afterShotSource, "Infinite-option magazine or shot count mismatch: " + cachedResult);
                if (id.equals("m249")) h.assertTrue(cachedResult.source == 100, "Default native infinite reload spent cache");
                FeedMePackages.LOGGER.info("FMP_TACZ_INFINITE_OPTION gun={} nativeEqualsCache=true outcome={}", id, cachedResult);
            } finally { infinite.setBoolean(reload, previous); }
        }
        h.succeed();
    }
    private static CanceledOutcome reloadInfiniteOption(GameTestHelper h, String id, boolean cached) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, id); player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var ammo = ammo(id.equals("ak47") ? "762x39" : "556x45");
        if (cached) ConsumptionTests.seed(player, 0, ammo, 100);
        else { player.getInventory().setItem(1, ammo.copyWithCount(60)); player.getInventory().setItem(2, ammo.copyWithCount(40)); }
        var holder = draw(player); Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("reload").invoke(player);
        var nativeReload = reloadService(player, holder);
        holder.getClass().getField("reloadTimestamp").setLong(holder, System.currentTimeMillis() - 30_000);
        nativeReload.getClass().getMethod("tickReloadState").invoke(nativeReload);
        long source = cached ? ConsumptionTests.stock(player, 0) : player.getInventory().getItem(1).getCount() + player.getInventory().getItem(2).getCount();
        int loaded = totalLoaded(gun);
        h.assertTrue(fire(player, holder).equals("SUCCESS"), "Native infinite-option gun did not shoot");
        long remaining = cached ? ConsumptionTests.stock(player, 0) : player.getInventory().getItem(1).getCount() + player.getInventory().getItem(2).getCount();
        return new CanceledOutcome(source, loaded, remaining, totalLoaded(gun));
    }
    private record CanceledOutcome(long source, int loaded, long afterShotSource, int afterShotLoaded) {}
    private static void canceledShoot(GameTestHelper h) throws ReflectiveOperationException {
        for (String id : List.of("minigun", "ak47")) {
            var data = gunData(id); var reload = data.getClass().getMethod("getReloadData").invoke(data);
            var type = reload.getClass().getDeclaredField("type"); type.setAccessible(true); var previous = type.get(reload);
            try {
                // Exercise both native bolt modes with native inventory feed, without changing shipped gun packs.
                type.set(reload, Class.forName("com.tacz.guns.resource.pojo.data.gun.FeedType").getField("INVENTORY").get(null));
                var nativeResult = cancelShot(h, id, false); var cachedResult = cancelShot(h, id, true);
                h.assertTrue(nativeResult.equals(cachedResult), "Canceled cached shot diverged from native inventory: " + id + "/" + nativeResult + "/" + cachedResult);
                h.assertTrue(cachedResult.source + cachedResult.loaded == 3 && cachedResult.afterShotSource + cachedResult.afterShotLoaded == 2,
                        "Canceled/resumed shot lost or duplicated a chambered cartridge: " + cachedResult);
                FeedMePackages.LOGGER.info("FMP_TACZ_CANCELED_SHOT gun={} nativeEqualsCache={} outcome={}", id, true, cachedResult);
            } finally { type.set(reload, previous); }
        }
        h.succeed();
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CanceledOutcome cancelShot(GameTestHelper h, String id, boolean cached) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, id); player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var ammo = ammo(id.equals("ak47") ? "762x39" : "308");
        if (cached) ConsumptionTests.seed(player, 0, ammo, 3); else player.getInventory().setItem(1, ammo.copyWithCount(3));
        var holder = draw(player); var type = Class.forName("com.tacz.guns.api.event.common.GunShootEvent");
        int[] seen = {0};
        java.util.function.Consumer<net.neoforged.bus.api.Event> listener = event -> {
            try { if (type.getMethod("getShooter").invoke(event) == player) { seen[0]++; ((net.neoforged.bus.api.ICancellableEvent)event).setCanceled(true); } }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.NORMAL, false, (Class)type, listener);
        try {
            h.assertTrue(fire(player, holder).equals("FORGE_EVENT_CANCEL") && fire(player, holder).equals("FORGE_EVENT_CANCEL") && seen[0] == 2,
                    "Native cancellation did not reach both real shot events");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        long remaining = cached ? ConsumptionTests.stock(player, 0) : player.getInventory().getItem(1).getCount(); int loaded = totalLoaded(gun);
        h.assertTrue(fire(player, holder).equals("SUCCESS"), "Shot did not resume after native listener removal");
        return new CanceledOutcome(remaining, loaded, cached ? ConsumptionTests.stock(player, 0) : player.getInventory().getItem(1).getCount(), totalLoaded(gun));
    }
    private static org.objectweb.asm.tree.ClassNode bytecode(String name) {
        try (var stream = TaczTests.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new IllegalStateException("Missing actual optional class resource: " + name);
            var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(stream).accept(node, 0); return node;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void canceledReload(GameTestHelper h) throws ReflectiveOperationException {
        var player = player(h); var gun = gun(player, "ak47");
        player.setItemInHand(InteractionHand.MAIN_HAND, gun); ConsumptionTests.seed(player, 0, ammo("762x39"), 30);
        var holder = draw(player); var type = Class.forName("com.tacz.guns.api.event.common.GunReloadEvent");
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.neoforged.bus.api.Event> listener = event -> {
            try {
                if (type.getMethod("getEntity").invoke(event) == player) { seen.incrementAndGet(); ((net.neoforged.bus.api.ICancellableEvent)event).setCanceled(true); }
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.NORMAL, false, (Class)type, listener);
        try { Class.forName("com.tacz.guns.api.entity.IGunOperator").getMethod("reload").invoke(player); }
        finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        h.assertTrue(seen.get() == 1 && ConsumptionTests.stock(player, 0) == 30 && totalLoaded(gun) == 0
                && holder.getClass().getField("reloadTimestamp").getLong(holder) == -1, "Canceled native reload changed ammunition");
        h.succeed();
    }
    static Object reloadService(LivingEntity player, Object holder) throws ReflectiveOperationException {
        var holderType = holder.getClass();
        var drawType = Class.forName("com.tacz.guns.entity.shooter.LivingEntityDrawGun");
        var draw = drawType.getConstructor(LivingEntity.class, holderType).newInstance(player, holder);
        var shootType = Class.forName("com.tacz.guns.entity.shooter.LivingEntityShoot");
        var shoot = shootType.getConstructor(LivingEntity.class, holderType, drawType).newInstance(player, holder, draw);
        return Class.forName("com.tacz.guns.entity.shooter.LivingEntityReload")
                .getConstructor(LivingEntity.class, holderType, drawType, shootType).newInstance(player, holder, draw, shoot);
    }
    static ItemStack gun(ServerPlayer player, String id) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.api.item.builder.GunItemBuilder"); var builder = type.getMethod("create").invoke(null);
        type.getMethod("setId", ResourceLocation.class).invoke(builder, ResourceLocation.fromNamespaceAndPath("tacz", id));
        type.getMethod("setAmmoCount", int.class).invoke(builder, 0);
        type.getMethod("setAmmoInBarrel", boolean.class).invoke(builder, false);
        return (ItemStack)type.getMethod("build", HolderLookup.Provider.class).invoke(builder, player.registryAccess());
    }
    static ItemStack ammo(String id) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.api.item.builder.AmmoItemBuilder"); var builder = type.getMethod("create").invoke(null);
        type.getMethod("setId", ResourceLocation.class).invoke(builder, ResourceLocation.fromNamespaceAndPath("tacz", id));
        return (ItemStack)type.getMethod("build").invoke(builder);
    }
    static Object script(ServerPlayer player, ItemStack gun) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.item.ModernKineticGunScriptAPI"); var script = type.getConstructor().newInstance();
        type.getMethod("setShooter", LivingEntity.class).invoke(script, player);
        type.getMethod("setItemStack", ItemStack.class).invoke(script, gun);
        return script;
    }
}
