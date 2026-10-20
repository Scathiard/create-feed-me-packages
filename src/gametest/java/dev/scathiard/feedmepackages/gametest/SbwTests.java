package dev.scathiard.feedmepackages.gametest;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
public final class SbwTests {
    @GameTestGenerator public static Collection<TestFunction> installed() { return ModList.get().isLoaded("superbwarfare")?Native.tests():List.of(); }
    private static final class Native {
        static Collection<TestFunction> tests() {
            return List.of(test("native_rifle_conversion_priority",Native::rifle),test("native_rocket_exact_type_and_access",Native::rocket),test("native_vehicle_seat_and_persistence",Native::vehicle),test("native_cancel_and_infinite",Native::cancel));
        }
        static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body) {
            return new TestFunction("sbw","fmp_sbw_"+name,FeedMePackages.MOD_ID+":empty",100,0,true,body);
        }
        static ItemStack item(String name) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("superbwarfare:"+name))); }
        static ServerPlayer player(GameTestHelper h) {
            h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.sbwCompatible(),"SBW exact native ammo ports disabled");
            var p=TestPlayers.real(h,UUID.randomUUID());TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);return p;
        }
        static void rifle(GameTestHelper h) {
            var p=player(h);var gunStack=item("ak_47");p.setItemInHand(InteractionHand.MAIN_HAND,gunStack);
            var gun=com.atsuishio.superbwarfare.data.gun.GunData.from(gunStack);gun.ammo.set(0);gun.virtualAmmo.set(0);
            var ammo=com.atsuishio.superbwarfare.data.gun.Ammo.RIFLE;var pack=ammo.getItemStack();int per=ammo.getItem().getAmmoToAdd();
            h.assertTrue(per>0 && !pack.isEmpty(),"Native rifle ammo conversion missing");
            ConsumptionTests.seed(p,0,pack,8);ammo.set(p,7);p.getInventory().setItem(1,pack.copy());
            int total=7+9*per;h.assertTrue(gun.countBackupAmmo(p)==total,"Native count omitted or multiplied a source incorrectly");
            gun.reloadAmmo(p);int loaded=gun.ammo.get();int spent=(int)(8-ConsumptionTests.stock(p,0));
            h.assertTrue(loaded>0 && spent==Math.max(0,(loaded-7-per+per-1)/per),"Native reserve/inventory did not precede cache");
            h.assertTrue(total==loaded+gun.virtualAmmo.get()+ammo.get(p)+p.getInventory().countItem(pack.getItem())*per+ConsumptionTests.stock(p,0)*per,"Native rifle conversion failed conservation");
            long stock=ConsumptionTests.stock(p,0);gun.withdrawAmmo(p);
            h.assertTrue(ConsumptionTests.stock(p,0)==stock,"Native unload wrote rounds back into cache");h.succeed();
        }
        static void rocket(GameTestHelper h) {
            var p=player(h);var stack=item("rpg");p.setItemInHand(InteractionHand.MAIN_HAND,stack);var gun=com.atsuishio.superbwarfare.data.gun.GunData.from(stack);gun.ammo.set(0);gun.virtualAmmo.set(0);
            ConsumptionTests.seed(p,0,item("rpg_rocket_standard"),5);
            h.assertTrue(gun.countBackupAmmo(p)==0,"Wrong rocket variant became ammunition");
            ConsumptionTests.seed(p,1,item("rpg_rocket_tbg"),5);h.assertTrue(gun.countBackupAmmo(p)==5,"Exact cache rocket unavailable");
            gun.reloadAmmo(p);h.assertTrue(gun.ammo.get()==1 && ConsumptionTests.stock(p,1)==4 && ConsumptionTests.stock(p,0)==5,"RPG reload did not debit exact type once");
            gun.ammo.set(0);h.assertTrue(gun.countBackupAmmo(p)==4,"Native recount fixture failed");
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE));gun.reloadAmmo(p);
            h.assertTrue(gun.ammo.get()==0 && ConsumptionTests.stock(p,1)==4,"Stale three-tick ammo count spent cache after switching gun");h.succeed();
        }
        static void cancel(GameTestHelper h) {
            var p=player(h);var stack=item("ak_47");p.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var gun=com.atsuishio.superbwarfare.data.gun.GunData.from(stack);gun.ammo.set(0);gun.virtualAmmo.set(0);
            ConsumptionTests.seed(p,0,com.atsuishio.superbwarfare.data.gun.Ammo.RIFLE.getItemStack(),8);
            gun.startReload();for(int i=0;i<3;i++)com.atsuishio.superbwarfare.event.GunEventHandler.INSTANCE.gunTick(p,gun,true,1.0);
            h.assertTrue(gun.ammo.get()==0 && ConsumptionTests.stock(p,0)==8,"Starting native timed reload spent before its completion");
            gun.resetStatus();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE));
            com.atsuishio.superbwarfare.event.GunEventHandler.INSTANCE.gunTick(p,gun,false,1.0);
            h.assertTrue(gun.ammo.get()==0 && ConsumptionTests.stock(p,0)==8,"Canceling native reload spent cached rounds");
            p.setItemInHand(InteractionHand.MAIN_HAND,stack);p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);gun.reloadAmmo(p);
            h.assertTrue(gun.ammo.get()>0 && ConsumptionTests.stock(p,0)==8,"Native creative/infinite reload consumed cache");h.succeed();
        }
        static void vehicle(GameTestHelper h) {
            var p=player(h);var passenger=player(h);
            var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("superbwarfare:ac_130h"));
            var v=(com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)type.create(h.getLevel());
            v.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(3,5,3)));
            h.assertTrue(p.startRiding(v,true) && v.changeSeat(p,2),"Native operator could not occupy weapon seat 2");
            h.assertTrue(passenger.startRiding(v,true),"Native passenger fixture failed");
            var gun=v.getGunData(2);h.assertTrue(gun!=null && dev.scathiard.feedmepackages.compat.sbw.SbwMaterials.operator(gun,v)==p,"Weapon-to-seat mapping failed");
            var shell=item("medium_shell_he");ConsumptionTests.seed(p,0,shell,20);ConsumptionTests.seed(passenger,0,shell,30);
            var nativeInventory=(net.neoforged.neoforge.items.IItemHandlerModifiable)v.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY);
            h.assertTrue(nativeInventory!=null && nativeInventory.getSlots()>0,"Native vehicle inventory missing");nativeInventory.setStackInSlot(0,shell.copyWithCount(6));
            gun.ammo.set(0);gun.virtualAmmo.set(0);gun.reloadAmmo(v);
            int loaded=gun.ammo.get();h.assertTrue(loaded>6 && nativeInventory.getStackInSlot(0).isEmpty() && ConsumptionTests.stock(p,0)==26-loaded && ConsumptionTests.stock(passenger,0)==30,"Vehicle did not use only actual gunner stock");
            // The native weapon tick completes its save after reload; GunData's mutable properties are not persisted before that boundary.
            com.atsuishio.superbwarfare.event.GunEventHandler.INSTANCE.gunTick(v,gun,true,1.0);
            var tag=new net.minecraft.nbt.CompoundTag();h.assertTrue(v.save(tag),"Native vehicle could not serialize");p.stopRiding();
            h.assertTrue(gun.ammo.get()==loaded,"Dismount removed already loaded ammunition");
            var restored=(com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)type.create(h.getLevel());restored.load(tag);
            h.assertTrue(restored.getGunData("Bofors").ammo.get()==loaded,"Native save/load lost loaded rounds");
            var path=p.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("fmp-test69-vehicle.nbt");
            try {
                if(java.nio.file.Files.exists(path)) {
                    var prior=net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    var reloaded=(com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)type.create(h.getLevel());reloaded.load(prior.getCompound("vehicle"));
                    h.assertTrue(reloaded.getGunData("Bofors").ammo.get()==prior.getInt("loaded"),"Second JVM lost native saved vehicle magazine");
                    FeedMePackages.LOGGER.info("FMP_SBW_PERSISTENCE priorJvmRead=true loaded={} path={}",prior.getInt("loaded"),path);
                } else FeedMePackages.LOGGER.info("FMP_SBW_PERSISTENCE priorJvmRead=false initialWrite=true path={}",path);
                var evidence=new net.minecraft.nbt.CompoundTag();evidence.put("vehicle",tag);evidence.putInt("loaded",loaded);
                net.minecraft.nbt.NbtIo.writeCompressed(evidence,path);
            } catch(java.io.IOException failure) {throw new IllegalStateException("Native vehicle persistence evidence failed",failure);}
            gun.ammo.set(0);h.assertTrue(gun.countBackupAmmo(v)==0,"Passenger/owner cache was used without a gunner");
            h.assertTrue(p.startRiding(v,true) && v.changeSeat(p,2),"Native remount failed");gun.countBackupAmmo(v);h.assertTrue(v.changeSeat(p,4),"Native seat switch failed");
            gun.reloadAmmo(v);h.assertTrue(gun.ammo.get()==0 && ConsumptionTests.stock(p,0)==26-loaded,"Pending reload consumed previous gunner cache after seat change");
            p.stopRiding();passenger.stopRiding();h.succeed();
        }
    }
}
