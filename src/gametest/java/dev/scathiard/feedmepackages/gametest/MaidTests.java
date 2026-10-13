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
import net.minecraft.nbt.CompoundTag;
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
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.interaction.PanelAccess;
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
                test("network_permission", MaidTests::permission),
                test("native_panel_access", MaidTests::panelAccess), test("native_panel_revocation", MaidTests::panelRevocation),
                test("pickup_native_deltas", MaidTests::pickup), test("return_rejection_and_escrow", MaidTests::returnSafety)));
        if (ModList.get().isLoaded("tacz")) tests.add(test("native_tacz_reload", MaidTests::tacz));
        if (ModList.get().isLoaded("cmpackagecouriers")) {
            tests.add(test("paper_pickup_return", h -> pickupReturn(h, false)));
            tests.add(test("paper_direct_flight", h -> flight(h, false, false)));
            tests.add(test("paper_swapped_in_flight", h -> flight(h, false, true)));
        }
        if (ModList.get().isLoaded("createphantom")) {
            // End-to-end maid compatibility: a real native courier delivering into a real maid's cache.
            tests.add(test("phantom_delivery_to_maid", MaidTests::phantomDeliveryToMaid));
            // And the way home: the returning carrier must come to her, not to her owner.
            tests.add(test("phantom_return_to_maid", MaidTests::phantomReturnToMaid));
        }
        if (ModList.get().isLoaded("create_mobile_packages")) {
            tests.add(test("bee_pickup_return", h -> pickupReturn(h, true)));
            tests.add(test("bee_direct_flight", h -> flight(h, true, false)));
            tests.add(test("bee_swapped_in_flight", h -> flight(h, true, true)));
            // Transport bee return to its maid (plan B, user 2026-10-13).
            tests.add(test("bee_return_home", MaidTests::beeReturnHome));
            tests.add(test("bee_return_unreachable", MaidTests::beeReturnUnreachable));
            tests.add(test("bee_return_tag_roundtrip", MaidTests::beeReturnTagRoundtrip));
            tests.add(test("bee_return_no_record_native", MaidTests::beeReturnNoRecordLeavesNative));
            tests.add(test("bee_return_item_guard", MaidTests::beeReturnItemGuard));
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
    // ---------------------------------------------------------------------------------------------------
    // Transport bee return to its maid (plan B, user 2026-10-13). The mobile classes are runtime-only inputs,
    // so they are reached reflectively here exactly like the production seam does.
    // ---------------------------------------------------------------------------------------------------
    private static final String BEE_MANAGER = "de.theidler.create_mobile_packages.robo.RoboManager";
    private static final String BEE_ROBO = "de.theidler.create_mobile_packages.robo.VirtualRobo";

    private static Object beeManager(GameTestHelper h) throws ReflectiveOperationException {
        return Class.forName(BEE_MANAGER).getMethod("get", ServerLevel.class).invoke(null, h.getLevel());
    }
    /** A real native robo, created the same way a dispatch creates one, and returned as its live object. */
    private static Object beeRobo(GameTestHelper h, Object manager, ItemStack cargo, BlockPos spawn) throws ReflectiveOperationException {
        UUID id = (UUID) Class.forName(BEE_MANAGER)
                .getMethod("newRobo", ServerLevel.class, ItemStack.class, BlockPos.class, UUID.class, float.class, BlockPos.class, boolean.class)
                .invoke(manager, h.getLevel(), cargo, spawn, UUID.randomUUID(), 0f, null, false);
        return Class.forName(BEE_MANAGER).getMethod("get", UUID.class).invoke(manager, id);
    }
    private static UUID beeId(Object robo) throws ReflectiveOperationException {
        return (UUID) robo.getClass().getMethod("getId").invoke(robo);
    }
    private static boolean beePresent(Object manager, UUID id) throws ReflectiveOperationException {
        return Class.forName(BEE_MANAGER).getMethod("get", UUID.class).invoke(manager, id) != null;
    }
    /** Park the bee exactly where the arrival test needs it: her eye level, i.e. position + 2. */
    private static void beeParkAt(Object robo, TamableAnimal maid) throws ReflectiveOperationException {
        robo.getClass().getMethod("setPos", Vec3.class).invoke(robo, maid.position().add(0, 2, 0));
    }
    private static net.minecraft.world.item.Item beeItem() throws ReflectiveOperationException {
        Object entry = Class.forName("de.theidler.create_mobile_packages.index.CMPItems").getField("ROBO_BEE").get(null);
        return (net.minecraft.world.item.Item) entry.getClass().getMethod("get").invoke(entry);
    }

    /** a + f: the bee comes home, the carrier lands in her own inventory, the bee is removed, and twice is once. */
    private static void beeReturnHome(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.STONE), 0);
        h.assertTrue(MaidCouriers.beeReady(), "Native transport bee endpoint unavailable");
        var manager = beeManager(h);
        var robo = beeRobo(h, manager, ItemStack.EMPTY, f.maid.blockPosition());
        h.assertTrue(robo != null, "Native robo was not created");
        beeParkAt(robo, f.maid);
        var id = beeId(robo);
        MaidReturn.register(id, f.maid.getUUID());
        h.assertTrue(MaidCouriers.beeTarget(robo), "An empty bee with a return record must target its maid");
        var before = count(MaidCompatibility.inventory(f.maid), new ItemStack(beeItem()));
        MaidCouriers.beeArrive(robo);
        h.assertTrue(count(MaidCompatibility.inventory(f.maid), new ItemStack(beeItem())) == before + 1,
                "The returning bee did not hand its item to the maid's inventory; record=" + MaidReturn.maid(id)
                        + " found=" + MaidReturn.find(h.getLevel(), f.maid.getUUID())
                        + " reachable=" + MaidReturn.reachable(f.maid, h.getLevel())
                        + " robo=" + robo.getClass().getMethod("getItemStack").invoke(robo));
        h.assertTrue(!beePresent(manager, id), "The returned bee was not removed");
        MaidCouriers.beeArrive(robo);   // f: the hook is a HEAD injection, so a second call must not hand it over again
        h.assertTrue(count(MaidCompatibility.inventory(f.maid), new ItemStack(beeItem())) == before + 1,
                "A repeated arrival handed the carrier over twice");
        h.succeed();
    }

    /** b: unreachable maid (death here; dimension/unload are not producible in this fixture) => native chain owns it. */
    private static void beeReturnUnreachable(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.STONE), 0);
        h.assertTrue(MaidCouriers.beeReady(), "Native transport bee endpoint unavailable");
        var manager = beeManager(h);
        var robo = beeRobo(h, manager, ItemStack.EMPTY, f.maid.blockPosition());
        beeParkAt(robo, f.maid);
        MaidReturn.register(beeId(robo), f.maid.getUUID());
        h.assertTrue(MaidReturn.reachable(f.maid, h.getLevel()), "A live in-level maid must be reachable");
        h.assertTrue(MaidCouriers.beeTarget(robo), "A reachable maid must become the target");
        f.maid.kill();
        h.assertTrue(!MaidReturn.reachable(f.maid, h.getLevel()), "A dead maid must not be reachable");
        h.assertTrue(MaidReturn.find(h.getLevel(), f.maid.getUUID()) == null, "A dead maid must not be found");
        h.assertTrue(!MaidCouriers.beeTarget(robo), "An unreachable maid must not be targeted - the native chain takes over");
        h.assertTrue(MaidReturn.maid(beeId(robo)) != null, "The return record must not expire while she is away");
        h.succeed();
    }

    /** c: the record rides in the robo's own tag, so a save/reload keeps the same maid. */
    private static void beeReturnTagRoundtrip(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.STONE), 0);
        var manager = beeManager(h);
        var robo = beeRobo(h, manager, ItemStack.EMPTY, f.maid.blockPosition());
        var id = beeId(robo);
        MaidReturn.register(id, f.maid.getUUID());
        var tag = (CompoundTag) robo.getClass().getMethod("serializeNBT").invoke(robo);
        h.assertTrue(tag.hasUUID(MaidReturn.TAG), "The robo tag did not carry the FMP return key");
        h.assertTrue(f.maid.getUUID().equals(tag.getUUID(MaidReturn.TAG)), "The robo tag names the wrong maid");
        MaidReturn.forget(id);
        h.assertTrue(MaidReturn.maid(id) == null, "forget did not clear the record");
        var restored = Class.forName(BEE_ROBO).getMethod("deserializeNBT", ServerLevel.class, CompoundTag.class)
                .invoke(null, h.getLevel(), tag);
        h.assertTrue(f.maid.getUUID().equals(MaidReturn.maid(beeId(restored))),
                "A reload did not restore the return record for the same maid");
        h.succeed();
    }

    /** d: a bee without an FMP record keeps exactly the native target chain (ports own the return leg). */
    private static void beeReturnNoRecordLeavesNative(GameTestHelper h) throws ReflectiveOperationException {
        var f = setup(h, new ItemStack(Items.STONE), 0);
        h.assertTrue(MaidCouriers.beeReady(), "Native transport bee endpoint unavailable");
        var manager = beeManager(h);
        var robo = beeRobo(h, manager, ItemStack.EMPTY, f.maid.blockPosition());
        beeParkAt(robo, f.maid);
        h.assertTrue(!MaidCouriers.beeTarget(robo), "A bee without an FMP record must stay on the native chain");
        h.succeed();
    }

    /** e: the loss guard itself - a return may never end without a carrier item to hand over. */
    private static void beeReturnItemGuard(GameTestHelper h) {
        h.assertTrue(!ReturnDecision.mayEnd(true, true, false), "A return must not end without a resolvable carrier item");
        h.assertTrue(!ReturnDecision.mayEnd(true, false, true), "A return must not end while the maid is unreachable");
        h.assertTrue(!ReturnDecision.mayEnd(false, true, true), "A bee without a record is not ours to end");
        h.assertTrue(ReturnDecision.mayEnd(true, true, true), "All three conditions must allow the return to end");
        h.succeed();
    }

    private static net.minecraft.world.inventory.AbstractContainerMenu panelMenu(ServerPlayer player, TamableAnimal maid, int id) throws ReflectiveOperationException {
        return (net.minecraft.world.inventory.AbstractContainerMenu)Class.forName(
                "com.github.tartaricacid.touhoulittlemaid.compat.curios.menu.CuriosContainer")
                .getConstructor(int.class,net.minecraft.world.entity.player.Inventory.class,int.class)
                .newInstance(id,player.getInventory(),maid.getId());
    }
    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> onlineOwners(ServerPlayer player) throws ReflectiveOperationException {
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
        field.setAccessible(true);
        return (Map<UUID, ServerPlayer>)field.get(player.getServer().getPlayerList());
    }
    private static CacheActions.Result panelAction(ServerPlayer player, CacheActions.Action action, int slot, int amount, String value) {
        var view=CacheActions.snapshot(player);
        return CacheActions.execute(player,new CacheActions.Intent(view.session(),view.record().state().revision(),action,slot,amount,-1,value));
    }
    private static void panelAccess(GameTestHelper h) throws ReflectiveOperationException {
        var f=setup(h,new ItemStack(Items.BREAD),8);var p=f.owner;
        p.setPos(f.maid.position().add(1,0,0));
        h.getLevel().addNewPlayer(p);
        onlineOwners(p).put(p.getUUID(),p);
        try {
            h.assertTrue(f.maid.isOwnedBy(p), "Panel fixture owner is not present in the native level");
            p.containerMenu=panelMenu(p,f.maid,81);
            var view=CacheActions.open(p);
            h.assertTrue(view.access().active() && view.access().handle().equals(f.handle) && !AccessGate.resolve(p).active()
                    && PanelAccess.address(p).equals(MaidAccess.address(f.maid)),"Unworn owner could not access exactly the native maid target");
            h.assertTrue(panelAction(p,CacheActions.Action.TAKE_CURSOR,0,4,"")==CacheActions.Result.OK,"Maid preview refused");
            var destination=p.containerMenu.slots.stream().filter(slot->slot.container==p.getInventory() && slot.getContainerSlot()==9).findFirst().orElseThrow();
            p.containerMenu.clicked(destination.index,1,net.minecraft.world.inventory.ClickType.PICKUP,p);
            p.containerMenu.clicked(destination.index,1,net.minecraft.world.inventory.ClickType.PICKUP,p);
            h.assertTrue(f.stock()==6 && p.getInventory().getItem(9).getCount()==2 && p.containerMenu.getCarried().getCount()==2,
                    "Native partial placement did not debit exactly the maid stock");
            CacheActions.close(p);
            h.assertTrue(f.stock()==6 && p.containerMenu.getCarried().isEmpty() && CursorReservations.reserved(f.handle.cacheId(),0)==0,
                    "Cancel materialized the remainder of a maid preview");
            h.assertTrue(panelAction(p,CacheActions.Action.TAKE_INVENTORY,0,64,"")==CacheActions.Result.OK && f.stock()==0
                    && p.getInventory().countItem(Items.BREAD)==8,"Maid shift destination was not the operating player's inventory");
            var stone=ItemVariantKey.of(new ItemStack(Items.STONE),p.registryAccess());
            h.assertTrue(panelAction(p,CacheActions.Action.SET_GHOST,1,0,stone.encoded())==CacheActions.Result.OK
                    && panelAction(p,CacheActions.Action.CLEAR_FILTER,1,0,"")==CacheActions.Result.OK,"Maid filter edit or empty unbind failed");
            view=CacheActions.snapshot(p);
            h.assertTrue(CacheActions.settings(p,view.session(),List.of(new CacheActions.SettingsChange(0,
                    f.record().state().cells().getFirst().filterRevision(),new dev.scathiard.feedmepackages.domain.CellSettings(4,-1,64,128))))==CacheActions.Result.OK,
                    "Maid supply settings failed");
            var before=f.record();var edit=before.state().edit();edit.request(0,UUID.randomUUID(),f.maid.getUUID(),64,false);
            f.ledger.replace(f.handle,before.state().revision(),before.withState(edit.finish()));
            h.assertTrue(panelAction(p,CacheActions.Action.RESET_REQUEST,0,0,"")==CacheActions.Result.OK && f.record().state().pending(0)==0,
                    "Maid manual request reset failed");
            h.assertTrue(panelAction(p,CacheActions.Action.SET_RETURN_ADDRESS,-1,0,"elsewhere")==CacheActions.Result.OK
                    && panelAction(p,CacheActions.Action.FILL_RECIPE,-1,0,"minecraft:bread")==CacheActions.Result.UNSUPPORTED_RECIPE,
                    "Maid return address failed or player recipe access leaked");
            h.assertTrue(!MaidPickup.enabled(f.maid), "Pickup must default off");
            h.assertTrue(panelAction(p,CacheActions.Action.MAID_PICKUP,-1,1,"")==CacheActions.Result.OK && MaidPickup.enabled(f.maid), "Owner toggle failed");
            h.assertTrue(panelAction(p,CacheActions.Action.MAID_PICKUP,-1,2,"")==CacheActions.Result.INVALID_REQUEST, "Invalid toggle accepted");
            TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            var own=AccessGate.resolve(p).handle();
            h.assertTrue(!own.cacheId().equals(f.handle.cacheId()) && CacheActions.snapshot(p).access().handle().equals(f.handle),
                    "Player necklace stole the active maid panel");
            CacheActions.close(p);p.containerMenu=p.inventoryMenu;
            h.assertTrue(CacheActions.open(p).access().handle().equals(own),"Leaving the maid page did not restore the player's own cache");
            h.assertTrue(panelAction(p,CacheActions.Action.MAID_PICKUP,-1,0,"")==CacheActions.Result.INVALID_REQUEST && MaidPickup.enabled(f.maid), "Player page changed a maid toggle");
            CacheActions.close(p);h.succeed();
        } finally {
            CacheActions.close(p);
            onlineOwners(p).remove(p.getUUID(),p);
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }
    private static void panelRevocation(GameTestHelper h) throws ReflectiveOperationException {
        for(String condition:List.of("owner","distance","unwear","replace","menu","removed")) {
            var f=setup(h,new ItemStack(Items.BREAD),8);var p=f.owner;
            p.setPos(f.maid.position().add(1,0,0));
            h.getLevel().addNewPlayer(p);
            onlineOwners(p).put(p.getUUID(),p);
            try {
                h.assertTrue(f.maid.isOwnedBy(p), "Revocation fixture owner is not present in the native level");
                p.containerMenu=panelMenu(p,f.maid,82);
                var old=p.containerMenu;var view=CacheActions.open(p);
                h.assertTrue(panelAction(p,CacheActions.Action.TAKE_CURSOR,0,4,"")==CacheActions.Result.OK,"Revocation preview unavailable");
                switch(condition) {
                    case "owner" -> f.maid.setOwnerUUID(UUID.randomUUID());
                    case "distance" -> p.setPos(f.maid.position().add(30,0,0));
                    case "unwear" -> CuriosApi.getCuriosInventory(f.maid).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0,ItemStack.EMPTY);
                    case "replace" -> CuriosApi.getCuriosInventory(f.maid).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0,FmpRegistries.PENDANT.toStack());
                    case "menu" -> p.containerMenu=p.inventoryMenu;
                    case "removed" -> f.maid.discard();
                }
                CacheActions.validateOpenContext(p);
                h.assertTrue(old.getCarried().isEmpty() && CursorReservations.reserved(f.handle.cacheId(),0)==0 && f.stock()==8,
                        "Invalid maid context retained/materialized preview: "+condition);
                var result=CacheActions.execute(p,new CacheActions.Intent(view.session(),view.record().state().revision(),
                        CacheActions.Action.TAKE_INVENTORY,0,4,-1,""));
                h.assertTrue(result!=CacheActions.Result.OK && f.stock()==8,"Stale maid command succeeded: "+condition);
                h.assertTrue(CacheActions.execute(p,new CacheActions.Intent(view.session(),view.record().state().revision(),
                        CacheActions.Action.MAID_PICKUP,-1,1,-1,""))!=CacheActions.Result.OK && !MaidPickup.enabled(f.maid),
                        "Revoked menu changed maid pickup: "+condition);
                CacheActions.close(p);
            } finally {
                CacheActions.close(p);
                onlineOwners(p).remove(p.getUUID(),p);
                h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        }
        h.succeed();
    }
    private static int find(Fixture f, Predicate<ItemStack> filter, int limit) throws ReflectiveOperationException {
        return (int) Class.forName("com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil")
                .getMethod("findStackSlot", IItemHandler.class, Predicate.class, int.class)
                .invoke(null, MaidCompatibility.inventory(f.maid), filter, limit);
    }
    private static boolean nativePickup(Fixture f, ItemStack item, boolean simulate) throws ReflectiveOperationException {
        var ground = new net.minecraft.world.entity.item.ItemEntity(f.maid.level(), f.maid.getX(), f.maid.getY(), f.maid.getZ(), item.copy());
        ground.setNoPickUpDelay(); f.maid.level().addFreshEntity(ground);
        return (boolean)f.maid.getClass().getMethod("pickupItem",net.minecraft.world.entity.item.ItemEntity.class,boolean.class).invoke(f.maid,ground,simulate);
    }
    private static void pickup(GameTestHelper h) throws ReflectiveOperationException {
        var item = new ItemStack(Items.STONE); item.set(DataComponents.CUSTOM_NAME, Component.literal("Exact pickup components"));
        var f = setup(h,item,0); var inv=MaidCompatibility.inventory(f.maid);
        inv.setStackInSlot(0,item.copyWithCount(11)); f.maid.setItemInHand(InteractionHand.MAIN_HAND,item.copyWithCount(7));
        h.assertTrue(nativePickup(f,item.copyWithCount(3),false) && f.stock()==0 && count(inv,item)==14,"Off switch changed native pickup");
        MaidPickup.setEnabled(f.maid,true);
        h.assertTrue(nativePickup(f,item.copyWithCount(5),true) && f.stock()==0 && count(inv,item)==14,"Simulated pickup changed inventory");
        h.assertTrue(nativePickup(f,item.copyWithCount(5),false) && f.stock()==5 && count(inv,item)==14
                && f.maid.getMainHandItem().getCount()==7,"Pickup moved old inventory/equipment or lost exact variant");
        h.assertTrue(nativePickup(f,new ItemStack(Items.STONE,4),false) && f.stock()==5 && count(inv,new ItemStack(Items.STONE))==4,
                "Unmatched component variant was auto-filtered or swallowed");
        var before=f.record();var edit=before.state().edit();int capacity=before.state().capacity().groups()*64;
        edit.insert(0,f.key,capacity-7);f.ledger.replace(f.handle,before.state().revision(),before.withState(edit.finish()));
        h.assertTrue(nativePickup(f,item.copyWithCount(5),false) && f.stock()==capacity && count(inv,item)==17,"Partial cache acceptance lost leftovers or moved old stock");
        h.assertTrue(nativePickup(f,item.copyWithCount(2),false) && f.stock()==capacity && count(inv,item)==19,"Full cache did not preserve native pickup");
        for(int i=0;i<inv.getSlots();i++) inv.setStackInSlot(i,new ItemStack(Items.DIRT,64));
        f.maid.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIRT,64));f.maid.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.DIRT,64));
        h.assertTrue(!nativePickup(f,item.copyWithCount(2),false) && f.stock()==capacity,"FMP bypassed native refusal");
        h.succeed();
    }
    private static void returnSafety(GameTestHelper h) throws ReflectiveOperationException {
        var f=setup(h,new ItemStack(Items.STONE),100);f.pendant.set(FmpRegistries.NETWORK.get(),UUID.randomUUID());
        var carrier=BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
        if(carrier==Items.AIR)carrier=Items.PAPER;
        var inv=MaidCompatibility.inventory(f.maid);inv.setStackInSlot(0,new ItemStack(carrier));
        var before=f.record();var edit=before.state().edit();edit.settings(0,new dev.scathiard.feedmepackages.domain.CellSettings(-1,64,1,-1));
        f.ledger.replace(f.handle,before.state().revision(),before.withState(edit.finish()));
        f.ledger.setReturnAddress(f.handle.cacheId(),"missing-"+UUID.randomUUID());before=f.record();
        ReturnService.check(MaidAccess.endpoint(f.maid));
        h.assertTrue(f.record()==before && inv.getStackInSlot(0).getCount()==1 && f.ledger.returnEscrow(f.handle.cacheId())==null,"Rejected return spent goods or carrier");
        var handle=MaidAccess.resolve(f.maid).handle();
        var hold=new ReturnEscrow(UUID.randomUUID(),handle,before.state().revision(),0,f.key,36,new ItemStack(carrier),0,-1,"paper","test",false,f.owner.getUUID());
        f.ledger.beginReturn(hold,before,f.maid.registryAccess());inv.setStackInSlot(0,ItemStack.EMPTY);
        var saved=f.ledger.save(new net.minecraft.nbt.CompoundTag(),f.maid.registryAccess());
        var row=(net.minecraft.nbt.CompoundTag)saved.getList("return_handoffs",net.minecraft.nbt.Tag.TAG_COMPOUND).stream()
                .map(net.minecraft.nbt.CompoundTag.class::cast).filter(t->t.getUUID("cache").equals(handle.cacheId())).findFirst().orElseThrow();
        h.assertTrue(row.getUUID("player").equals(f.owner.getUUID()) && row.getUUID("maid").equals(f.maid.getUUID()),"Existing player column was repurposed");
        var loaded=CacheLedger.load(saved,f.maid.registryAccess());var roundtrip=loaded.returnEscrow(handle.cacheId());
        h.assertTrue(roundtrip!=null && roundtrip.maidSource() && roundtrip.maidOwner().equals(f.owner.getUUID())
                && roundtrip.handle().equals(handle),"Escrow reload lost original maid/owner identity");
        h.assertTrue(MaidAccess.resolve(f.maid).status()==AccessGate.Status.RETURN_HELD,"Uncertain maid return did not lock cache");
        f.ledger.rejectReturn(hold);
        for(int i=0;i<inv.getSlots();i++) inv.setStackInSlot(i,new ItemStack(Items.DIRT,64));
        ReturnService.check(MaidAccess.endpoint(f.maid));
        h.assertTrue(MaidAccess.resolve(f.maid).status()==AccessGate.Status.RETURN_REFUND && f.stock()==100,
                "Full maid inventory unlocked or duplicated held carrier");
        inv.setStackInSlot(0,ItemStack.EMPTY);
        ReturnService.check(MaidAccess.endpoint(f.maid));
        h.assertTrue(f.ledger.returnEscrow(handle.cacheId())==null && f.stock()==100 && inv.getStackInSlot(0).getCount()==1
                && f.owner.getInventory().countItem(carrier)==0,"Rejected carrier did not return exactly once to the maid");
        h.succeed();
    }
    private static int returnedStone(ServerPlayer target) {
        int amount=0;
        for(int i=0;i<target.getInventory().getContainerSize();i++) {
            var box=target.getInventory().getItem(i);if(!PackageItem.isPackage(box))continue;
            var contents=PackageItem.getContents(box);
            for(int j=0;j<contents.getSlots();j++) if(contents.getStackInSlot(j).is(Items.STONE)) amount+=contents.getStackInSlot(j).getCount();
        }
        return amount;
    }
    private static void pickupReturn(GameTestHelper h,boolean bee) throws ReflectiveOperationException {
        var f=setup(h,new ItemStack(Items.STONE),0);var target=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());
        f.maid.setPos(h.absoluteVec(new Vec3(1,1.2,1)));target.setPos(h.absoluteVec(new Vec3(4,1.2,4)));h.getLevel().addNewPlayer(target);
        UUID network=UUID.randomUUID();
        if(bee){
            var pos=new BlockPos(1,1,3);var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
            h.setBlock(pos,block.defaultBlockState());block.setPlacedBy(h.getLevel(),h.absolutePos(pos),h.getBlockState(pos),f.owner,new ItemStack(block));
            var port=h.getBlockEntity(pos);network=(UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
            var join=Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            join.getMethod("handle",ServerPlayer.class).invoke(join.getConstructor(UUID.class,UUID.class).newInstance(target.getUUID(),network),target);
        } else {
            var tx=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
            tx.getItem().getClass().getMethod("setEnabled",ItemStack.class,boolean.class).invoke(null,tx,true);
            tx.getItem().inventoryTick(tx,h.getLevel(),target,0,false);
        }
        f.pendant.set(FmpRegistries.NETWORK.get(),network);
        var carrier=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(bee?"create_mobile_packages:robo_bee":"cmpackagecouriers:cardboard_plane_parts")));
        var before=f.record();var edit=before.state().edit();edit.settings(0,new dev.scathiard.feedmepackages.domain.CellSettings(-1,64,1,-1));
        if(bee){var key=ItemVariantKey.of(carrier,f.maid.registryAccess());edit.filter(1,key);edit.insert(1,key,1);}
        else MaidCompatibility.inventory(f.maid).setStackInSlot(0,carrier.copy());
        f.ledger.replace(f.handle,before.state().revision(),before.withState(edit.finish()));
        MaidPickup.setEnabled(f.maid,true);
        nativePickup(f,new ItemStack(Items.STONE,60),false);nativePickup(f,new ItemStack(Items.STONE,40),false);
        h.assertTrue(f.stock()==100,"Real pickups did not reach source cache");
        f.ledger.setReturnAddress(f.handle.cacheId(),SupplyService.address(target));
        h.startSequence().thenIdle(6).thenExecute(()->ReturnService.check(MaidAccess.endpoint(f.maid)))
                .thenWaitUntil(()->h.assertTrue(f.stock()==64 && returnedStone(target)==36,"Native maid return: source="+f.stock()+" target="+returnedStone(target)+" carrier="+(bee?f.record().state().cells().get(1).amount():count(MaidCompatibility.inventory(f.maid),carrier))+" state="+MaidAccess.resolve(f.maid).status()))
                .thenExecute(()->{
                    h.assertTrue(f.ledger.returnEscrow(f.handle.cacheId())==null && f.owner.getInventory().countItem(carrier.getItem())==0
                            && (bee?f.record().state().cells().get(1).amount()==0:count(MaidCompatibility.inventory(f.maid),carrier)==0),"Maid return carrier accounting changed owner inventory");
                    FeedMePackages.LOGGER.info("FMP_MAID_PICKUP_RETURN_PASSED transport={} nativePickup=100 source=64 destination=36 ownerOffline=true carrierSource={}",bee?"bee":"paper",bee?"cache":"backpack");
                }).thenSucceed();
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
        MaidPickup.setEnabled(f.maid, true);
        h.assertTrue(f.maid.save(saved), "Native maid save rejected a live entity");
        String address = MaidAccess.address(f.maid); f.maid.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.assertTrue(MaidAccess.find(h.getLevel().getServer(), address) == null && f.stock() == 10, "Unloaded maid remained addressable");
        h.runAfterDelay(2, () -> {
            var restored = (LivingEntity) EntityType.loadEntityRecursive(saved, h.getLevel(), entity -> entity);
            h.assertTrue(restored != null && h.getLevel().addFreshEntity(restored), "Native entity could not load saved data");
            var access = MaidAccess.resolve(restored);
            h.assertTrue(access.active() && access.handle().cacheId().equals(f.handle.cacheId()) && f.stock() == 10
                    && MaidAccess.find(h.getLevel().getServer(), address) == restored && MaidPickup.enabled(restored), "Native entity roundtrip changed cache, address or pickup toggle");
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
    /**
     * End-to-end: a package addressed to one of our maids is dispatched as a real native Phantom task and its
     * contents must end up in her own cache. Loader and receiver stand together, so this proves the dispatch
     * handover, the maid-addressed target, the arrival and the delivery - not the cruise range.
     */
    private static void phantomDeliveryToMaid(GameTestHelper h) throws ReflectiveOperationException {
        if (!dev.scathiard.feedmepackages.compat.PhantomDispatch.ready()) return; // No Phantom API in this run.
        // An empty but filtered cell: the delivery has to fit, which is what the acceptance predicate must mean.
        var f = setup(h, new ItemStack(Items.DIRT), 0);
        var sender = TestPlayers.create(h, ItemStack.EMPTY);
        var at = f.maid.position();
        sender.moveTo(at.x, at.y, at.z, 0, 0);
        f.maid.setPos(at.x, at.y, at.z);
        var contents = new ArrayList<ItemStack>();
        contents.add(new ItemStack(Items.DIRT, 16));
        ItemStack box = com.simibubi.create.content.logistics.box.PackageItem.containing(contents);
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(
                box, dev.scathiard.feedmepackages.compat.maid.MaidAccess.address(f.maid));
        // Seal it through the production dispatch scope, for her cache: the receiving side only accepts a parcel
        // whose seal this world authenticated for that destination.
        var route = new dev.scathiard.feedmepackages.logistics.ParcelSeal(UUID.randomUUID(), f.handle().cacheId(),
                f.owner.getUUID(), UUID.randomUUID(), "", f.record().state().revision(),
                dev.scathiard.feedmepackages.compat.maid.MaidAccess.address(f.maid), true, "");
        try (var scope = new dev.scathiard.feedmepackages.logistics.DispatchScope(
                f.ledger(), h.getLevel().registryAccess(), route)) {
            dev.scathiard.feedmepackages.logistics.DispatchScope.onCreated(box);
        }
        // 1: dispatch. The address resolver cannot know a maid, so our own path must build her native target.
        var result = dev.scathiard.feedmepackages.compat.PhantomDispatch.send(sender, box);
        h.assertTrue(result.outcome() == dev.scathiard.feedmepackages.compat.TransportDispatch.Outcome.SENT,
                "Phantom dispatch to a maid was not sent: " + result + " cause=" + result.failure());
        // 2: adoption. A real native task aimed at her must be recognised by the flight layer, and a native flight
        // target must be constructible from her live position.
        Object task = nativeMaidTask(h, sender, box.copy(), f.maid);
        var server = h.getLevel().getServer();
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                .maidRecipient(server, task) == f.maid, "The flight layer did not recognise a maid task");
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomFlight
                .flightTarget(f.maid) != null, "No native flight target could be built for the maid");
        // The belt launch gate is a separate injection: load its target through the mixin layer (this is what applies
        // it) and require the shape gate to have accepted the measured shape. A mismatch here means the mixin was
        // silently not applied, which is the one failure this test can rule out without a real belt.
        Class.forName("com.yision.phantom.logistics.courier.AirCourierBeltHooks");
        h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.phantom102BeltCompatible(),
                "The belt launch gate refused the measured shape; the mixin was not applied");
        // 3: arrival. The hand-over must put the contents in her own cache and consume the package exactly once.
        // 3: arrival. The receiving side only ever takes an FMP-authenticated parcel, so the hand-over must obey
        // exactly one of two contracts, and never anything in between: accepted => the contents are in her cache
        // and the package is consumed; refused => nothing moved and the package is still on board for the native
        // undelivered-return path. Both are safe; a silent drop or a double deposit is not.
        long before = f.stock();
        boolean owned = dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                .handOver(task, f.maid, server);
        var left = dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.packageOf(task);
        if (owned) {
            h.assertTrue(f.stock() == before + 16 && left != null && left.isEmpty(),
                    "An accepted hand-over must deposit 16 and consume the package: stock=" + f.stock() + " left=" + left);
        } else {
            h.assertTrue(f.stock() == before && left != null && !left.isEmpty(),
                    "A refused hand-over must move nothing and keep the package: stock=" + f.stock() + " left=" + left
                            + " canReceive=" + dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.canReceive(f.maid, left));
        }
        h.succeed();
    }
    /** A real native package-to-player task aimed at her, built exactly the way our own dispatch builds one. */
    private static Object nativeMaidTask(GameTestHelper h, ServerPlayer sender, ItemStack box, TamableAnimal maid)
            throws ReflectiveOperationException {
        var factory = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask").getMethod("forPackageToPlayer",
                UUID.class, ItemStack.class, ServerLevel.class, UUID.class, net.minecraft.resources.ResourceKey.class,
                Vec3.class, Vec3.class, Vec3.class, net.minecraft.resources.ResourceKey.class, BlockPos.class,
                UUID.class, UUID.class, UUID.class);
        UUID id = UUID.randomUUID(), owner = sender.getUUID();
        return factory.invoke(null, id, box, h.getLevel(), maid.getUUID(), maid.level().dimension(), maid.position(),
                new Vec3(0, 0, 1), new Vec3(0, 0, .3), null, null, owner, id, owner);
    }
    /**
     * The way home: a native carrier return (which a maid dispatch produces for its owner) must be recognised as ours
     * only through our own registry, and its carrier must end up in her inventory using the native item source.
     */
    private static void phantomReturnToMaid(GameTestHelper h) throws ReflectiveOperationException {
        if (!dev.scathiard.feedmepackages.compat.PhantomDispatch.ready()) return;
        var f = setup(h, new ItemStack(Items.DIRT), 0);
        var owner = TestPlayers.create(h, ItemStack.EMPTY);
        var factory = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask").getMethod(
                "forCarrierReturnToPlayer", UUID.class, ServerLevel.class, UUID.class,
                net.minecraft.resources.ResourceKey.class, Vec3.class, Vec3.class, Vec3.class);
        Object task = factory.invoke(null, UUID.randomUUID(), h.getLevel(), owner.getUUID(),
                h.getLevel().dimension(), f.maid.position(), new Vec3(0, 0, 1), new Vec3(0, 0, .3));
        h.assertTrue(task != null, "Native carrier-return task was not created");
        // 1: the measured return-leg criteria recognise it.
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.onReturnLeg(task),
                "A carrier return was not recognised as a return leg");
        // 1b: a carrier return that has a source port must NOT be ours - it has to fly home to that port (native, and
        // the user verified it on the belt path). This is the guard against redirecting a correct return.
        var portFactory = Class.forName("com.yision.phantom.logistics.courier.AirCourierTask").getMethod(
                "forCarrierReturn", UUID.class, ServerLevel.class, net.minecraft.resources.ResourceKey.class,
                BlockPos.class, Vec3.class, Vec3.class, Vec3.class);
        Object portTask = portFactory.invoke(null, UUID.randomUUID(), h.getLevel(), h.getLevel().dimension(),
                f.maid.blockPosition(), f.maid.position(), new Vec3(0, 0, 1), new Vec3(0, 0, .3));
        h.assertTrue(portTask != null, "Native carrier-return-to-port task was not created");
        h.assertTrue(!dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                        .returnsToSourcePlayer(portTask),
                "A carrier return with a source port must never be redirected to the maid");
        // 1c: a port schedules the carrier return with the source UUID it was handed, so with a maid dispatcher that
        // slot holds a maid address - it must be recognised on its own, with no registry entry at all.
        Object slotTask = factory.invoke(null, UUID.randomUUID(), h.getLevel(), f.maid.getUUID(),
                h.getLevel().dimension(), f.maid.position(), new Vec3(0, 0, 1), new Vec3(0, 0, .3));
        h.assertTrue(slotTask != null, "Native carrier-return-to-player task was not created");
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                        .carrierRecipient(h.getLevel().getServer(), slotTask) == f.maid,
                "A carrier return carrying a maid address in its player slot was not recognised");
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                        .maidFor(slotTask, h.getLevel().getServer()) == null,
                "Slot recognition must not depend on a registry entry");
        // 2: an unregistered carrier return is an ordinary player task and must stay one.
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                        .maidFor(task, h.getLevel().getServer()) == null,
                "An unregistered carrier return was claimed as ours");
        // 3: registered as the dispatcher, it is hers, and the native carrier goes into her own inventory.
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                .rememberReturn(task, f.maid), "Our own carrier return could not be registered");
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                        .maidForReturn(task, h.getLevel().getServer()) == f.maid,
                "A registered carrier return lost its maid");
        var carrier = dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks.nativeCarrier();
        h.assertTrue(carrier != null && !carrier.isEmpty(), "The native carrier item could not be created");
        h.assertTrue(dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks
                .handCarrierTo(f.maid, carrier), "The returning carrier was not handed to the maid");
        h.succeed();
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
