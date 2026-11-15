package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MixedReturnTests {
    @GameTest(template = "empty")
    public static void addressedPaperFlightDeliversMixedCargoAndChargesOneCarrier(GameTestHelper h) throws ReflectiveOperationException {
        var parts = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
        if (parts == Items.AIR) { h.succeed(); return; }
        var target = TestPlayers.real(h, UUID.randomUUID());
        PhantomCompatibilityTests.register(h, target);
        h.getLevel().addNewPlayer(target);
        try {
            var type = Class.forName("com.kreidev.cmpackagecouriers.CourierTarget");
            type.getMethod("addOrUpdateTarget", type).invoke(null, type.getConstructor(String.class, Entity.class)
                    .newInstance(target.getGameProfile().getName(), target));
            var player = TestPlayers.create(h, FmpRegistries.PENDANT.toStack());
            TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
            var handle = AccessGate.resolve(player).handle();
            var ledger = CacheLedger.get(player.getServer());
            String address = "@" + target.getGameProfile().getName();
            ledger.setReturnAddress(handle.cacheId(), address);
            var metals = List.of(Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE);
            var initial = ledger.find(handle.cacheId()); var edit = initial.state().edit();
            for (int level=2;level<=5;level++) edit.upgrade();
            for (int slot=0; slot<3; slot++) {
                var key = ItemVariantKey.of(new ItemStack(metals.get(slot)), player.registryAccess());
                edit.filter(slot,key); edit.settings(slot,new CellSettings(-1,0,1,-1,64));
                h.assertTrue(edit.insert(slot,key,(4-slot)*64)==(4-slot)*64, "Fixture could not store all cargo");
            }
            var carrier = ItemVariantKey.of(new ItemStack(parts),player.registryAccess());
            edit.filter(3,carrier); edit.settings(3,new CellSettings(-1,0,1,-1,1)); edit.insert(3,carrier,1);
            ledger.replace(handle,initial.state().revision(),initial.withState(edit.finish()));
            var manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            var data = manager.getField("INSTANCE").get(null);
            var flights = (List<?>) data.getClass().getField("pairedPlanes").get(data);
            int count = flights.size();
            ReturnService.check(player);
            h.assertTrue(flights.size() == count + 1, "Three cargo types should launch exactly one addressed plane");
            var cargo = ledger.find(handle.cacheId()).state().cells();
            h.assertTrue(cargo.subList(0,4).stream().allMatch(c -> c.amount()==0), "Wrong stock or carrier debit");
            var pair = (net.createmod.catnip.data.Pair<?,?>) flights.getLast();
            var plane = pair.getFirst();
            var box = (ItemStack)plane.getClass().getField("box").get(plane);
            h.assertTrue(address.equals(PackageItem.getAddress(box)), "Native parcel address changed");
            var units = PackageItem.getContents(box);
            int i=0;
            for (int slot=0;slot<3;slot++) for(int stack=0;stack<4-slot;stack++,i++)
                h.assertTrue(units.getStackInSlot(i).is(metals.get(slot)) && units.getStackInSlot(i).getCount()==64,
                        "Incorrect package stack " + i);
            plane.getClass().getMethod("onReachedTarget",net.minecraft.server.MinecraftServer.class)
                    .invoke(plane,player.getServer());
            h.assertTrue(target.getInventory().items.stream().anyMatch(s -> ItemStack.matches(s,box)),
                    "Native arrival did not put the mixed parcel into the addressed recipient inventory");
            plane.getClass().getField("forRemoval").setBoolean(plane,true);

            // With only one package slot left, a copper floor of 128 cannot be partially included.
            var before = ledger.find(handle.cacheId()); edit=before.state().edit();
            var iron = before.state().cells().get(0).filter(); var copper=before.state().cells().get(1).filter();
            edit.insert(0,iron,512); edit.insert(1,copper,128);
            edit.settings(1,new CellSettings(-1,0,1,-1,128)); edit.insert(3,carrier,1);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish())); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(0).amount()==0 && before.state().cells().get(1).amount()==128
                    && before.state().cells().get(3).amount()==0,"Small remainder broke floor or spent the carrier twice");
            player.getInventory().setItem(0,new ItemStack(parts)); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(1).amount()==0
                    && PackageItem.getContents(lastBox(flights)).getStackInSlot(1).is(Items.COPPER_INGOT),
                    "Second carrier did not send the entire two-stack copper line");
            // A failed dispatch and a non-multiple surplus must have distinct outcomes.
            edit=before.state().edit(); edit.extract(1,128); edit.insert(0,iron,600);
            edit.settings(0,new CellSettings(-1,512,1,-1,64)); edit.insert(3,carrier,1);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            ledger.setReturnAddress(handle.cacheId(),"@no_such_target_031"); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(0).amount()==600 && before.state().cells().get(3).amount()==1,
                    "Rejected address spent cargo or the carrier");
            ledger.setReturnAddress(handle.cacheId(),address); ReturnService.check(player);
            h.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount()==512,
                    "Return floor 64 rounded a surplus of 88 to 64");
            before=ledger.find(handle.cacheId()); edit=before.state().edit(); edit.extract(0,512);
            edit.settings(0,new CellSettings(-1,0,1,-1,64));
            edit.settings(1,new CellSettings(-1,0,1,-1,64));
            edit.insert(0,iron,64); edit.insert(1,copper,64);
            edit.insert(2,before.state().cells().get(2).filter(),63);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            player.getInventory().setItem(0,new ItemStack(parts)); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(2).amount()==63 && before.state().cells().get(0).amount()==0
                    && before.state().cells().get(1).amount()==0 && PackageItem.getContents(lastBox(flights)).getStackInSlot(2).isEmpty(),
                    "A partial mixed parcel waited for more cargo or charged a line below its floor");
            edit=before.state().edit(); edit.insert(0,iron,576); edit.insert(1,copper,64);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            player.getInventory().setItem(0,new ItemStack(parts)); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(0).amount()==0 && before.state().cells().get(1).amount()==64,
                    "A full nine-stack first line was displaced");
            edit=before.state().edit(); edit.insert(0,iron,64); edit.insert(1,copper,512);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            player.getInventory().setItem(0,new ItemStack(parts)); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            h.assertTrue(before.state().cells().get(0).amount()==0 && before.state().cells().get(1).amount()==64
                    && PackageItem.getContents(lastBox(flights)).getStackInSlot(8).is(Items.COPPER_INGOT),
                    "A one-stack first line did not share its parcel with eight stacks from the next line");
            // A new parcel starts from the remainder of the queue head, even when the next cell triggered this pass.
            edit=before.state().edit(); edit.insert(0,iron,640);
            ledger.replace(handle,before.state().revision(),before.withState(edit.finish()));
            player.getInventory().setItem(0,new ItemStack(parts,2)); ReturnService.check(player);
            before=ledger.find(handle.cacheId());
            var last=PackageItem.getContents(lastBox(flights));
            h.assertTrue(before.state().cells().get(0).amount()==0 && before.state().cells().get(1).amount()==0
                    && last.getStackInSlot(0).is(Items.IRON_INGOT) && last.getStackInSlot(1).is(Items.COPPER_INGOT),
                    "Second parcel skipped the queue head's remaining stack");
            if (net.neoforged.fml.ModList.get().isLoaded("fluidlogistics")) {
                checkSeparateFluidParcel(h, player, parts, flights, address);
            }
            FeedMePackages.LOGGER.info("FMP031_MIXED_RETURN_PASSED recipient={} received=4+3+2 floors=128,64 partial=true rejectedUnchanged=true",
                    target.getGameProfile().getName());
        } finally {
            h.getLevel().removePlayerImmediately(target,Entity.RemovalReason.DISCARDED);
            PhantomCompatibilityTests.unregister(h,target);
        }
        h.succeed();
    }
    private static void checkSeparateFluidParcel(GameTestHelper h, ServerPlayer player, Item parts, List<?> flights,
                                                 String address) throws ReflectiveOperationException {
        h.assertTrue(FluidPackages.status() == FluidPackages.Status.READY, "Installed fluid transport is unavailable");
        var ledger = CacheLedger.get(player.getServer());
        var handle = AccessGate.resolve(player).handle();
        var record = ledger.find(handle.cacheId());
        var items = record.state().edit();
        items.insert(0, record.state().cells().getFirst().filter(), 64);
        var water = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        var key = FluidVariantKey.of(water, player.registryAccess());
        var fluids = record.fluids().state().edit();
        fluids.filter(4, key);
        fluids.insert(4, key, 1000);
        fluids.thresholds(4, 0, 0);
        ledger.replace(handle, record.state().revision(), record.withStates(items.finish(), record.fluids().withState(fluids.finish())));
        player.getInventory().setItem(0, new ItemStack(parts, 2));
        int start = flights.size();
        ReturnService.check(player);
        h.assertTrue(flights.size() == start + 2, "An ordinary return and pure fluid require separate parcels");
        var dry = boxFromFlight(flights.get(start));
        var wet = boxFromFlight(flights.get(start + 1));
        var dryContents = PackageItem.getContents(dry);
        h.assertTrue(!FluidPackages.isFamily(dry) && dryContents.getStackInSlot(0).is(Items.IRON_INGOT)
                && dryContents.getStackInSlot(0).getCount() == 64 && dryContents.getStackInSlot(1).isEmpty(),
                "Ordinary parcel lost its iron or took fluid cargo");
        var liquid = FluidPackages.read(wet);
        h.assertTrue(FluidPackages.isFamily(wet) && liquid.getFluid() == water.getFluid() && liquid.getAmount() == 1000
                && address.equals(PackageItem.getAddress(dry)) && address.equals(PackageItem.getAddress(wet)),
                "Native fluid parcel lost its water or return address");
        var after = ledger.find(handle.cacheId());
        h.assertTrue(after.state().cells().getFirst().amount() == 0 && after.fluids().state().cells().get(4).amount() == 0
                && player.getInventory().getItem(0).isEmpty(), "The two parcels did not charge the exact cargo and carriers");
        FeedMePackages.LOGGER.info("FMP031_SEPARATE_FLUID_PASSED ordinary=64iron fluid=1000water carriers=2");
    }

    private static ItemStack lastBox(List<?> flights) throws ReflectiveOperationException {
        return boxFromFlight(flights.getLast());
    }

    private static ItemStack boxFromFlight(Object flight) throws ReflectiveOperationException {
        var pair=(net.createmod.catnip.data.Pair<?,?>)flight;
        return (ItemStack)pair.getFirst().getClass().getField("box").get(pair.getFirst());
    }
}
