package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageEntity;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;
import java.util.function.Consumer;

/** Current ordinary 0.3 native Phantom tests. Historical frozen-version probe evidence stays in its original files. */
@GameTestHolder("fmp_phantom_compat")
public final class PhantomCompatibilityTests {
    private static final String HELPER = "com.yision.phantom.logistics.courier.AirCourierHelper";
    private static final String MINI = "com.yision.phantom.item.miniphantom.MiniPhantomItem";

    @GameTestGenerator
    public static Collection<TestFunction> installedPhantom() {
        if (!ModList.get().isLoaded("createphantom")) return List.of();
        return List.of(test("address", PhantomCompatibilityTests::address),
                test("full_and_residual", PhantomCompatibilityTests::fullAndResidual),
                test("unworn", PhantomCompatibilityTests::unworn),
                test("ordinary_parcel", PhantomCompatibilityTests::ordinaryParcel),
                test("return_waits_for_destination", PhantomCompatibilityTests::returnWaitsForDestination),
                test("return_native_port_and_carrier", h -> returnNativePort(h, false)),
                test("return_cached_carrier", h -> returnNativePort(h, true)),
                test("return_full_port", PhantomCompatibilityTests::returnFullPort),
                test("network_flight", PhantomCompatibilityTests::networkFlight));
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction("phantom_compat", "fmp_phantom_" + name,
                "fmp_phantom_compat:empty", 1600, 0, true, body);
    }

