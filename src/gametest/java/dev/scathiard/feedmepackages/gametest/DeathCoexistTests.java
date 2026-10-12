package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.logistics.ParcelSeal;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ParcelAuthentication;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
public final class DeathCoexistTests {
    @GameTestGenerator public static Collection<TestFunction> scenarios() {
        var tests = new ArrayList<TestFunction>();
        for (boolean personal : List.of(false)) {
            for (String rule : List.of("ON", "OFF")) for (boolean keep : List.of(false, true)) {
                tests.add(test("curios_" + rule + "_keep" + keep + "_personal" + personal,
                        h -> curios(h, personal, rule, keep)));
            }
            if (List.of("gravestone", "corpse", "tombstone").stream().anyMatch(ModList.get()::isLoaded))
                for (boolean occupied : List.of(false, true)) tests.add(test("grave_full_inventory_personal" + personal + "_occupied" + occupied,
                        h -> grave(h, personal, occupied)));
        }
        if (ModList.get().isLoaded("tombstone")) {
            tests.add(test("corail_experience_respawn", DeathCoexistTests::experience));
            tests.add(test("corail_curio_slot_coexist", DeathCoexistTests::corailCurio));
        }
        return tests;
    }

    private static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> action) {
        return new TestFunction("deathcoexist", "fmp_" + name, FeedMePackages.MOD_ID + ":empty", 100, 0, true, action);
    }

    @SuppressWarnings("unchecked")
    private static ModConfigSpec.ConfigValue<Object> keepCurios() {
        try {
            var config = Class.forName("top.theillusivec4.curios.common.CuriosConfig").getField("SERVER").get(null);
            return (ModConfigSpec.ConfigValue<Object>) config.getClass().getField("keepCurios").get(config);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }

    private static void curios(GameTestHelper h, boolean personal, String rule, boolean keep) {
        var config = keepCurios(); Object old = config.get(); var rules = h.getLevel().getGameRules();
        boolean oldKeep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        try {
            config.set(Arrays.stream(old.getClass().getEnumConstants()).filter(v -> v.toString().equals(rule)).findFirst().orElseThrow());
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keep, h.getLevel().getServer());
            var p = TestPlayers.real(h, UUID.randomUUID());
            var cosmetic = CuriosApi.getCuriosInventory(p).orElseThrow().getStacksHandler("necklace").orElseThrow().getCosmeticStacks();
            var ornamental = FmpRegistries.PENDANT.toStack(); var decorativeMarker = UUID.randomUUID();
            ornamental.set(FmpRegistries.NETWORK.get(), decorativeMarker); cosmetic.setStackInSlot(0, ornamental);
            h.assertTrue(!AccessGate.resolve(p).active(), "Cosmetic pendant granted cache authority");
            var pendant = FmpRegistries.PENDANT.toStack();
            var marker = UUID.randomUUID(); pendant.set(FmpRegistries.NETWORK.get(), marker);
            TestPlayers.necklace(p).setStackInSlot(0, pendant);
            var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer());
            h.assertTrue(handle != null, "Cosmetic pendant conflicted with functional pendant");
            ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 120);
            var before = FoundationTests.row(ledger, handle.cacheId(), p.registryAccess());
            p.setHealth(0); p.die(p.damageSources().generic());
            h.assertTrue(!AccessGate.resolve(p).active(), "Dead Curios owner retained cache access");
            h.assertTrue(FoundationTests.row(ledger, handle.cacheId(), p.registryAccess()).equals(before), "Curios death changed cache data");
            // Curios deliberately allows OFF to override vanilla keepInventory, and ON to retain both slot kinds.
            int expectedLoot = rule.equals("ON") ? 0 : 1;
            h.assertTrue(countMarker(DeathRecovery.loot(p), marker) == expectedLoot
                    && countMarker(DeathRecovery.loot(p), decorativeMarker) == expectedLoot,
                    "Curios drop policy lost or duplicated a slot: functional=" + countMarker(DeathRecovery.loot(p), marker)
                            + " cosmetic=" + countMarker(DeathRecovery.loot(p), decorativeMarker) + diagnostic(p));
            var revived = DeathRecovery.respawn(h, p);
            h.assertTrue(AccessGate.resolve(revived).active() == rule.equals("ON"), "Curios native clone ignored configured retention");
            if (expectedLoot != 0) {
                DeathRecovery.recover(revived); pickup(revived, marker, decorativeMarker); equipMarked(revived, marker);
            }
            h.assertTrue(AccessGate.resolve(revived).active() && AccessGate.resolve(revived).handle().cacheId().equals(handle.cacheId())
                    && FoundationTests.row(ledger, handle.cacheId(), revived.registryAccess()).equals(before), "Curios recovery selected or changed cache");
            h.assertTrue(countMarker(all(revived), marker) == 1 && countMarker(all(revived), decorativeMarker) == 1,
                    "Curios recovery duplicated or swallowed a pendant: functional=" + countMarker(all(revived), marker)
                            + " cosmetic=" + countMarker(all(revived), decorativeMarker) + diagnostic(revived));
            FeedMePackages.LOGGER.info("FMP_CURIOS_DEATH_PASSED personal={} rule={} vanillaKeep={}", personal, rule, keep);
        } finally { config.set(old); rules.getRule(GameRules.RULE_KEEPINVENTORY).set(oldKeep, h.getLevel().getServer()); }
        h.succeed();
    }

    private static void grave(GameTestHelper h, boolean personal, boolean occupied) {
        var rules = h.getLevel().getGameRules(); boolean oldKeep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        try {
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(false, h.getLevel().getServer());
            var p = TestPlayers.real(h, UUID.randomUUID()); var marker = UUID.randomUUID();
            var pendant = FmpRegistries.PENDANT.toStack();
            pendant.set(FmpRegistries.NETWORK.get(), marker); TestPlayers.necklace(p).setStackInSlot(0, pendant);
            var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer());
            ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 96);
            var record = ledger.find(handle.cacheId()); var edit = record.state().edit(); var request = UUID.randomUUID();
            edit.thresholds(0, 2, -1); edit.request(0, request, p.getUUID(), 32, false);
            ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
            var key = ItemVariantKey.of(new ItemStack(Items.STONE), p.registryAccess());
            var parcel = PackageItem.containing(List.of(new ItemStack(Items.STONE, 32))); var address = SupplyService.address(p);
            PackageItem.addAddress(parcel, address);
            ParcelAuthentication.apply(ledger, p.registryAccess(), parcel, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), p.getUUID(), request,
                    key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
            String prefix = "grave-" + p.getUUID();
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, named(Items.DIORITE, i + 1, prefix + ":" + i));
            p.getInventory().setItem(10, parcel);
            p.getInventory().armor.set(0, named(Items.IRON_BOOTS, 1, prefix + ":boots"));
            p.getInventory().offhand.set(0, named(Items.GOLDEN_APPLE, 3, prefix + ":offhand"));
            var expected = tagged(all(p), prefix);
            var before = FoundationTests.row(ledger, handle.cacheId(), p.registryAccess());
            p.setHealth(0); p.die(p.damageSources().generic());
            h.assertTrue(FoundationTests.row(ledger, handle.cacheId(), p.registryAccess()).equals(before), "Grave capture changed cache ledger");
            h.assertTrue(!DeathRecovery.storedItems(p).isEmpty(), "Installed grave did not capture native death");
            h.assertTrue(tagged(all(p), prefix).equals(expected) && countMarker(all(p), marker) == 1,
                    "Death capture lost or copied exact inventory variants");
            var revived = DeathRecovery.respawn(h, p);
            if (occupied) for (int i = 0; i < 36; i++) {
                var stack = named(Items.COBBLESTONE, 64, prefix + ":new" + i);
                revived.getInventory().setItem(i, stack); expected.put(new Cargo(stack.getItem(), stack.getComponents()), 64);
            }
            DeathRecovery.recover(revived);
            h.assertTrue(tagged(all(revived), prefix).equals(expected), "Native grave retrieval lost or duplicated overflow/equipment");
            // Free one occupied slot by a real drop; full-backpack conservation still includes that ground stack.
            if (TestPlayers.necklace(revived).getStackInSlot(0).isEmpty() && inventoryMarked(revived, marker) < 0) {
                var stack = revived.getInventory().removeItemNoUpdate(0); if (!stack.isEmpty()) revived.drop(stack, false);
                DeathRecovery.takePendant(revived, marker);
                for (var entity : DeathRecovery.drops(revived)) if (marker.equals(entity.getItem().get(FmpRegistries.NETWORK.get()))) {
                    entity.setNoPickUpDelay(); entity.playerTouch(revived);
                }
            }
            equipMarked(revived, marker);
            h.assertTrue(AccessGate.resolve(revived).active() && AccessGate.resolve(revived).handle().cacheId().equals(handle.cacheId()),
                    "Recovered pendant changed cache ownership");
            // Create may defer replacing an overflowing ItemEntity with its PackageEntity until the next tick.
            h.runAfterDelay(2, () -> {
                long physicalCargo = 0;
                for (var stack : all(revived)) if (stack.has(FmpRegistries.PARCEL_SEAL.get())) {
                    var seal = stack.get(FmpRegistries.PARCEL_SEAL.get());
                    if (!seal.playerId().equals(revived.getUUID())) continue;
                    var contents = PackageItem.getContents(stack);
                    for (int i = 0; i < contents.getSlots(); i++) physicalCargo += contents.getStackInSlot(i).getCount();
                }
                h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() + physicalCargo == 128,
                        "Recovered parcel cargo is not conserved across native entity conversion" + diagnostic(revived));
                // Registered production player-tick path must receive a parcel restored by native direct slot writes.
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(revived)); pickup(revived, marker);
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(revived));
                h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 128,
                        "Restored signed parcel did not reach its original cache exactly once: stock="
                                + ledger.find(handle.cacheId()).state().cells().getFirst().amount() + diagnostic(revived));
                h.assertTrue(tagged(all(revived), prefix).equals(expected) && countMarker(all(revived), marker) == 1,
                        "Automatic receive changed unrelated grave cargo or swallowed pendant");
                DeathRecovery.recover(revived); pickup(revived, marker); NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(revived));
                h.assertTrue(ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 128
                        && tagged(all(revived), prefix).equals(expected) && countMarker(all(revived), marker) == 1,
                        "Repeated native recovery duplicated cargo");
                FeedMePackages.LOGGER.info("FMP_GRAVE_RECOVERY_PASSED personal={} occupied={} cache=128", personal, occupied);
                h.succeed();
            });
        } finally { rules.getRule(GameRules.RULE_KEEPINVENTORY).set(oldKeep, h.getLevel().getServer()); }
    }

    private static ItemStack named(net.minecraft.world.item.Item item, int count, String name) {
        var stack = new ItemStack(item, count); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }
    private static void corailCurio(GameTestHelper h) {
        try {
            var player = TestPlayers.real(h, UUID.randomUUID());
            var original = FmpRegistries.PENDANT.toStack(); var marker = UUID.randomUUID();
            original.set(FmpRegistries.NETWORK.get(), marker);
            TestPlayers.necklace(player).setStackInSlot(0, original);
            var handle = AccessGate.resolve(player).handle();
            var rival = FmpRegistries.PENDANT.toStack(); rival.set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
            var incoming = new net.neoforged.neoforge.items.ItemStackHandler(1);
            incoming.setStackInSlot(0, rival);
            var type = Class.forName("ovh.corail.tombstone.compatibility.CompatibilityCurio");
            var compatibility = type.getField("instance").get(null);
            var autoEquip = type.getMethod("autoEquip", ServerPlayer.class,
                    net.neoforged.neoforge.items.ItemStackHandler.class, List.class);
            autoEquip.invoke(compatibility, player, incoming, new ArrayList<>(List.of(0)));
            h.assertTrue(marker.equals(TestPlayers.necklace(player).getStackInSlot(0).get(FmpRegistries.NETWORK.get()))
                            && ItemStack.matches(incoming.getStackInSlot(0), rival)
                            && AccessGate.resolve(player).handle().cacheId().equals(handle.cacheId()),
                    "Corail auto-equip overwrote an occupied FMP necklace slot");
            var ownTags = new TreeMap<String, Set<String>>();
            for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                if (!id.getNamespace().equals("tombstone")) continue;
                var tags = CuriosApi.getCuriosHelper().getCurioTags(item);
                if (!tags.isEmpty()) ownTags.put(id.toString(), new TreeSet<>(tags));
            }
            FeedMePackages.LOGGER.info("FMP_CORAIL_CURIO_SLOT_PASSED occupiedNecklacePreserved=true ownCurioTags={}", ownTags);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        h.succeed();
    }
    private static void experience(GameTestHelper h) {
        var rules = h.getLevel().getGameRules(); boolean oldKeep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        try {
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(false, h.getLevel().getServer());
            var p = TestPlayers.real(h, UUID.randomUUID()); TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
            ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 120);
            var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer());
            var before = FoundationTests.row(ledger, handle.cacheId(), p.registryAccess());
            p.giveExperiencePoints(250); int initialXp = p.totalExperience;
            p.setHealth(0); p.die(p.damageSources().generic());
            var tag = (net.minecraft.nbt.CompoundTag) Class.forName("ovh.corail.tombstone.helper.EntityHelper")
                    .getMethod("getPersistentTag", net.minecraft.world.entity.player.Player.class).invoke(null, p);
            h.assertTrue(tag.contains("tb_experience_total"), "Corail did not record its native experience policy");
            int expected = tag.getInt("tb_experience_total"), level = tag.getInt("tb_experience_level");
            float progress = tag.getFloat("tb_experience_bar");
            var revived = DeathRecovery.respawn(h, p);
            h.assertTrue(expected > 0 && expected <= initialXp && revived.totalExperience == expected && revived.experienceLevel == level
                    && revived.experienceProgress == progress, "Corail respawn did not restore its exact saved XP");
            DeathRecovery.recover(revived);
            h.assertTrue(revived.totalExperience == expected && FoundationTests.row(ledger, handle.cacheId(), revived.registryAccess()).equals(before),
                    "Grave retrieval granted XP twice or mutated cache");
            FeedMePackages.LOGGER.info("FMP_CORAIL_XP_PASSED granted=250 before={} restored={} level={}", initialXp, expected, level);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        finally { rules.getRule(GameRules.RULE_KEEPINVENTORY).set(oldKeep, h.getLevel().getServer()); }
        h.succeed();
    }
    private static List<ItemStack> all(ServerPlayer p) {
        var stacks = new ArrayList<>(DeathRecovery.loot(p));
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) stacks.add(p.getInventory().getItem(i));
        var curios = CuriosApi.getCuriosInventory(p).orElseThrow().getStacksHandler("necklace").orElseThrow();
        for (var slots : List.of(curios.getStacks(), curios.getCosmeticStacks()))
            for (int i = 0; i < slots.getSlots(); i++) stacks.add(slots.getStackInSlot(i));
        return stacks;
    }
    private record Cargo(net.minecraft.world.item.Item item, net.minecraft.core.component.DataComponentMap components) {}
    private static Map<Cargo, Integer> tagged(List<ItemStack> stacks, String prefix) {
        var counts = new HashMap<Cargo, Integer>();
        for (var stack : stacks) {
            var name = stack.get(DataComponents.CUSTOM_NAME);
            if (!stack.isEmpty() && name != null && name.getString().startsWith(prefix))
                counts.merge(new Cargo(stack.getItem(), stack.getComponents()), stack.getCount(), Integer::sum);
        }
        return counts;
    }
    private static long countMarker(List<ItemStack> stacks, UUID marker) {
        return stacks.stream().filter(s -> marker.equals(s.get(FmpRegistries.NETWORK.get()))).mapToLong(ItemStack::getCount).sum();
    }
    private static int inventoryMarked(ServerPlayer p, UUID marker) {
        for (int i = 0; i < 36; i++) if (marker.equals(p.getInventory().getItem(i).get(FmpRegistries.NETWORK.get()))) return i;
        return -1;
    }
    private static void equipMarked(ServerPlayer p, UUID marker) {
        if (marker.equals(TestPlayers.necklace(p).getStackInSlot(0).get(FmpRegistries.NETWORK.get()))) return;
        int slot = inventoryMarked(p, marker);
        if (slot < 0) throw new IllegalStateException("Recovered pendant is neither worn nor in inventory");
        var requested = p.getInventory().removeItemNoUpdate(slot);
        var displaced = TestPlayers.necklace(p).getStackInSlot(0);
        TestPlayers.necklace(p).setStackInSlot(0, requested);
        // Corail can auto-equip the cosmetic pendant as functional; an intentional swap must conserve it.
        p.getInventory().setItem(slot, displaced);
    }
    private static void pickup(ServerPlayer p, UUID... markers) {
        for (var item : DeathRecovery.drops(p)) {
            var stack = item.getItem(); var name = stack.get(DataComponents.CUSTOM_NAME);
            var seal = stack.get(FmpRegistries.PARCEL_SEAL.get());
            if (Arrays.asList(markers).contains(stack.get(FmpRegistries.NETWORK.get()))
                    || name != null && name.getString().startsWith("grave-" + p.getUUID())
                    || seal != null && seal.playerId().equals(p.getUUID())) {
                item.setNoPickUpDelay(); item.playerTouch(p);
            }
        }
        for (var entity : DeathRecovery.parcels(p)) {
            var seal = entity.getBox().get(FmpRegistries.PARCEL_SEAL.get());
            if (seal != null && seal.playerId().equals(p.getUUID())) {
                // Create's physical box is picked up by an empty hand, not the ItemEntity collision path.
                var held = p.getInventory().removeItemNoUpdate(p.getInventory().selected);
                if (!held.isEmpty()) p.drop(held, false);
                entity.interact(p, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        }
    }
    private static String diagnostic(ServerPlayer p) {
        var relevant = all(p).stream().filter(s -> s.has(FmpRegistries.NETWORK.get()) || s.has(FmpRegistries.PARCEL_SEAL.get()))
                .map(s -> s.getCount() + "x" + s.getItem() + " net=" + s.get(FmpRegistries.NETWORK.get())
                        + " parcel=" + s.has(FmpRegistries.PARCEL_SEAL.get())).toList();
        return " graves=" + DeathRecovery.graves(p).stream().map(b -> b.getClass().getSimpleName() + "@" + b.getBlockPos()).toList()
                + " observed=" + relevant + " curios=" + CuriosApi.getCuriosInventory(p).orElseThrow().findCurios(s ->
                        s.has(FmpRegistries.NETWORK.get())).stream().map(s -> s.slotContext() + " " + s.stack()).toList();
    }
}
