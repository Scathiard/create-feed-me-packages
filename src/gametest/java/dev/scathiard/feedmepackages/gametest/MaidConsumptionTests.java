package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.compat.tacz.TaczMaterials;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.IItemHandler;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.*;
import java.util.function.Supplier;

/** Uses real maid pickup, meals, bow task, crossbow loading and TaCZ reload scripts with a full backpack. */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaidConsumptionTests {
    @FunctionalInterface private interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("maid-consumption", "fmp_maid_consumption_" + name, FeedMePackages.MOD_ID + ":empty", 650, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native consumption fixture failed", e); } });
    }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("touhou_little_maid")) return List.of();
        var tests = new ArrayList<>(List.of(test("hand_pickup", MaidConsumptionTests::handPickup),
                test("full_meal_remainder", MaidConsumptionTests::mealRemainder), test("meal_interrupted_reload", MaidConsumptionTests::mealRoundtrip),
                test("full_bow", h -> bow(h, false)), test("infinite_bow", h -> bow(h, true)),
                test("full_crossbow", h -> crossbow(h, false)), test("full_firework", h -> crossbow(h, true)),
                test("query_invalidation", MaidConsumptionTests::queryInvalidation), test("native_food_priority", MaidConsumptionTests::foodPriority),
                test("external_food_priority", MaidConsumptionTests::externalFood), test("native_healing", MaidConsumptionTests::healing)));
        if (ModList.get().isLoaded("tacz")) {
            tests.add(test("tacz_full_partial_cancel", MaidConsumptionTests::taczPartial));
            tests.add(test("tacz_sources_invalidation", MaidConsumptionTests::taczSources));
        }
        return tests;
    }
    static void fill(MaidTests.Fixture f) {
        var inv = MaidCompatibility.inventory(f.maid());
        for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
    }
    private static int count(IItemHandler inv, ItemStack prototype) {
        int total = 0;
        for (int i = 0; i < inv.getSlots(); i++) if (ItemStack.isSameItemSameComponents(inv.getStackInSlot(i), prototype)) total += inv.getStackInSlot(i).getCount();
        return total;
    }
    private static boolean pickup(MaidTests.Fixture f, ItemStack item, boolean simulate) throws ReflectiveOperationException {
        var ground = new ItemEntity(f.maid().level(), f.maid().getX(), f.maid().getY(), f.maid().getZ(), item.copy());
        ground.setNoPickUpDelay(); f.maid().level().addFreshEntity(ground);
        boolean result = (boolean)f.maid().getClass().getMethod("pickupItem", ItemEntity.class, boolean.class).invoke(f.maid(), ground, simulate);
        if (simulate) ground.discard();
        return result;
    }
    private static void handPickup(GameTestHelper h) throws ReflectiveOperationException {
        var iron = new ItemStack(Items.IRON_INGOT); iron.set(DataComponents.CUSTOM_NAME, Component.literal("Pickup exact iron"));
        var f = MaidTests.setup(h, iron, 0); fill(f);
        var sword = new ItemStack(Items.DIAMOND_SWORD); f.maid().setItemInHand(InteractionHand.MAIN_HAND, sword);
        MaidPickup.setEnabled(f.maid(), true);
        h.assertTrue(pickup(f, iron.copyWithCount(5), true) && f.stock() == 0 && f.maid().getOffhandItem().isEmpty(), "Simulation moved hand/cache stock");
        h.assertTrue(pickup(f, iron.copyWithCount(5), false) && f.stock() == 5 && f.maid().getOffhandItem().isEmpty()
                && f.maid().getMainHandItem() == sword, "Full-bag pickup into offhand was missed or moved the old weapon");
        f.maid().setItemInHand(InteractionHand.OFF_HAND, iron.copyWithCount(7));
        h.assertTrue(pickup(f, iron.copyWithCount(3), false) && f.stock() == 8 && f.maid().getOffhandItem().getCount() == 7,
                "Offhand pickup moved previously held stock");
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, iron.copyWithCount(6));
        h.assertTrue(pickup(f, iron.copyWithCount(2), false) && f.stock() == 10
                && f.maid().getMainHandItem().getCount() == 6 && f.maid().getOffhandItem().getCount() == 7, "Main-hand increments were missed");
        var record = f.record(); var edit = record.state().edit(); int capacity = record.state().capacity().groups() * 64;
        edit.insert(0, f.key(), capacity - 12); f.ledger().replace(f.handle(), record.state().revision(), record.withState(edit.finish()));
        h.assertTrue(pickup(f, iron.copyWithCount(5), false) && f.stock() == capacity
                && count(MaidCompatibility.pickupInventory(f.maid()), iron) == 16, "Partial hand intake lost leftovers or moved old stock");
        h.succeed();
    }
    static void meal(LivingEntity maid, ServerLevel level) throws ReflectiveOperationException {
        var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask");
        var start = type.getDeclaredMethod("start", ServerLevel.class, maid.getClass(), long.class); start.setAccessible(true);
        start.invoke(type.getConstructor().newInstance(), level, maid, level.getGameTime());
    }
    private static void mealRemainder(GameTestHelper h) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.MUSHROOM_STEW), 2); fill(f);
        h.assertTrue(f.stock() == 2, "Non-stackable food fixture exceeded its actual cache capacity");
        var sword = new ItemStack(Items.DIAMOND_SWORD); var shield = new ItemStack(Items.SHIELD);
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, sword); f.maid().setItemInHand(InteractionHand.OFF_HAND, shield);
        meal(f.maid(), h.getLevel());
        h.assertTrue(f.maid().isUsingItem() && f.stock() == 1 && f.maid().getOffhandItem().getCount() == 1
                && f.maid().getMainHandItem() == sword && count(MaidCompatibility.inventory(f.maid()), new ItemStack(Items.MUSHROOM_STEW)) == 0,
                "Full-bag meal did not transfer exactly one food to the native hand");
        h.runAfterDelay(45, () -> {
            var bowl = new ItemStack(Items.BOWL);
            int bowls = count(MaidCompatibility.pickupInventory(f.maid()), bowl);
            for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, f.maid().getBoundingBox().inflate(2)))
                if (item.getItem().is(Items.BOWL)) bowls += item.getItem().getCount();
            h.assertTrue(bowls == 1 && f.stock() == 1 && f.maid().getOffhandItem().is(Items.SHIELD)
                    && f.maid().getMainHandItem() == sword && !f.maid().isUsingItem(), "Native full-bag completion lost remainder or original hands");
            h.succeed();
        });
    }
    private static void mealRoundtrip(GameTestHelper h) throws ReflectiveOperationException {
        var bread = new ItemStack(Items.BREAD); bread.set(DataComponents.CUSTOM_NAME, Component.literal("Interrupted meal"));
        var f = MaidTests.setup(h, bread, 10); fill(f);
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        f.maid().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        meal(f.maid(), h.getLevel()); f.maid().stopUsingItem();
        h.assertTrue(f.stock() == 9 && ItemStack.isSameItemSameComponents(f.maid().getOffhandItem(), bread), "Interrupted food vanished or was refunded twice");
        var saved = new CompoundTag(); h.assertTrue(f.maid().save(saved), "Native save rejected food in use");
        f.maid().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.runAfterDelay(2, () -> {
            var maid = (LivingEntity)EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
            h.assertTrue(maid != null && h.getLevel().addFreshEntity(maid), "Native maid failed to reload");
            h.assertTrue(f.stock() == 9 && count(MaidCompatibility.pickupInventory(maid), bread) == 1, "Reload lost or duplicated transferred food");
            try { meal(maid, h.getLevel()); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            h.assertTrue(maid.isUsingItem() && f.stock() == 9, "Resume took another cached food instead of the held one");
            h.runAfterDelay(45, () -> {
                h.assertTrue(!maid.isUsingItem() && f.stock() == 9 && maid.getOffhandItem().is(Items.SHIELD)
                        && maid.getMainHandItem().is(Items.DIAMOND_SWORD), "Native reload/resume lost original equipment"); h.succeed();
            });
        });
    }
    private static void foodPriority(GameTestHelper h) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.BREAD), 10);
        MaidCompatibility.inventory(f.maid()).setStackInSlot(0, new ItemStack(Items.COOKED_BEEF, 2));
        meal(f.maid(), h.getLevel());
        h.assertTrue(f.maid().isUsingItem() && f.maid().getUseItem().is(Items.COOKED_BEEF) && f.stock() == 10, "Cache bypassed native food priority");
        h.succeed();
    }
    private static void healing(GameTestHelper h) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.BREAD), 10); fill(f); f.maid().setHealth(1);
        var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidHealSelfTask");
        var start = type.getDeclaredMethod("start", ServerLevel.class, f.maid().getClass(), long.class); start.setAccessible(true);
        start.invoke(type.getConstructor().newInstance(), h.getLevel(), f.maid(), h.getLevel().getGameTime());
        h.assertTrue(f.maid().isUsingItem() && f.maid().getHealth() > 1 && f.stock() == 9
                && f.maid().getUseItem().getCount() == 1, "Native healing meal did not use one cached food with a full bag");
        h.runAfterDelay(45, () -> { h.assertTrue(!f.maid().isUsingItem() && f.stock() == 9, "Healing completion debited food again"); h.succeed(); });
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void externalFood(GameTestHelper h) throws ReflectiveOperationException {
        var f = MaidTests.setup(h, new ItemStack(Items.BREAD), 10);
        var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent");
        java.util.function.Consumer<net.neoforged.bus.api.Event> listener = event -> {
            try {
                if (type.isInstance(event) && type.getMethod("getMaid").invoke(event) == f.maid()) {
                    var food = new ItemStack(Items.COOKED_BEEF);
                    MaidCompatibility.inventory(f.maid()).setStackInSlot(0, food);
                    type.getMethod("setRequestedItem", ItemStack.class).invoke(event, food);
                }
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, (Class)type, listener);
        try {
            meal(f.maid(), h.getLevel());
            h.assertTrue(f.maid().isUsingItem() && f.maid().getUseItem().is(Items.COOKED_BEEF) && f.stock() == 10,
                    "Scoped meal source suppressed or bypassed another native shortage-event provider");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        h.succeed();
    }
    private static void bow(GameTestHelper h, boolean infinite) throws ReflectiveOperationException {
        var arrow = new ItemStack(Items.SPECTRAL_ARROW);
        arrow.set(DataComponents.CUSTOM_NAME, Component.literal("Maid exact bow arrow"));
        var f = MaidTests.setup(h, arrow, 4); fill(f); var bow = new ItemStack(Items.BOW);
        if (infinite) bow.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY), 1);
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, bow);
        var task = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskBowAttack").getConstructor().newInstance();
        var hasArrow = task.getClass().getDeclaredMethod("hasArrow", f.maid().getClass()); hasArrow.setAccessible(true);
        for (int i = 0; i < 10; i++) h.assertTrue((boolean)hasArrow.invoke(task, f.maid()) && f.stock() == 4, "Bow query consumed cache");
        var target = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 3));
        task.getClass().getMethod("performRangedAttack", f.maid().getClass(), LivingEntity.class, float.class).invoke(task, f.maid(), target, 1f);
        h.assertTrue(f.stock() == (infinite ? 4 : 3) && bow.getDamageValue() == 1
                && count(MaidCompatibility.inventory(f.maid()), arrow) == 0, "Full-bag native bow or infinity accounting failed");
        h.succeed();
    }
    private static void crossbow(GameTestHelper h, boolean firework) throws ReflectiveOperationException {
        var ammo = new ItemStack(firework ? Items.FIREWORK_ROCKET : Items.SPECTRAL_ARROW);
        ammo.set(DataComponents.CUSTOM_NAME, Component.literal("Maid exact crossbow ammunition"));
        var f = MaidTests.setup(h, ammo, 4); fill(f); var weapon = new ItemStack(Items.CROSSBOW);
        weapon.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MULTISHOT), 1);
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, weapon);
        var task = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskCrossBowAttack").getConstructor().newInstance();
        var hasArrow = task.getClass().getDeclaredMethod("hasArrow", f.maid().getClass()); hasArrow.setAccessible(true);
        h.assertTrue((boolean)hasArrow.invoke(task, f.maid()) && f.stock() == 4, "Native crossbow AI failed to see cached ammo");
        weapon.getItem().releaseUsing(weapon, h.getLevel(), f.maid(), weapon.getUseDuration(f.maid()) - 5);
        h.assertTrue(!CrossbowItem.isCharged(weapon) && f.stock() == 4, "Interrupted crossbow load spent ammo");
        weapon.getItem().releaseUsing(weapon, h.getLevel(), f.maid(), weapon.getUseDuration(f.maid()) - 30);
        var charged = weapon.get(DataComponents.CHARGED_PROJECTILES);
        h.assertTrue(charged != null && charged.getItems().size() == 3 && f.stock() == 3
                && ItemStack.isSameItemSameComponents(charged.getItems().getFirst(), ammo), "Native maid multishot load lost exact components or charged more than once");
        CuriosApi.getCuriosInventory(f.maid()).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, ItemStack.EMPTY);
        var target = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 3));
        ((CrossbowItem)weapon.getItem()).performShooting(h.getLevel(), f.maid(), InteractionHand.MAIN_HAND, weapon, 3.15f, 1, target);
        h.assertTrue(!CrossbowItem.isCharged(weapon) && f.stock() == 3, "Removing pendant/fire refunded or charged loaded ammo twice"); h.succeed();
    }
    private static void queryInvalidation(GameTestHelper h) {
        var f = MaidTests.setup(h, new ItemStack(Items.ARROW), 4); fill(f); var weapon = new ItemStack(Items.CROSSBOW);
        f.maid().setItemInHand(InteractionHand.MAIN_HAND, weapon);
        var query = f.maid().getProjectile(weapon);
        h.assertTrue(!query.isEmpty() && f.stock() == 4, "Native projectile query staged or consumed stock");
        var slots = CuriosApi.getCuriosInventory(f.maid()).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
        slots.setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        h.assertTrue(ProbeWeapon.drawNative(weapon, query, f.maid()).isEmpty() && f.stock() == 4, "Old projectile query spent the swapped pendant");
        slots.setStackInSlot(0, f.pendant()); query = f.maid().getProjectile(weapon);
        var record = f.record(); var edit = record.state().edit(); edit.extract(0, 4);
        f.ledger().replace(f.handle(), record.state().revision(), record.withState(edit.finish()));
        h.assertTrue(ProbeWeapon.drawNative(weapon, query, f.maid()).isEmpty() && f.stock() == 0, "Concurrent withdrawal manufactured cached projectiles"); h.succeed();
    }
    private static final class ProbeWeapon extends ProjectileWeaponItem {
        ProbeWeapon() { super(new Item.Properties()); }
        public java.util.function.Predicate<ItemStack> getAllSupportedProjectiles() { return ARROW_ONLY; }
        public int getDefaultProjectileRange() { return 15; }
        protected void shootProjectile(LivingEntity shooter, net.minecraft.world.entity.projectile.Projectile projectile, int index, float velocity, float accuracy, float angle, LivingEntity target) {}
        static List<ItemStack> drawNative(ItemStack weapon, ItemStack ammo, LivingEntity actor) { return draw(weapon, ammo, actor); }
    }
    private static Object draw(LivingEntity maid) throws ReflectiveOperationException {
        var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        op.getMethod("initialData").invoke(maid); op.getMethod("draw", Supplier.class).invoke(maid, (Supplier<ItemStack>)maid::getMainHandItem);
        var holder = op.getMethod("getDataHolder").invoke(maid);
        holder.getClass().getField("drawTimestamp").setLong(holder, -1); holder.getClass().getField("shootTimestamp").setLong(holder, -30_000);
        return holder;
    }
    private static void taczPartial(GameTestHelper h) throws ReflectiveOperationException {
        var ammo = TaczTests.ammo("12g"); var f = MaidTests.setup(h, ammo, 10); fill(f);
        var gun = TaczTests.gun(f.owner(), "m870"); f.maid().setItemInHand(InteractionHand.MAIN_HAND, gun);
        var holder = draw(f.maid()); var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        op.getMethod("reload").invoke(f.maid());
        var nativeReload = TaczTests.reloadService(f.maid(), holder); var tick = nativeReload.getClass().getMethod("tickReloadState");
        var timestamp = holder.getClass().getField("reloadTimestamp");
        for (int i = 0; i < 600 && TaczTests.totalLoaded(gun) < 2 && timestamp.getLong(holder) >= 0; i++) {
            timestamp.setLong(holder, timestamp.getLong(holder) - 100); tick.invoke(nativeReload);
            h.assertTrue(TaczTests.totalLoaded(gun) + f.stock() == 10, "Per-shell maid reload lost or duplicated ammo");
        }
        int loaded = TaczTests.totalLoaded(gun); op.getMethod("cancelReload").invoke(f.maid());
        h.assertTrue(loaded >= 2 && f.stock() == 10 - loaded && count(MaidCompatibility.inventory(f.maid()), ammo) == 0,
                "Full-bag per-shell cancellation staged ammo or refunded loaded rounds"); h.succeed();
    }
    private static void taczSources(GameTestHelper h) throws ReflectiveOperationException {
        var ammo = TaczTests.ammo("762x39"); int size = ammo.getMaxStackSize(), extra = Math.min(26, size / 2), reserve = Math.min(10, size - extra);
        int requested = size + extra, total = requested + reserve;
        var f = MaidTests.setup(h, ammo, total); fill(f);
        h.assertTrue(f.stock() == total, "TaCZ segment fixture exceeded its native-stack cache capacity");
        var gun = TaczTests.gun(f.owner(), "ak47"); f.maid().setItemInHand(InteractionHand.MAIN_HAND, gun);
        var inv = MaidCompatibility.inventory(f.maid()); var source = (IItemHandler)TaczMaterials.append(f.maid(), gun, inv, requested);
        int slot = inv.getSlots();
        h.assertTrue(source.getSlots() > slot && source.extractItem(slot, 32, true).getCount() == Math.min(32, size) && f.stock() == total,
                "Simulating maid TaCZ extraction consumed stock");
        var slots = CuriosApi.getCuriosInventory(f.maid()).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks(); slots.setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(source.extractItem(slot, 32, false).isEmpty() && f.stock() == total, "Detached TaCZ source spent after unequip");
        slots.setStackInSlot(0, f.pendant());
        source = (IItemHandler)TaczMaterials.append(f.maid(), gun, inv, requested);
        h.assertTrue(source.extractItem(slot, requested, false).getCount() == size && source.extractItem(slot + 1, requested, false).getCount() == extra
                && source.extractItem(slot, 1, false).isEmpty() && f.stock() == reserve, "Large native request duplicated a segment or exceeded its demand");
        FeedMePackages.LOGGER.info("FMP_MAID_TACZ_SEGMENTS stackSize={} requested={} remaining={}", size, requested, reserve); h.succeed();
    }
}