    private static Object invoke(String type, String name, Class<?>[] signature, Object... args) {
        try { return Class.forName(type).getMethod(name, signature).invoke(null, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(type + "." + name, failure); }
    }

    private static Object find(ServerLevel level, ItemStack box) {
        return invoke(HELPER, "findTargetPlayer", new Class<?>[]{ServerLevel.class, ItemStack.class}, level, box);
    }

    private static void handoff(GameTestHelper h, ServerPlayer player, ItemStack box) {
        h.assertTrue(Boolean.TRUE.equals(invoke(HELPER, "deliverPackageOnly",
                new Class<?>[]{ServerPlayer.class, ItemStack.class}, player, box)), "Native handoff rejected package");
        // Phantom copies its argument. Its caller relinquishes the source after this successful handoff.
        box.setCount(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> onlineById(ServerLevel level) {
        try {
            var field = PlayerList.class.getDeclaredField("playersByUUID"); field.setAccessible(true);
            return (Map<UUID, ServerPlayer>)field.get(level.getServer().getPlayerList());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void register(GameTestHelper h, ServerPlayer player) {
        player.setPos(h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        // Native online-player synchronization inspects negotiated channels even without a remote client.
        TestPlayers.nativePackets(player);
        new io.netty.channel.embedded.EmbeddedChannel(player.connection.getConnection());
        TestPlayers.nativeSavePlayers(h.getLevel().getServer()).add(player);
        onlineById(h.getLevel()).put(player.getUUID(), player);
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                "Fixture recipient is absent from native player lookup");
    }

    private static void unregister(GameTestHelper h, ServerPlayer player) {
        TestPlayers.nativeSavePlayers(h.getLevel().getServer()).remove(player);
        onlineById(h.getLevel()).remove(player.getUUID(), player);
        ((io.netty.channel.embedded.EmbeddedChannel)player.connection.getConnection().channel()).finishAndReleaseAll();
    }

    private static void address(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); register(h, f.player());
        try {
            var box = f.box(64);
            h.assertTrue(find(h.getLevel(), box) == f.player(), "Native Phantom does not recognize FMP@player");
            PackageItem.addAddress(box, SupplyService.address(f.player()) + " // optional comment");
            h.assertTrue(find(h.getLevel(), box) == f.player(), "Native commented address mismatch");
            PackageItem.addAddress(box, "FMP@NoSuchRecipient" + UUID.randomUUID());
            h.assertTrue(find(h.getLevel(), box) == null, "Missing target was silently redirected to another player");
            h.assertTrue(f.record().state().pending(0) == 64 && f.record().state().cells().getFirst().amount() == 0,
                    "Read-only native lookup altered cache or pending stock");
            passed("address recognizes_exact_FMP_and_comment; missing_player_unresolved");
        } finally { unregister(h, f.player()); }
        h.succeed();
    }

    private static void fullAndResidual(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); f.player().setPos(h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        handoff(h, f.player(), f.box(64));
        h.assertTrue(f.record().state().cells().getFirst().amount() == 64 && f.record().state().orders().isEmpty(),
                "Native full-backpack delivery failed to unpack or settle request");
        handoff(h, f.player(), f.box(48));
        handoff(h, f.player(), f.box(32));
        h.assertTrue(f.record().state().cells().getFirst().amount() == ReceiveTests.FULL
                && cargo(f.residual()) == 16, "Partial native handoff lost its residual");
        handoff(h, f.player(), f.box(16));
        h.startSequence().thenWaitUntil(() -> {
            var drops = h.getLevel().getEntitiesOfClass(Entity.class, new AABB(f.player().blockPosition()).inflate(4), e -> {
                var seal = droppedStack(e).get(FmpRegistries.PARCEL_SEAL.get());
                return seal != null && seal.cacheId().equals(f.handle().cacheId());
            });
            h.assertTrue(drops.size() == 1 && cargo(droppedStack(drops.getFirst())) == 16,
                    "Busy residual fallback did not leave exactly one intact physical parcel: entities=" + drops.size());
        }).thenExecute(() -> {
            f.extract(64); ReceiveService.resume(f.player());
            h.assertTrue(f.record().state().cells().getFirst().amount() == 80 && f.residual().isEmpty(),
                    "Residual resume was not conservative");
            for (int slot = 0; slot < 36; slot++) h.assertTrue(f.player().getInventory().getItem(slot).is(Items.DIRT)
                    && f.player().getInventory().getItem(slot).getCount() == 64, "Delivery displaced the player's inventory");
            passed("full_backpack_and_partial_acceptance; busy_residual_drops_intact_package");
        }).thenSucceed();
    }

    private static void unworn(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); f.player().getInventory().setItem(0, ItemStack.EMPTY);
        TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        var box = f.box(64); var expected = box.copy(); handoff(h, f.player(), box);
        h.assertTrue(ItemStack.matches(expected, f.player().getInventory().getItem(0))
                && f.record().state().cells().getFirst().amount() == 0 && f.record().state().pending(0) == 64,
                "Unworn pendant silently used its cache or lost its unopened parcel");
        passed("unworn_keeps_sealed_parcel_in_inventory"); h.succeed();
    }

    private static void ordinaryParcel(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); f.player().getInventory().setItem(0, ItemStack.EMPTY);
        var box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 3)));
        PackageItem.addAddress(box, f.player().getGameProfile().getName()); var expected = box.copy();
        handoff(h, f.player(), box);
        h.assertTrue(ItemStack.matches(expected, f.player().getInventory().getItem(0))
                && f.record().state().cells().getFirst().amount() == 0 && f.record().state().pending(0) == 64,
                "Ordinary unsealed parcel was intercepted or settled an FMP request");
        passed("ordinary_parcel_untouched"); h.succeed();
    }

    private static void returnWaitsForDestination(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 100); f.player().getInventory().clearContent();
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        f.ledger().setReturnAddress(f.handle().cacheId(), "phantom-return-fixture");
        var before = f.record(); var edit = before.state().edit(); edit.thresholds(0, 0, 0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createphantom:mini_phantom"));
        h.assertTrue(item != Items.AIR, "Official Phantom carrier item missing");
        f.player().getInventory().setItem(0, new ItemStack(item)); before = f.record();
        ReturnService.check(f.player());
        h.assertTrue(f.record() == before && f.player().getInventory().getItem(0).getCount() == 1,
                "Missing target changed stock or consumed a Phantom");
        passed("return_rejected_missing_target_stock_and_carrier_unchanged"); h.succeed();
    }

    private static void networkFlight(GameTestHelper h) {
        var f = ReceiveTests.setup(h, 0); register(h, f.player());
        var outbound = SupplyTests.outboundFromNetwork(h, f);
        var sender = TestPlayers.create(h, ItemStack.EMPTY);
        sender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIREWORK_ROCKET));
        h.startSequence().thenWaitUntil(() -> {
            var box = outbound.get();
            h.assertTrue(find(h.getLevel(), box) == f.player(), "Real packed request did not resolve its player");
            var loaded = (ItemStack)invoke(MINI, "createLoaded", new Class<?>[]{ItemStack.class}, box);
            var preserved = (ItemStack)invoke(MINI, "copyCargoPackage", new Class<?>[]{ItemStack.class}, loaded);
            h.assertTrue(ItemStack.matches(box, preserved), "Carrier wrapping lost the real parcel components");
            box.setCount(0);
            var courier = (Entity)invoke("com.yision.phantom.entity.courier.AirCourierEntity", "createWaiting",
                    new Class<?>[]{Level.class, ItemStack.class, Vec3.class}, h.getLevel(), preserved, new Vec3(0, 0, 1));
            courier.setPos(f.player().position().add(0, 1, -6));
            h.assertTrue(h.getLevel().addFreshEntity(courier), "Native courier entity was not created");
            loaded.setCount(0);
            try { courier.getClass().getMethod("interact", Player.class, InteractionHand.class)
                    .invoke(courier, sender, InteractionHand.MAIN_HAND); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            h.assertTrue(courier.isRemoved() && sender.getMainHandItem().isEmpty(),
                    "Native firework launch failed to consume its courier and firework");
        }).thenWaitUntil(() -> h.assertTrue(f.record().state().cells().getFirst().amount() == 64,
                "Real Phantom flight has not delivered into the full backpack cache"))
          .thenExecute(() -> {
              h.assertTrue(f.record().state().orders().isEmpty(), "Flight did not settle the actual factory request");
              for (int slot = 0; slot < 36; slot++) h.assertTrue(f.player().getInventory().getItem(slot).is(Items.DIRT)
                      && f.player().getInventory().getItem(slot).getCount() == 64, "Flight displaced inventory");
              unregister(h, f.player());
              passed("NETWORK_FLIGHT source=64 cache=64 pending=0 backpack=full native_firework_launch=true");
          }).thenSucceed();
    }


    private static com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity port(GameTestHelper h, String address) {
        var pos=new net.minecraft.core.BlockPos(3,2,3);
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createphantom:phantomport"));
        h.assertTrue(block!=net.minecraft.world.level.block.Blocks.AIR,"Native Phantom port missing");h.setBlock(pos,block.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,net.minecraft.core.Direction.EAST));
        var port=(com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity)h.getBlockEntity(pos);
        port.addressFilter=address;port.setChanged();
        invoke("com.yision.phantom.block.phantomport.PhantomPortTargetRegistry","update",
                new Class<?>[]{ServerLevel.class,net.minecraft.core.BlockPos.class,String.class},h.getLevel(),h.absolutePos(pos),address);
        return port;
    }
    /** A real powered two-block runway, with the native extraction funnel required for empty returns. */
    private static void returnRunway(GameTestHelper h) {
        var east=net.minecraft.core.Direction.EAST;
        var first=new net.minecraft.core.BlockPos(4,1,3);
        var state=com.simibubi.create.AllBlocks.BELT.getDefaultState()
                .setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.HORIZONTAL_FACING,east)
                .setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.SLOPE,com.simibubi.create.content.kinetics.belt.BeltSlope.HORIZONTAL);
        h.setBlock(first,state.setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.PART,com.simibubi.create.content.kinetics.belt.BeltPart.START));
        h.setBlock(first.east(),state.setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.PART,com.simibubi.create.content.kinetics.belt.BeltPart.END));
        com.simibubi.create.content.kinetics.belt.BeltBlock.initBelt(h.getLevel(),h.absolutePos(first));
        h.setBlock(first.north(),com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING,net.minecraft.core.Direction.SOUTH));
        var motor=(com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)h.getBlockEntity(first.north());
        motor.generatedSpeed.setValue(-256);
        h.setBlock(first.above(),com.simibubi.create.AllBlocks.ANDESITE_BELT_FUNNEL.getDefaultState()
                .setValue(com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.HORIZONTAL_FACING,east)
                .setValue(com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.SHAPE,com.simibubi.create.content.logistics.funnel.BeltFunnelBlock.Shape.PUSHING));
    }
    private static ReceiveTests.Fixture returnFixture(GameTestHelper h, boolean cached, String address) {
        var f=ReceiveTests.setup(h,100);f.player().getInventory().clearContent();register(h,f.player());
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(),UUID.randomUUID());
        var carrier=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createphantom:mini_phantom")));
        var before=f.record();var edit=before.state().edit();edit.settings(0,new dev.scathiard.feedmepackages.domain.CellSettings(-1,64,1,-1));
        if(cached){var key=dev.scathiard.feedmepackages.item.ItemVariantKey.of(carrier,f.player().registryAccess());edit.filter(1,key);edit.insert(1,key,3);}
        else f.player().getInventory().setItem(0,carrier);
        f.ledger().replace(f.handle(),before.state().revision(),before.withState(edit.finish()));f.ledger().setReturnAddress(f.handle().cacheId(),address);
        return f;
    }
    private static int carrierCount(ServerPlayer p) {
        int n=0;var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse("createphantom:mini_phantom"));
        for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;
    }
    private static void returnNativePort(GameTestHelper h, boolean cached) {
        String address="FmpReturn-"+UUID.randomUUID();var f=returnFixture(h,cached,address);var destination=port(h,address);returnRunway(h);
        h.assertTrue(dev.scathiard.feedmepackages.compat.PhantomDispatch.ready(),"Native Phantom task adapter unavailable");
        try(var signals=new dev.scathiard.feedmepackages.logistics.LogisticsSignals.Viewer(f.handle().cacheId())) {
            signals.page(0,9);ReturnService.check(f.player());
            h.assertTrue(f.record().state().cells().getFirst().amount()==64 && carrierCount(f.player())==0
                    && (!cached || f.record().state().cells().get(1).amount()==2)
                    && f.ledger().returnEscrow(f.handle().cacheId())==null,"Native dispatch did not settle exact cargo and one carrier");
            h.assertTrue(signals.get(0).kind()==dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.DEPARTURE,"Accepted native return omitted departure signal");
        }
        ReturnService.check(f.player());
        h.startSequence().thenWaitUntil(()->{
            int count=0;for(int i=0;i<destination.inventory.getSlots();i++)count+=cargo(destination.inventory.getStackInSlot(i));
            h.assertTrue(count==36,"Native flight has not delivered exactly 36 items to the port");
            var belt=(com.simibubi.create.content.kinetics.belt.BeltBlockEntity)h.getBlockEntity(new net.minecraft.core.BlockPos(4,1,3));
            h.assertTrue(carrierCount(f.player())==1,"Native empty carrier has not returned; runway speed="+belt.getSpeed()
                    +" length="+belt.beltLength+" direction="+belt.getMovementFacing());
        }).thenIdle(30).thenExecute(()->{
            ReturnService.check(f.player());
            h.assertTrue(f.record().state().cells().getFirst().amount()==64 && carrierCount(f.player())==1,"Settled return repeated");
            unregister(h,f.player());passed("RETURN_NATIVE_PORT carrier_source="+(cached?"cache":"inventory")+" cargo=36 carrier_recovered=1");
        }).thenSucceed();
    }
    private static void returnFullPort(GameTestHelper h) {
        String address="FmpFull-"+UUID.randomUUID();var f=returnFixture(h,false,address);var destination=port(h,address);
        var filler=PackageItem.containing(List.of(new ItemStack(Items.DIRT,64)));PackageItem.addAddress(filler,address);
        for(int i=0;i<destination.inventory.getSlots();i++)destination.inventory.setStackInSlot(i,filler.copy());
        var before=f.record();ReturnService.check(f.player());
        h.assertTrue(f.record()==before && carrierCount(f.player())==1 && f.ledger().returnEscrow(f.handle().cacheId())==null,"Full native destination lost cargo or carrier");
        // An occupied carrier is never overwritten when a destination later opens.
        destination.inventory.setStackInSlot(0,ItemStack.EMPTY);
        var loaded=(ItemStack)invoke(MINI,"createLoaded",new Class<?>[]{ItemStack.class},filler);
        f.player().getInventory().setItem(0,loaded);ReturnService.check(f.player());
        h.assertTrue(f.record()==before && ItemStack.matches(loaded,f.player().getInventory().getItem(0)),"Occupied Phantom cargo was overwritten");
        unregister(h,f.player());passed("RETURN_FULL_PORT_AND_OCCUPIED_CARRIER preserved=true");h.succeed();
    }

    private static int cargo(ItemStack box) {
        if (!PackageItem.isPackage(box)) return 0;
        var content = PackageItem.getContents(box); int count = 0;
        for (int i = 0; i < content.getSlots(); i++) count += content.getStackInSlot(i).getCount();
        return count;
    }

    private static ItemStack droppedStack(Entity entity) {
        return entity instanceof PackageEntity parcel ? parcel.getBox() : entity instanceof ItemEntity item ? item.getItem() : ItemStack.EMPTY;
    }

    private static void passed(String detail) {
        FeedMePackages.LOGGER.info("FMP_PHANTOM_PROBE {} fmp={} phantom={}", detail,
                ModList.get().getModContainerById(FeedMePackages.MOD_ID).orElseThrow().getModInfo().getVersion(),
                ModList.get().getModContainerById("createphantom").orElseThrow().getModInfo().getVersion());
    }
}
