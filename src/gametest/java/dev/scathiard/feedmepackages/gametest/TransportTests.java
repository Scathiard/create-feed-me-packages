package dev.scathiard.feedmepackages.gametest;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import com.simibubi.create.AllDataComponents;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.logistics.ParcelAuthentication;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.HolderLookup;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Optional tests exist only when the corresponding real mod is loaded; no successful skip tests. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class TransportTests {
    @GameTestGenerator
    public static Collection<TestFunction> installedTransports() {
        var tests = new ArrayList<TestFunction>();
        if (ModList.get().isLoaded("create_mobile_packages")) {
            tests.add(test("mobile_handoff", TransportTests::mobileHandoff));
            tests.add(test("mobile_registry_roundtrip", TransportTests::mobileRegistryRoundtrip));
            tests.add(test("mobile_native_flight", TransportTests::mobileNativeFlight));
            tests.add(test("mobile_native_boundaries", TransportTests::mobileNativeBoundaries));
            tests.add(test("return_mobile_dispatch", TransportTests::returnMobileDispatch));
            tests.add(test("return_mobile_shared_stock", h -> returnSharedStock(h, true)));
            tests.add(test("return_mobile_unavailable", h -> returnUnavailable(h, true, false)));
            tests.add(test("return_mobile_full_port", h -> returnUnavailable(h, true, true)));
        }
        if (ModList.get().isLoaded("cmpackagecouriers")) {
            tests.add(test("paper_native_flight", TransportTests::paperNativeFlight));
            tests.add(test("paper_registry_roundtrip", TransportTests::paperRegistryRoundtrip));
            tests.add(test("paper_native_boundaries", TransportTests::paperNativeBoundaries));
            tests.add(test("return_paper_dispatch", TransportTests::returnPaperDispatch));
            tests.add(test("return_paper_shared_stock", h -> returnSharedStock(h, false)));
            tests.add(test("return_paper_unavailable", h -> returnUnavailable(h, false, false)));
        }
        if (ModList.get().isLoaded("fluidlogistics")) for (boolean mobile : new boolean[]{false,true}) {
            if (!ModList.get().isLoaded(mobile ? "create_mobile_packages" : "cmpackagecouriers")) continue;
            String name=mobile?"mobile":"paper";
            tests.add(test("supply_fluid_"+name+"_flight",h->fluidNativeFlight(h,mobile)));
            tests.add(test("return_fluid_"+name+"_flight",h->returnFluidFlight(h,mobile)));
            tests.add(test("return_fluid_"+name+"_preview",h->returnFluidPreview(h,mobile)));
        }
        return tests;
    }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction("transports", "fmp_" + name, FeedMePackages.MOD_ID + ":empty",
                name.endsWith("_boundaries") ? 4800 : 1200, 0, true, body);
    }

    /** Tests native membership/cold dimension lookup and flight through an actually unloaded area. */
    private static void mobileNativeBoundaries(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addNewPlayer(f.player());
        var portPos = new BlockPos(1, 1, 1);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
        helper.setBlock(portPos, block.defaultBlockState());
        block.setPlacedBy(helper.getLevel(), helper.absolutePos(portPos), helper.getBlockState(portPos), f.player(), new ItemStack(block));
        try {
            var port = helper.getBlockEntity(portPos); var portType = port.getClass();
            var network = (UUID)portType.getMethod("getLogisticsNetworkId").invoke(port);
            var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            var leave = Class.forName("de.theidler.create_mobile_packages.network_settings.RemovePlayerFromNetworkPackage");
            var playerType = net.minecraft.server.level.ServerPlayer.class;
            var lookup = Class.forName("de.theidler.create_mobile_packages.robo.PlayerTarget")
                    .getMethod("fromAddress", ServerLevel.class, String.class, UUID.class);
            var box = f.box(12); var original = box.copy();
            leave.getMethod("handle", playerType).invoke(leave.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            helper.assertTrue(lookup.invoke(null, helper.getLevel(), PackageItem.getAddress(box), network) == null,
                    "FMP address bypassed native bee membership");
            join.getMethod("handle", playerType).invoke(join.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            helper.assertTrue(lookup.invoke(null, helper.getLevel(), PackageItem.getAddress(box), network) != null,
                    "Native member could not resolve the FMP address");
            var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null && lookup.invoke(null, nether, PackageItem.getAddress(box), network) == null,
                    "Native cold lookup unexpectedly found a player in a different dimension");
            helper.assertTrue(ItemStack.matches(box, original) && f.record().state().cells().getFirst().amount() == 0,
                    "Native target queries changed stock or a package");

            var start = new BlockPos(f.player().getBlockX() + 512, helper.getLevel().getMaxBuildHeight() + 64, f.player().getBlockZ());
            helper.assertTrue(!helper.getLevel().hasChunk(start.getX() >> 4, start.getZ() >> 4), "Bee unloaded-area fixture is already loaded");
            var managerType = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            var manager = managerType.getMethod("get", ServerLevel.class).invoke(null, helper.getLevel());
            var id = (UUID)managerType.getMethod("newRobo", ServerLevel.class, ItemStack.class, BlockPos.class, UUID.class,
                    float.class, BlockPos.class, boolean.class).invoke(manager, helper.getLevel(), box.copy(), start, network,
                            1F, helper.absolutePos(portPos), true);
            box.setCount(0);
            var find = managerType.getMethod("get", UUID.class);
            var roboType = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            var position = roboType.getMethod("getCurrentPos"); var cargo = roboType.getMethod("getItemStack");
            var entityId = roboType.getDeclaredField("entityId"); entityId.setAccessible(true);
            var bees = (net.neoforged.neoforge.items.ItemStackHandler)portType.getMethod("getRoboBeeInventory").invoke(port);
            helper.startSequence()
                    .thenExecuteAfter(20, () -> {
                        try {
                            var robo = find.invoke(manager, id); helper.assertTrue(robo != null, "Unloaded native bee disappeared");
                            var pos = (Vec3)position.invoke(robo); var chunk = BlockPos.containing(pos);
                            helper.assertTrue(!helper.getLevel().hasChunk(chunk.getX() >> 4, chunk.getZ() >> 4)
                                    && entityId.get(robo) == null, "Native unloaded leg forced a rendered entity or a chunk");
                            helper.assertTrue(pos.distanceToSqr(Vec3.atCenterOf(start).subtract(0, 0.5, 0)) > 1
                                    && ItemStack.matches((ItemStack)cargo.invoke(robo), original), "Virtual bee did not move with its intact package");
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 12,
                            "Native bee did not deliver after crossing the unloaded area"))
                    .thenWaitUntil(() -> {
                        try {
                            helper.assertTrue(find.invoke(manager, id) == null && bees.getStackInSlot(0).getCount() == 1,
                                    "Native unloaded flight did not finish with its bee recovered");
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    })
                    .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_TRANSPORT_BOUNDARY_PASSED mobile native-membership/cold-dimension/unloaded-flight stock=12"))
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mobile boundary experiment failed", failure); }
    }

    private static void paperNativeBoundaries(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addNewPlayer(f.player());
        var transmitter = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
        try {
            var manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            var launch = manager.getMethod("addPlane", Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class);
            var box = f.box(11); var original = box.copy();
            helper.assertTrue(!(Boolean)launch.invoke(null, helper.getLevel(), f.player().position(), 0F, 0F, box, false)
                    && ItemStack.matches(box, original), "Paper without a native published target launched or consumed a package");
            transmitter.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, transmitter, true);
            transmitter.getItem().inventoryTick(transmitter, helper.getLevel(), f.player(), 0, false);
            var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "No native Nether dimension in the boundary fixture");
            var start = new Vec3(f.player().getX() + 512, 150, f.player().getZ());
            helper.assertTrue(!nether.hasChunk(BlockPos.containing(start).getX() >> 4, BlockPos.containing(start).getZ() >> 4),
                    "Paper unloaded-area fixture is already loaded");
            var config = Class.forName("com.kreidev.cmpackagecouriers.ServerConfig");
            var crossDim = config.getField("planeCrossDimTransport"); var playerTargets = config.getField("planePlayerTargets");
            boolean oldCrossDim = crossDim.getBoolean(null), oldPlayerTargets = playerTargets.getBoolean(null);
            try {
                playerTargets.setBoolean(null, false);
                helper.assertTrue(!(Boolean)launch.invoke(null, helper.getLevel(), f.player().position(), 0F, 0F, box, false)
                        && ItemStack.matches(box, original), "Paper player-target setting was bypassed");
                playerTargets.setBoolean(null, true); crossDim.setBoolean(null, false);
                helper.assertTrue(!(Boolean)launch.invoke(null, nether, start, 0F, 0F, box, false)
                        && ItemStack.matches(box, original), "Paper cross-dimension setting was bypassed");
                crossDim.setBoolean(null, true);
                helper.assertTrue((Boolean)launch.invoke(null, nether, start, 0F, 0F, box.copy(), false), "Native cross-dimension paper failed to launch");
                box.setCount(0);
            } finally { crossDim.setBoolean(null, oldCrossDim); playerTargets.setBoolean(null, oldPlayerTargets); }
            var saved = manager.getField("INSTANCE").get(null);
            @SuppressWarnings("unchecked") var pairs = (List<net.createmod.catnip.data.Pair<Object, Object>>)saved.getClass().getField("pairedPlanes").get(saved);
            var planeType = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlane");
            var getPackage = planeType.getMethod("getPackage");
            var parcelId = original.get(FmpRegistries.PARCEL_SEAL.get()).parcelId();
            helper.startSequence()
                    .thenExecuteAfter(20, () -> {
                        try {
                            Object plane = null, rendered = null;
                            for (var pair : pairs) {
                                var candidate = (ItemStack)getPackage.invoke(pair.getFirst()); var seal = candidate.get(FmpRegistries.PARCEL_SEAL.get());
                                if (seal != null && seal.parcelId().equals(parcelId)) { plane = pair.getFirst(); rendered = pair.getSecond(); break; }
                            }
                            helper.assertTrue(plane != null && rendered == null, "Paper did not preserve an unrendered virtual flight");
                            var pos = (Vec3)planeType.getMethod("getPos").invoke(plane);
                            helper.assertTrue(planeType.getMethod("getCurrentDim").invoke(plane) == Level.NETHER
                                    && !nether.hasChunk(BlockPos.containing(pos).getX() >> 4, BlockPos.containing(pos).getZ() >> 4),
                                    "Paper unloaded leg unexpectedly changed dimension or loaded its area");
                            helper.assertTrue(pos.distanceToSqr(start) > 1 && ItemStack.matches((ItemStack)getPackage.invoke(plane), original),
                                    "Unloaded paper did not move with intact cargo");
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 11,
                            "Native paper did not deliver from an unloaded area across dimensions"))
                    .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_TRANSPORT_BOUNDARY_PASSED paper native-target/config/cross-dimension/unloaded-flight stock=11"))
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Paper boundary experiment failed", failure); }
    }

    private static void mobileHandoff(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8);
        try {
            var api = Class.forName("de.theidler.create_mobile_packages.blocks.bee_port.BeePortBlockEntity")
                    .getMethod("sendPackageToPlayer", Player.class, ItemStack.class);
            var first = f.box(32);
            helper.assertTrue((Boolean)api.invoke(null, f.player(), first) && first.isEmpty(), "Native mobile handoff refused service intake");
            var next = f.box(16); var original = next.copy();
            helper.assertTrue(!(Boolean)api.invoke(null, f.player(), next) && ItemStack.matches(next, original), "Native rejection lost its parcel");
            var ordinary = new ItemStack(Items.STONE, 5);
            helper.assertTrue(!(Boolean)api.invoke(null, f.player(), ordinary) && ordinary.getCount() == 5, "Compatibility changed ordinary full-inventory behavior");
            helper.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL, "Native handoff duplicated stock");
            helper.succeed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mobile handoff API experiment failed", failure); }
    }

    private static void mobileRegistryRoundtrip(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); var box = f.box(5);
        var enchanted = new ItemStack(Items.ENCHANTED_BOOK);
        enchanted.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 3);
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 5), enchanted)));
        ParcelAuthentication.apply(f.ledger(), f.player().registryAccess(), box, box.get(FmpRegistries.PARCEL_SEAL.get()));
        try {
            var type = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            var robo = type.getConstructor(ServerLevel.class, UUID.class, ItemStack.class, BlockPos.class, UUID.class)
                    .newInstance(helper.getLevel(), UUID.randomUUID(), box, helper.absolutePos(BlockPos.ZERO), UUID.randomUUID());
            var nbt = (CompoundTag)type.getMethod("serializeNBT").invoke(robo);
            var loaded = type.getMethod("deserializeNBT", ServerLevel.class, CompoundTag.class).invoke(null, helper.getLevel(), nbt);
            var restored = (ItemStack)type.getMethod("getItemStack").invoke(loaded);
            helper.assertTrue(ItemStack.matches(box, restored), "Mobile transport failed to preserve registered components and parcel seal");
            helper.succeed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mobile storage API experiment failed", failure); }
    }

    /** Native port inventory -> virtual/entity flight -> service handoff -> native bee recovery.
     * This is inbound replenishment, not FMP's deferred return-goods feature. */
    private static void mobileNativeFlight(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        var outbound = SupplyTests.outboundFromNetwork(helper, f);
        f.player().setPos(helper.absoluteVec(new Vec3(5, 2, 5))); helper.getLevel().addNewPlayer(f.player());
        BlockPos portPos = new BlockPos(1, 1, 1);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
        helper.setBlock(portPos, block.defaultBlockState());
        block.setPlacedBy(helper.getLevel(), helper.absolutePos(portPos), helper.getBlockState(portPos), f.player(), new ItemStack(block));
        try {
            var port = helper.getBlockEntity(portPos); var portType = port.getClass();
            var bees = (net.neoforged.neoforge.items.ItemStackHandler)portType.getMethod("getRoboBeeInventory").invoke(port);
            portType.getMethod("addBeeToRoboBeeInventory", int.class).invoke(port, 1);
            portType.getMethod("setBeeReturnModeEnabled", boolean.class).invoke(port, true);
            var network = (UUID)portType.getMethod("getLogisticsNetworkId").invoke(port);
            // Fixture performs the transport mod's own "add yourself" action, not an FMP membership bypass.
            var joinType = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            joinType.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                    joinType.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            var members = Class.forName("de.theidler.create_mobile_packages.network_settings.NetworkHelper").getMethod("getPlayerUUIDs", UUID.class);
            helper.assertTrue(((java.util.Set<?>)members.invoke(null, network)).contains(f.player().getUUID()), "Native join fixture did not register the recipient");
            var managerType = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            var manager = managerType.getMethod("get", ServerLevel.class).invoke(null, helper.getLevel());
            @SuppressWarnings("unchecked") var robos = (java.util.Map<UUID, Object>)managerType.getField("robos").get(manager);
            var roboType = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            var getStack = roboType.getMethod("getItemStack");
            var add = portType.getMethod("addItemStack", ItemStack.class, boolean.class);
            var inFlight = new UUID[1];
            Runnable observeFlight = () -> {
                inFlight[0] = robos.entrySet().stream().filter(entry -> {
                    try {
                        var stack = (ItemStack)getStack.invoke(entry.getValue()); var seal = stack.get(FmpRegistries.PARCEL_SEAL.get());
                        return seal != null && seal.cacheId().equals(f.handle().cacheId());
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                }).map(java.util.Map.Entry::getKey).findFirst().orElse(null);
                String detail;
                try {
                    var counter = portType.getDeclaredField("tickCounter"); counter.setAccessible(true);
                    detail = "tick=" + helper.getTick() + ",portTicks=" + counter.getInt(port) + ",bees=" + bees.getStackInSlot(0).getCount()
                            + ",members=" + ((java.util.Set<?>)members.invoke(null, network)).size() + ",robos=" + robos.size();
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                helper.assertTrue(inFlight[0] != null && bees.getStackInSlot(0).isEmpty(), "Native port has not launched its actual bee and package: " + detail);
            };
            Consumer<ItemStack> enqueue = box -> {
                try {
                    helper.assertTrue((Boolean)add.invoke(port, box, true) && box.getCount() == 1, "Native port simulation consumed package");
                    // ItemStackHandler may retain the offered object. Transfer a detached real stack,
                    // then remove the source, as the normal inventory-extraction path does.
                    helper.assertTrue((Boolean)add.invoke(port, box.copy(), false), "Native port refused fixture input");
                    box.setCount(0);
                    helper.assertTrue(!((com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity)port).inventory.getStackInSlot(0).isEmpty(),
                            "Fixture source cleanup also cleared the destination package");
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            };
            Runnable recovered = () -> helper.assertTrue(inFlight[0] != null && !robos.containsKey(inFlight[0]) && bees.getStackInSlot(0).getCount() == 1,
                    "Delivered native bee has not returned to its configured port");
            helper.startSequence()
                    .thenWaitUntil(() -> enqueue.accept(outbound.get()))
                    .thenWaitUntil(observeFlight)
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 64, "Native bee did not deliver full parcel to full backpack"))
                    .thenWaitUntil(recovered)
                    .thenExecute(() -> {
                        helper.assertTrue(f.record().state().orders().isEmpty(), "Network-to-bee delivery did not settle its real request");
                        FeedMePackages.LOGGER.info("FMP_NETWORK_FLIGHT_PASSED mobile source=64 cache=64 backpack=full");
                    })
                    .thenExecute(() -> {
                        var before = f.record(); var edit = before.state().edit(); edit.insert(0, f.key(), ReceiveTests.FULL);
                        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish())); enqueue.accept(f.box(32));
                    })
                    .thenWaitUntil(observeFlight)
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL && !f.residual().isEmpty(), "Native bee did not deliver partial parcel"))
                    .thenWaitUntil(recovered)
                    .thenExecute(() -> enqueue.accept(f.box(16)))
                    .thenWaitUntil(observeFlight)
                    .thenExecuteAfter(100, () -> {
                        try {
                            var live = robos.get(inFlight[0]); helper.assertTrue(live != null, "Refused native bee discarded its transport record");
                            var box = (ItemStack)getStack.invoke(live);
                            helper.assertTrue(PackageItem.isPackage(box) && PackageItem.getContents(box).getStackInSlot(0).getCount() == 16,
                                    "Refused native bee lost original cargo");
                            helper.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL, "Refused native delivery changed cache");
                            f.extract(ReceiveTests.FULL); dev.scathiard.feedmepackages.logistics.ReceiveService.resume(f.player());
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 32 + 16 && f.residual().isEmpty(), "Native retry after space was freed was not conservative"))
                    .thenWaitUntil(recovered)
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mobile native flight experiment failed", failure); }
    }

    private static void paperNativeFlight(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        var outbound = SupplyTests.outboundFromNetwork(helper, f);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addNewPlayer(f.player());
        var transmitter = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
        try {
            transmitter.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, transmitter, true);
            // Native transmitter publishes the target; FMP never adds members or targets itself.
            transmitter.getItem().inventoryTick(transmitter, helper.getLevel(), f.player(), 0, false);
            var manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            var launch = manager.getMethod("addPlane", Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class);
            var second = f.box(32); var refused = f.box(16);
            Consumer<ItemStack> send = box -> {
                try {
                    transmitter.getItem().inventoryTick(transmitter, helper.getLevel(), f.player(), 0, false);
                    helper.assertTrue((Boolean)launch.invoke(null, helper.getLevel(), f.player().position().add(0, 0, 3), 180F, 0F, box.copy(), false), "Native paper launch rejected FMP address");
                    box.setCount(0);
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            };
            helper.startSequence()
                    .thenWaitUntil(() -> send.accept(outbound.get()))
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 64, "Paper has not delivered full parcel"))
                    .thenExecute(() -> {
                        helper.assertTrue(f.record().state().orders().isEmpty(), "Network-to-paper delivery did not settle its real request");
                        FeedMePackages.LOGGER.info("FMP_NETWORK_FLIGHT_PASSED paper source=64 cache=64 backpack=full");
                    })
                    .thenExecute(() -> {
                        var before = f.record(); var edit = before.state().edit(); edit.insert(0, f.key(), 8120);
                        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish())); send.accept(second);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL && !f.residual().isEmpty(), "Paper has not delivered partial parcel"))
                    .thenExecute(() -> send.accept(refused))
                    .thenWaitUntil(() -> {
                        var drops = helper.getLevel().getEntitiesOfClass(Entity.class, new AABB(f.player().blockPosition()).inflate(4), entity ->
                                droppedStack(entity).has(FmpRegistries.PARCEL_SEAL.get()) && droppedStack(entity).get(FmpRegistries.PARCEL_SEAL.get()).cacheId().equals(f.handle().cacheId()));
                        helper.assertTrue(drops.size() == 1 && PackageItem.getContents(droppedStack(drops.getFirst())).getStackInSlot(0).getCount() == 16, "Refused paper parcel did not survive native fallback");
                        helper.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL, "Refused paper delivery changed cache");
                    })
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Paper flight experiment failed", failure); }
    }

    /** FMP's own automatic return: trim a cell above its maximum by dispatching the overage back to the
     *  per-cache return address (here the player itself) through a consumed cardboard-plane carrier. */
    private static void returnPaperDispatch(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addNewPlayer(f.player());
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        f.ledger().setReturnAddress(f.handle().cacheId(), SupplyService.address(f.player()));
        var before = f.record(); var edit = before.state().edit();
        for (int level = 1; level < 5; level++) edit.upgrade();
        edit.thresholds(0, 0, 1); edit.insert(0, f.key(), 1000);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 1000, "Paper return fixture did not retain the requested overage");
        f.player().getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"))));
        var transmitter = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
        try {
            transmitter.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, transmitter, true);
            transmitter.getItem().inventoryTick(transmitter, helper.getLevel(), f.player(), 0, false);
            helper.startSequence()
                    .thenExecute(() -> ReturnService.check(f.player()))
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 424,
                            "Return dispatch did not trim exactly one package while leaving overage"))
                    .thenWaitUntil(() -> helper.assertTrue(f.player().getInventory().getItem(0).getCount() == 0,
                            "Return dispatch did not consume the cardboard carrier"))
                    .thenWaitUntil(() -> helper.assertTrue(f.record().residual(f.key()).isEmpty(),
                            "Return dispatch left a stray residual"))
                    .thenExecute(() -> {
                        assertReturnSnapshotRoundTrips(helper, f.player(), 0, ReturnService.RETURN_SENT);
                        var afterSuccess = f.record();
                        ReturnService.check(f.player());
                        helper.assertTrue(f.record() == afterSuccess && f.record().state().cells().getFirst().amount() == 424,
                                "Carrier-less paper retry changed stock or revision");
                        assertReturnSnapshotRoundTrips(helper, f.player(), 0, ReturnService.RETURN_FAILED);
                    })
                    .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_RETURN_PAPER_DISPATCH_PASSED stock=424 carrier=0 state=sent-then-failed"))
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Return paper dispatch experiment failed", failure); }
    }

    /** FMP's own automatic return through a real transport bee. The drone must fly inside the cache's
     *  actual logistics network, so its UUID is the bee port's network — the reuse of a placeholder UUID
     *  would spawn a drone that cannot resolve the return address. */
    private static void returnMobileDispatch(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0);
        f.player().setPos(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addNewPlayer(f.player());
        var portPos = new BlockPos(1, 1, 1);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
        helper.setBlock(portPos, block.defaultBlockState());
        block.setPlacedBy(helper.getLevel(), helper.absolutePos(portPos), helper.getBlockState(portPos), f.player(), new ItemStack(block));
        try {
            var port = helper.getBlockEntity(portPos); var portType = port.getClass();
            var network = (UUID)portType.getMethod("getLogisticsNetworkId").invoke(port);
            TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
            var joinType = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            joinType.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                    joinType.getConstructor(UUID.class, UUID.class).newInstance(f.player().getUUID(), network), f.player());
            f.ledger().setReturnAddress(f.handle().cacheId(), SupplyService.address(f.player()));
            var before = f.record(); var edit = before.state().edit();
            for (int level = 1; level < 5; level++) edit.upgrade();
            edit.thresholds(0, 0, 1); edit.insert(0, f.key(), 1000);
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
            helper.assertTrue(f.record().state().cells().getFirst().amount() == 1000, "Bee return fixture did not retain the requested overage");
            f.player().getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"))));
            helper.startSequence()
                    .thenExecute(() -> ReturnService.check(f.player()))
                    .thenWaitUntil(() -> helper.assertTrue(f.record().state().cells().getFirst().amount() == 424,
                            "Bee return dispatch did not trim exactly one package while leaving overage"))
                    .thenWaitUntil(() -> helper.assertTrue(f.player().getInventory().getItem(0).getCount() == 0,
                            "Bee return dispatch did not consume the bee carrier"))
                    .thenWaitUntil(() -> helper.assertTrue(f.record().residual(f.key()).isEmpty(),
                            "Bee return dispatch left a stray residual"))
                    .thenExecute(() -> {
                        assertReturnSnapshotRoundTrips(helper, f.player(), 0, ReturnService.RETURN_SENT);
                        var afterSuccess = f.record();
                        ReturnService.check(f.player());
                        helper.assertTrue(f.record() == afterSuccess && f.record().state().cells().getFirst().amount() == 424,
                                "Carrier-less bee retry changed stock or revision");
                        assertReturnSnapshotRoundTrips(helper, f.player(), 0, ReturnService.RETURN_FAILED);
                    })
                    .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_RETURN_MOBILE_DISPATCH_PASSED stock=424 carrier=0 state=sent-then-failed"))
                    .thenSucceed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Return mobile dispatch experiment failed", failure); }
    }

    private static ItemStack droppedStack(Entity entity) {
        return entity instanceof PackageEntity parcel ? parcel.getBox() : entity instanceof ItemEntity item ? item.getItem() : ItemStack.EMPTY;
    }

    private static void assertReturnSnapshotRoundTrips(GameTestHelper helper, net.minecraft.server.level.ServerPlayer player, int slot, int state) {
        player.tickCount += 4;
        var view = PanelNetwork.query(player, new PanelPackets.Query(UUID.randomUUID(), player.containerMenu.containerId, true));
        helper.assertTrue(view != null && view.cells().get(slot).returnState() == state,
                "Return state did not reach the server snapshot: expected=" + state + " actual="
                        + (view == null ? "null" : view.cells().get(slot).returnState()));
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.Snapshot.CODEC.encode(wire, view);
            var decoded = PanelPackets.Snapshot.CODEC.decode(wire);
            helper.assertTrue(decoded.cells().get(slot).returnState() == state && decoded.equals(view),
                    "Return state did not survive snapshot codec round trip");
        } finally {
            wire.release();
        }
    }

    private static void paperRegistryRoundtrip(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); var box = f.box(5);
        var enchanted = new ItemStack(Items.ENCHANTED_BOOK);
        enchanted.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 3);
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 5), enchanted)));
        ParcelAuthentication.apply(f.ledger(), f.player().registryAccess(), box, box.get(FmpRegistries.PARCEL_SEAL.get()));
        try {
            var targetType = Class.forName("com.kreidev.cmpackagecouriers.CourierTarget");
            var target = targetType.getConstructor(String.class, Entity.class).newInstance(f.player().getGameProfile().getName(), f.player());
            var planeType = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlane");
            var plane = planeType.getConstructor(Level.class, targetType, ItemStack.class).newInstance(helper.getLevel(), target, box);
            var savedType = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneSavedData");
            var data = savedType.getConstructor().newInstance();
            @SuppressWarnings("unchecked") var pairs = (java.util.List<net.createmod.catnip.data.Pair<Object, Object>>)savedType.getField("pairedPlanes").get(data);
            pairs.add(net.createmod.catnip.data.Pair.of(plane, null));
            var tag = (CompoundTag)savedType.getMethod("save", CompoundTag.class, HolderLookup.Provider.class).invoke(data, new CompoundTag(), helper.getLevel().registryAccess());
            var restored = savedType.getConstructor(CompoundTag.class, HolderLookup.Provider.class).newInstance(tag, helper.getLevel().registryAccess());
            var loadedPairs = (List<?>)savedType.getField("pairedPlanes").get(restored);
            helper.assertTrue(loadedPairs.size() == 1, "Paper storage discarded parcel during round trip");
            Object loaded = ((net.createmod.catnip.data.Pair<?, ?>)loadedPairs.getFirst()).getFirst();
            var recovered = (ItemStack)planeType.getMethod("getPackage").invoke(loaded);
            helper.assertTrue(ItemStack.matches(box, recovered), "Paper storage changed registered components or seal");
            helper.succeed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Paper storage experiment failed", failure); }
    }

    /** Actual return dispatch must leave both players' reserved sources untouched and deliver the exact surplus. */
    @SuppressWarnings("unchecked")
    private static void returnSharedStock(GameTestHelper helper, boolean mobile) {
        var f = ReceiveTests.setup(helper, 0);
        var player = f.player(); player.getInventory().clearContent();
        player.setPos(helper.absoluteVec(new Vec3(3, 2, 3))); helper.getLevel().addNewPlayer(player);
        UUID network = UUID.randomUUID();
        try {
            if (mobile) {
                var pos = new BlockPos(1, 1, 1);
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
                helper.setBlock(pos, block.defaultBlockState());
                block.setPlacedBy(helper.getLevel(), helper.absolutePos(pos), helper.getBlockState(pos), player, new ItemStack(block));
                var port = helper.getBlockEntity(pos);
                network = (UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
                var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
                join.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                        join.getConstructor(UUID.class, UUID.class).newInstance(player.getUUID(), network), player);
            } else {
                var tx = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
                tx.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, tx, true);
                tx.getItem().inventoryTick(tx, helper.getLevel(), player, 0, false);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
        f.ledger().setReturnAddress(f.handle().cacheId(), SupplyService.address(player));
        ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE), 100);
        var carrier = BuiltInRegistries.ITEM.get(ResourceLocation.parse(mobile ? "create_mobile_packages:robo_bee" : "cmpackagecouriers:cardboard_plane_parts"));
        ConsumptionTests.seed(player, 1, new ItemStack(carrier), 1);
        var before = f.record(); var edit = before.state().edit(); edit.thresholds(0, 0, 0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var other = TestPlayers.create(helper, TestPlayers.necklace(player).getStackInSlot(0).copy());
        reserveCursor(helper, other, 1, 1);
        ReturnService.check(player);
        helper.assertTrue(ConsumptionTests.stock(player, 0) == 100 && ConsumptionTests.stock(player, 1) == 1,
                "Return consumed the carrier reserved by another player");
        other.closeContainer();
        reserveCursor(helper, player, 0, 64);
        var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                player.getServer().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("stone_button")).orElseThrow();
        helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingService.place(other, recipe, false, false, true)
                == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK, "Shared recipe preparation failed");
        ReturnService.check(player);
        helper.assertTrue(ConsumptionTests.stock(player, 0) == 65 && ConsumptionTests.stock(player, 1) == 0
                && dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(f.handle().cacheId(), 0, null) == 65,
                "Return did not restrict cargo to S100-P64-C1=35, or consumed cached carrier twice");
        other.containerMenu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, other);
        helper.assertTrue(ConsumptionTests.stock(player, 0) == 64 && other.containerMenu.getCarried().is(Items.STONE_BUTTON),
                "Craft could not commit its distinct reserved item after return");
        other.closeContainer(); player.closeContainer();
        helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(f.handle().cacheId(), 0, null) == 0
                && ConsumptionTests.stock(player, 0) == 64, "Close after return consumed or restored already-spent cargo");
        ReturnService.check(player);
        helper.assertTrue(ConsumptionTests.stock(player, 0) == 64, "Carrier-less second check spent more stock");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(returnedCargo(player) == 35, "Return parcel has not arrived with exactly 35 stone"))
                .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_RETURN_SHARED_PASSED {} stock=64 crafted=1 delivered=35 carrier=0", mobile ? "mobile" : "paper"))
                .thenExecute(() -> prepareCarrierRefill(helper, f, carrier))
                .thenWaitUntil(() -> {
                    SupplyService.tick(player);
                    var packing = helper.absolutePos(new BlockPos(3, 1, 1));
                    var output = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            packing, net.minecraft.core.Direction.NORTH);
                    helper.assertTrue(output != null && PackageItem.isPackage(output.extractItem(0, 1, true)), "Waiting for real carrier replenishment package");
                    var box = output.extractItem(0, 1, false);
                    helper.assertTrue(dev.scathiard.feedmepackages.logistics.ReceiveService.receive(player, box) && box.isEmpty()
                            && ConsumptionTests.stock(player, 1) == 1 && ConsumptionTests.stock(player, 0) == 64,
                            "Carrier consumed by return was not replenished exactly once");
                })
                .thenExecute(() -> FeedMePackages.LOGGER.info("FMP_RETURN_CARRIER_REFILL_PASSED {} spent=1 factory=1 received=1", mobile ? "mobile" : "paper"))
                .thenSucceed();
    }
    private static void prepareCarrierRefill(GameTestHelper helper, ReceiveTests.Fixture f, net.minecraft.world.item.Item carrier) {
        var before = f.record(); var edit = before.state().edit(); edit.thresholds(0, 0, -1); edit.thresholds(1, 1, -1);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var packing = new BlockPos(3, 1, 1); var chestPos = packing.south(); var linkPos = packing.above();
        helper.setBlock(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        helper.setBlock(packing, com.simibubi.create.AllBlocks.PACKAGER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.logistics.packager.PackagerBlock.FACING, net.minecraft.core.Direction.NORTH));
        helper.setBlock(linkPos, com.simibubi.create.AllBlocks.STOCK_LINK.get().defaultBlockState()
                .setValue(com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)helper.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(carrier)); chest.setChanged();
        var link = (com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity)helper.getBlockEntity(linkPos);
        com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.remove(link.behaviour);
        link.behaviour.freqId = TestPlayers.necklace(f.player()).getStackInSlot(0).get(FmpRegistries.NETWORK.get());
        com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
    }
    private static void reserveCursor(GameTestHelper helper, net.minecraft.server.level.ServerPlayer player, int slot, int count) {
        var view = dev.scathiard.feedmepackages.interaction.CacheActions.open(player);
        var result = dev.scathiard.feedmepackages.interaction.CacheActions.execute(player,
                new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(), view.record().state().revision(),
                        dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_CURSOR, slot, count, -1, ""));
        helper.assertTrue(result == dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK, "Could not reserve cursor: " + result);
    }
    private static int returnedCargo(net.minecraft.server.level.ServerPlayer player) {
        int count = 0;
        for (var box : player.getInventory().items) {
            if (!PackageItem.isPackage(box) || !PackageItem.getAddress(box).equals(SupplyService.address(player))) continue;
            var contents = PackageItem.getContents(box);
            for (int i = 0; i < contents.getSlots(); i++) if (contents.getStackInSlot(i).is(Items.STONE)) count += contents.getStackInSlot(i).getCount();
        }
        return count;
    }

    private static void returnUnavailable(GameTestHelper helper, boolean mobile, boolean fullPort) {
        var f = ReceiveTests.setup(helper, 0); var player = f.player(); player.getInventory().clearContent();
        UUID network = UUID.randomUUID();
        if (fullPort) {
            var pos = new BlockPos(1, 1, 1);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
            helper.setBlock(pos, block.defaultBlockState());
            block.setPlacedBy(helper.getLevel(), helper.absolutePos(pos), helper.getBlockState(pos), player, new ItemStack(block));
            var port = helper.getBlockEntity(pos);
            try {
                network = (UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
                var bees = (net.neoforged.neoforge.items.ItemStackHandler)port.getClass().getMethod("getRoboBeeInventory").invoke(port);
                var bee = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));
                for (int i = 0; i < bees.getSlots(); i++) bees.setStackInSlot(i, new ItemStack(bee, bees.getSlotLimit(i)));
                helper.assertTrue(!(Boolean)port.getClass().getMethod("hasSpaceForPackageAndRobo").invoke(port), "Port fixture is not full");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
        f.ledger().setReturnAddress(f.handle().cacheId(), "unavailable-" + UUID.randomUUID());
        ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE), 100);
        var before = f.record(); var edit = before.state().edit(); edit.thresholds(0, 0, 0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var carrier = BuiltInRegistries.ITEM.get(ResourceLocation.parse(mobile ? "create_mobile_packages:robo_bee" : "cmpackagecouriers:cardboard_plane_parts"));
        player.getInventory().setItem(0, new ItemStack(carrier));
        before = f.record();
        player.setPos(helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
        // Native bees may fall back to ports on other networks. Bound only this synchronous probe's
        // native range so ports belonging to concurrent GameTests cannot turn a no-target fixture into a valid flight.
        try {
            net.createmod.catnip.config.ConfigBase.ConfigInt range = null; int previous = 0;
            if (mobile) {
                var configs = Class.forName("de.theidler.create_mobile_packages.index.config.CMPConfigs");
                var config = configs.getMethod("server").invoke(null);
                range = (net.createmod.catnip.config.ConfigBase.ConfigInt)config.getClass().getField("beeMaxDistance").get(config);
                previous = range.get(); range.set(2);
            }
            try { ReturnService.check(player); }
            finally { if (range != null) range.set(previous); }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        helper.assertTrue(f.record() == before && player.getInventory().getItem(0).getCount() == 1,
                "Unavailable return target consumed stock/carrier: mobile=" + mobile + " fullPort=" + fullPort
                        + " stock=" + ConsumptionTests.stock(player, 0) + " carrier=" + player.getInventory().getItem(0).getCount());
        helper.succeed();
    }

    private static void fluidNativeFlight(GameTestHelper h, boolean mobile) {
        var sample = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1);
        sample.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Factory flight water"));
        var f = SupplyTests.fluidSupply(h, sample, false); var player = f.player();
        var before = f.record(); var edit = before.fluids().state().edit(); edit.thresholds(0, 2, -1);
        f.ledger().replace(f.handle(), before.state().revision(), before.withFluids(before.fluids().withState(edit.finish())));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        // The empty template has no ground. Keep the author's rejected physical parcel out of the void.
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
        // Stay inside the 5x5x5 template: a fake player survives in its barrier wall, a dropped parcel does not.
        player.setPos(h.absoluteVec(new Vec3(3.5, 1, 3.5))); h.getLevel().addNewPlayer(player);
        var courier = fluidCourier(h, player, mobile);
        var inventory = player.getInventory().items.stream().map(ItemStack::copy).toList();
        var pos = new BlockPos(2, 1, 2); SupplyTests.placeFluidFactory(h, pos, 2);
        var parcels = new java.util.HashMap<UUID, Integer>(); var flown = new java.util.HashSet<UUID>();
        int[] phase = {0}; long[] waitingSince = {-1}, finishedAt = {-1};
        h.onEachTick(() -> {
            if (h.getTick() < 5) return;
            courier.refresh().run();
            h.assertTrue(h.getTick() < 1100, "Fluid flight stalled: phase=" + phase[0] + " parcels=" + parcels.size()
                    + " flown=" + flown.size() + " stock=" + f.record().fluids().state().cells().get(0).amount()
                    + " pending=" + f.record().fluids().state().pending(0) + " residual=" + f.record().anyResidual()
                    + " drops=" + fluidDrops(h, f).size() + " flights=" + returnTransitBoxes(player, mobile).size());
            if (phase[0] == 0) {
                // A registered player ticks naturally; request only at the production scheduler boundary.
                if (player.tickCount % 20 != 0) return;
                SupplyTests.fillSource(h, f, pos, 16000); SupplyTests.connect(h, pos, f.network());
                SupplyService.tick(player); phase[0] = 1;
                h.assertTrue(f.record().fluids().state().pending(0) == 16000
                         &&  SupplyTests.source(h, pos).getFluidInTank(0).isEmpty()
                        ,
                        "Native factory request did not reserve exactly 16000 mB: pending="
                                + f.record().fluids().state().pending(0) + " source="
                                + SupplyTests.source(h, pos).getFluidInTank(0).getAmount());
            }
            var box = SupplyTests.takeOutput(h, pos);
            if (!box.isEmpty()) {
                var contents = dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(box);
                var seal = box.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
                h.assertTrue(seal != null && ParcelAuthentication.validFluid(f.ledger(), player.registryAccess(), box, seal)
                        && net.neoforged.neoforge.fluids.FluidStack.isSameFluidSameComponents(sample, contents)
                        && (contents.getAmount() == 10000 || contents.getAmount() == 6000)
                        && parcels.putIfAbsent(seal.parcelId(), contents.getAmount()) == null,
                        "Factory output changed exact fluid, lost authentication, or reused a parcel");
                courier.send().accept(box);
                h.assertTrue(box.isEmpty(), "Factory-to-carrier wiring retained a second source copy");
            }
            for (var cargo : returnTransitBoxes(player, mobile)) {
                var seal = cargo.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
                h.assertTrue(seal != null && parcels.containsKey(seal.parcelId())
                        && ParcelAuthentication.validFluid(f.ledger(), player.registryAccess(), cargo, seal)
                        && dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(cargo).getAmount() == parcels.get(seal.parcelId()),
                        "Native carrier altered, forged or duplicated factory cargo");
                flown.add(seal.parcelId());
            }

            if (f.record().fluids().state().cells().get(0).amount() != 16000 || !courier.idle().getAsBoolean()) return;
            h.assertTrue(parcels.size() == 2  &&  flown.size() == 2
                     &&  f.record().fluids().state().orders().isEmpty()  &&  !f.record().anyResidual()
                     &&  SupplyTests.source(h, pos).getFluidInTank(0).isEmpty()  &&  fluidDrops(h, f).isEmpty()
                     &&  player.getOffhandItem().isEmpty(), "Full-chain delivery did not conserve stock, requests, cargo or contents");
            for (int i = 0; i < 36; i++) h.assertTrue(ItemStack.matches(player.getInventory().getItem(i), inventory.get(i)),
                    "Full-chain delivery changed ordinary inventory slot " + i);
            if (finishedAt[0] < 0) finishedAt[0] = h.getTick();
            if (h.getTick() - finishedAt[0] < 20) return;
            FeedMePackages.LOGGER.info("FMP_FLUID_NETWORK_FLIGHT_PASSED carrier={} source=16000 cache=16000 parcels=2 backpack=full",
                    mobile ? "mobile" : "paper"); h.succeed();
        });
    }

    private static List<Entity> fluidDrops(GameTestHelper h, SupplyTests.FluidSupply f) {
        return h.getLevel().getEntitiesOfClass(Entity.class, new AABB(f.player().blockPosition()).inflate(8), entity -> {
            var seal = droppedStack(entity).get(FmpRegistries.FLUID_PARCEL_SEAL.get());
            return seal != null && seal.cacheId().equals(f.handle().cacheId());
        });
    }

    private record FluidCourier(Consumer<ItemStack> send, Runnable refresh, java.util.function.BooleanSupplier idle) {}

    private static FluidCourier fluidCourier(GameTestHelper h, net.minecraft.server.level.ServerPlayer player, boolean mobile) {
        try {
            if (mobile) {
                var pos = new BlockPos(1, 1, 1);
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
                h.setBlock(pos, block.defaultBlockState());
                block.setPlacedBy(h.getLevel(), h.absolutePos(pos), h.getBlockState(pos), player, new ItemStack(block));
                var port = h.getBlockEntity(pos); var type = port.getClass();
                var bees = (net.neoforged.neoforge.items.ItemStackHandler)type.getMethod("getRoboBeeInventory").invoke(port);
                type.getMethod("addBeeToRoboBeeInventory", int.class).invoke(port, 2);
                type.getMethod("setBeeReturnModeEnabled", boolean.class).invoke(port, true);
                var network = (UUID)type.getMethod("getLogisticsNetworkId").invoke(port);
                var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
                join.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                        join.getConstructor(UUID.class, UUID.class).newInstance(player.getUUID(), network), player);
                var add = type.getMethod("addItemStack", ItemStack.class, boolean.class);
                return new FluidCourier(box -> {
                    try {
                        h.assertTrue((Boolean)add.invoke(port, box, true) && box.getCount() == 1, "Bee port simulation consumed factory output");
                        h.assertTrue((Boolean)add.invoke(port, box.copy(), false), "Bee port refused factory output"); box.setCount(0);
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                }, () -> {}, () -> returnTransitBoxes(player, true).isEmpty() && bees.getStackInSlot(0).getCount() == 2);
            }
            var tx = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
            tx.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, tx, true);
            player.getInventory().setItem(8, tx);
            Runnable publish = () -> tx.getItem().inventoryTick(tx, h.getLevel(), player, 8, false); publish.run();
            var launch = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager")
                    .getMethod("addPlane", Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class);
            return new FluidCourier(box -> {
                try {
                    publish.run(); h.assertTrue((Boolean)launch.invoke(null, h.getLevel(), player.position().add(0, 0, 3),
                            180F, 0F, box.copy(), false), "Native plane refused factory address"); box.setCount(0);
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }, publish, () -> returnTransitBoxes(player, false).isEmpty());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native fluid courier fixture failed", failure); }
    }

    private static ReceiveTests.Fixture returnFixture(GameTestHelper helper, boolean mobile) {
        var f = ReceiveTests.setup(helper, 0); var player = f.player(); player.getInventory().clearContent();
        player.setPos(helper.absoluteVec(new Vec3(3, 2, 3))); helper.getLevel().addNewPlayer(player);
        UUID network = UUID.randomUUID();
        try {
            // Both targets are genuinely available in the paper fallback experiment.
            if (ModList.get().isLoaded("create_mobile_packages")) {
                var pos = new BlockPos(1, 1, 1);
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
                helper.setBlock(pos, block.defaultBlockState());
                block.setPlacedBy(helper.getLevel(), helper.absolutePos(pos), helper.getBlockState(pos), player, new ItemStack(block));
                var port = helper.getBlockEntity(pos);
                network = (UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
                var join = Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
                join.getMethod("handle", net.minecraft.server.level.ServerPlayer.class).invoke(
                        join.getConstructor(UUID.class, UUID.class).newInstance(player.getUUID(), network), player);
            }
            if (!mobile) {
                var tx = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:location_transmitter")));
                tx.getItem().getClass().getMethod("setEnabled", ItemStack.class, boolean.class).invoke(null, tx, true);
                player.getInventory().setItem(8, tx);
                tx.getItem().inventoryTick(tx, helper.getLevel(), player, 0, false);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
        f.ledger().setReturnAddress(f.handle().cacheId(), SupplyService.address(player));
        ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE), 100);
        var before = f.record(); var edit = before.state().edit(); edit.thresholds(0, 0, 0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(mobile ? "create_mobile_packages:robo_bee" : "cmpackagecouriers:cardboard_plane_parts"));
        player.getInventory().setItem(0, new ItemStack(item, 2));
        return f;
    }

    private static dev.scathiard.feedmepackages.fluid.FluidVariantKey seedReturnFluid(ReceiveTests.Fixture f) {
        var stack = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("return fluid components"));
        var key = dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(stack, f.player().registryAccess());
        var before = f.record(); var items = before.state().edit(); items.thresholds(0, 0, -1);
        var fluids = before.fluids().state().edit(); fluids.filter(2, key); fluids.insert(2, key, 16000); fluids.thresholds(2, 0, 0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withStates(items.finish(), before.fluids().withState(fluids.finish())));
        return key;
    }

    private static long returnStock(ReceiveTests.Fixture f, boolean fluid) {
        return fluid ? f.record().fluids().state().cells().get(2).amount() : f.record().state().cells().get(0).amount();
    }

    private static int returnedFluid(net.minecraft.server.level.ServerPlayer player) {
        return player.getInventory().items.stream().filter(box -> PackageItem.isPackage(box)
                && PackageItem.getAddress(box).equals(SupplyService.address(player)))
                .mapToInt(box -> dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(box).getAmount()).sum();
    }

    private static void returnFluidFlight(GameTestHelper h, boolean mobile) {
        var f = returnFixture(h, mobile); var player = f.player(); var key = seedReturnFluid(f);
        var before = f.record(); var next = before;
        if (mobile) {
            // Cover a real carrier source in the item column of the same cache transaction.
            var carrier = player.getInventory().getItem(0); var items = next.state().edit();
            var carrierKey = dev.scathiard.feedmepackages.item.ItemVariantKey.of(carrier.copyWithCount(1), player.registryAccess());
            items.filter(1, carrierKey); items.insert(1, carrierKey, 2); next = next.withState(items.finish());
            player.getInventory().setItem(0, ItemStack.EMPTY);
        }
        f.ledger().replace(f.handle(), before.state().revision(), next);
        ReturnService.check(player);
        h.assertTrue(returnStock(f, true) == 6000, "First native return must ship 10000 mB");
        ReturnService.check(player);
        h.assertTrue(returnStock(f, true) == 0  &&  f.record().state().cells().get(0).amount() == 100
                 &&  player.getInventory().getItem(0).isEmpty()  &&  f.record().state().cells().get(1).amount() == 0,
                "Native fluid return failed to debit exact cargo and both original carriers");
        var boxes = returnTransitBoxes(player, mobile);
        var quantities = boxes.stream().map(dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages::read)
                .mapToInt(net.neoforged.neoforge.fluids.FluidStack::getAmount).sorted().toArray();
        h.assertTrue(java.util.Arrays.equals(quantities, new int[]{6000, 10000}), "Fluid return did not split at the author's actual parcel capacity");
        for (var box : boxes) assertFluidParcel(h, player, box, key);
        var settled = f.record(); ReturnService.check(player);
        h.assertTrue(f.record() == settled, "Fluid return reused the already emptied cache");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(returnedFluid(player) == 16000, "Native fluid return flight did not arrive intact"))
                .thenExecute(() -> {
                    for (var box : player.getInventory().items) if (PackageItem.isPackage(box)) assertFluidParcel(h, player, box, key);
                    h.assertTrue(returnStock(f, true) == 0 && returnTransitBoxes(player, mobile).isEmpty(), "Returned unsigned parcels were absorbed or duplicated");
                    FeedMePackages.LOGGER.info("FMP_FLUID_RETURN_FLIGHT_PASSED mobile={} amount=16000 packages=2 carriers=2", mobile);
                }).thenSucceed();
    }

    private static void returnFluidPreview(GameTestHelper h, boolean mobile) {
        var f=returnFixture(h,mobile); var player=f.player(); seedReturnFluid(f);
        var view=CacheActions.open(player);
        h.assertTrue(CacheActions.execute(player,new CacheActions.Intent(view.session(),view.record().state().revision(),
                CacheActions.Action.TAKE_FLUID,2,8000,0,""),1)==CacheActions.Result.OK,"Native return preview fixture failed");
        ReturnService.check(player);
        h.assertTrue(returnStock(f,true)==8000 && player.getInventory().getItem(0).getCount()==1
                && dev.scathiard.feedmepackages.interaction.FluidActions.preview(player).amount()==8000,
                "Return stole the player's reserved fluid or charged its carrier");
        h.assertTrue(returnTransitBoxes(player,mobile).stream().mapToInt(box ->
                dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(box).getAmount()).sum()==8000,
                "Reserved preview escaped into native transport");
        CacheActions.close(player); h.assertTrue(returnStock(f,true)==8000,"Cancel after return restored dispatched fluid");
        h.succeed();
    }

    private static void assertFluidParcel(GameTestHelper h, net.minecraft.server.level.ServerPlayer player, ItemStack box,
            dev.scathiard.feedmepackages.fluid.FluidVariantKey key) {
        var contents = dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(box);
        h.assertTrue(!contents.isEmpty() && key.equals(dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(contents, player.registryAccess()))
                && !box.has(FmpRegistries.PARCEL_SEAL.get()) && !box.has(FmpRegistries.FLUID_PARCEL_SEAL.get()),
                "Native return parcel changed exact fluid identity or acquired a false inbound receipt");
    }

    private static List<ItemStack> returnTransitBoxes(net.minecraft.server.level.ServerPlayer player, boolean mobile) {
        try {
            var boxes = new ArrayList<ItemStack>();
            if (mobile) {
                var type = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
                var manager = type.getMethod("get", ServerLevel.class).invoke(null, player.serverLevel());
                var robos = (java.util.Map<?,?>)type.getField("robos").get(manager);
                for (Object robo : robos.values()) boxes.add((ItemStack)robo.getClass().getMethod("getItemStack").invoke(robo));
            } else {
                var type = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
                var manager = type.getField("INSTANCE").get(null);
                var planes = (List<?>)manager.getClass().getField("pairedPlanes").get(manager);
                for (Object entry : planes) {
                    var plane = ((net.createmod.catnip.data.Pair<?,?>)entry).getFirst();
                    boxes.add((ItemStack)plane.getClass().getMethod("getPackage").invoke(plane));
                }
            }
            return boxes.stream().filter(box -> PackageItem.isPackage(box) && PackageItem.getAddress(box).equals(SupplyService.address(player))).toList();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
