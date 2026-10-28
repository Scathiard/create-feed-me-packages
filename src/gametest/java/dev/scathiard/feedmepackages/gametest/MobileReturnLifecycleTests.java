package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.*;

/** One real unsealed return survives three JVMs. Saved expectations never recreate cargo or carriers. */
@net.neoforged.fml.common.EventBusSubscriber(modid=FeedMePackages.MOD_ID)
@net.neoforged.neoforge.gametest.GameTestHolder(FeedMePackages.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class MobileReturnLifecycleTests {
    private static final UUID PROCESS=UUID.randomUUID();
    private static final BlockPos PORT=new BlockPos(129,65,129);
    private static final String MANAGER="de.theidler.create_mobile_packages.robo.RoboManager";
    @net.neoforged.bus.api.SubscribeEvent public static void prepare(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        if(System.getProperty("fmp.mobileReturnFlight", "").isEmpty())return;
        var level=event.getServer().overworld();var chunk=new ChunkPos(PORT);
        level.setChunkForced(chunk.x,chunk.z,true);level.getChunkSource().updateChunkForced(chunk,true);level.getChunk(chunk.x,chunk.z);
    }
    private static ServerPlayer player(GameTestHelper h, UUID id, boolean load, Vec3 initial) {
        var player=TestPlayers.real(h,id);
        PhantomCompatibilityTests.register(h,player);
        if(load)h.assertTrue(h.getLevel().getServer().getPlayerList().load(player).isPresent(),"Original player file missing");
        else player.setPos(initial);
        player.setNoGravity(true);h.getLevel().addNewPlayer(player);return player;
    }
    private static Map<?,?> robos(ServerLevel level) throws ReflectiveOperationException {
        var type=Class.forName(MANAGER);return (Map<?,?>)type.getField("robos").get(type.getMethod("get",ServerLevel.class).invoke(null,level));
    }
    private static ItemStack cargo(Object robo) throws ReflectiveOperationException {
        return (ItemStack)robo.getClass().getMethod("getItemStack").invoke(robo);
    }
    private static int carried(LivingEntity source, Item item) {
        if(source instanceof ServerPlayer p)return p.getInventory().countItem(item);
        int count=0;var inv=MaidCompatibility.inventory(source);
        for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(item))count+=inv.getStackInSlot(i).getCount();
        for(var stack:source.getHandSlots())if(stack.is(item))count+=stack.getCount();return count;
    }
    private static int delivered(ServerLevel level, ItemStack original) {
        int total=0;
        var port=(com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity)level.getBlockEntity(PORT);
        if(port==null)return 0;
        for(int slot=0;slot<port.inventory.getSlots();slot++) {
            var box=port.inventory.getStackInSlot(slot);
            if(!PackageItem.isPackage(box))continue;
            var items=PackageItem.getContents(box);
            for(int i=0;i<items.getSlots();i++)if(ItemStack.isSameItemSameComponents(items.getStackInSlot(i),original))total+=items.getStackInSlot(i).getCount();
        }
        return total;
    }
    @GameTest(template="empty",templateNamespace="fmp_mobile_return",timeoutTicks=2000)
    public static void originalReturnAcrossProcesses(GameTestHelper h) throws ReflectiveOperationException {
        String[] mode=System.getProperty("fmp.mobileReturnFlight", "").split(":");
        h.assertTrue(mode.length==3,"Dedicated lifecycle arguments missing");
        boolean maid=mode[0].equals("maid");String phase=mode[1];var level=h.getLevel();var server=level.getServer();
        var probe=level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Probe::new,Probe::load),"fmp_mobile_return_probe");
        var ledger=CacheLedger.get(server);var bee=BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));
        if(phase.equals("write")) {
            h.assertTrue(probe.data.isEmpty(),"New lifecycle batch required");
            var book=new ItemStack(Items.ENCHANTED_BOOK);
            book.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY),3);
            LivingEntity source;CacheHandle handle;ItemStack pendant;
            if(maid){var f=MaidTests.setup(h,book,2);source=f.maid();handle=f.handle();pendant=f.pendant();f.maid().setPersistenceRequired();}
            else {
                var p=player(h,UUID.randomUUID(),false,new Vec3(131,240,131));
                TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());ConsumptionTests.seed(p,0,book,2);
                source=p;handle=AccessGate.resolve(p).handle();pendant=TestPlayers.necklace(p).getStackInSlot(0);
            }
            source.setPos(131,240,131);source.setNoGravity(true);
            var target=player(h,UUID.randomUUID(),false,new Vec3(132,67,132));
            for(int x=128;x<=134;x++)for(int z=128;z<=134;z++)level.setBlockAndUpdate(new BlockPos(x,64,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));
            level.setBlockAndUpdate(PORT,block.defaultBlockState());block.setPlacedBy(level,PORT,level.getBlockState(PORT),target,new ItemStack(block));
            var port=level.getBlockEntity(PORT);
            ((com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity)port).addressFilter="fmp-return-warehouse";port.setChanged();
            var network=(UUID)port.getClass().getMethod("getLogisticsNetworkId").invoke(port);
            var join=Class.forName("de.theidler.create_mobile_packages.network_settings.AddPlayerToNetworkPackage");
            join.getMethod("handle",ServerPlayer.class).invoke(join.getConstructor(UUID.class,UUID.class).newInstance(target.getUUID(),network),target);
            pendant.set(FmpRegistries.NETWORK.get(),network);
            if(maid)MaidCompatibility.inventory(source).setStackInSlot(0,new ItemStack(bee));else ((ServerPlayer)source).getInventory().setItem(0,new ItemStack(bee));
            var record=ledger.find(handle.cacheId());var edit=record.state().edit();edit.settings(0,new CellSettings(-1,1,1,-1));
            ledger.replace(handle,record.state().revision(),record.withState(edit.finish()));ledger.setReturnAddress(handle.cacheId(),"fmp-return-warehouse");
            var endpoint=maid?MaidAccess.endpoint(source):LogisticsEndpoint.player((ServerPlayer)source);
            final LivingEntity actualSource=source;
            h.startSequence().thenIdle(6).thenExecute(()->ReturnService.check(endpoint)).thenIdle(2).thenExecute(()->{
                try {
                    h.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount()==1&&carried(actualSource,bee)==0,"Return did not debit one book and one bee");
                    var entries=robos(level).entrySet().stream().filter(e->{try{return !cargo(e.getValue()).isEmpty();}catch(ReflectiveOperationException e1){throw new IllegalStateException(e1);}}).toList();
                    h.assertTrue(entries.size()==1,"Expected exactly one native outbound cargo");var entry=entries.getFirst();var box=cargo(entry.getValue());
                    h.assertTrue(!box.has(FmpRegistries.PARCEL_SEAL.get())&&!box.has(FmpRegistries.FLUID_PARCEL_SEAL.get()),"Return acquired an inbound authorization seal");
                    h.assertTrue(ItemStack.matches(PackageItem.getContents(box).getStackInSlot(0),book),"Return did not retain exact enchanted item");
                    probe.data.putUUID("source",actualSource.getUUID());probe.data.putUUID("target",target.getUUID());probe.data.putUUID("cache",handle.cacheId());
                    probe.data.putUUID("robo",(UUID)entry.getKey());probe.data.put("box",box.save(level.registryAccess()));probe.data.put("book",book.save(level.registryAccess()));
                    mark(probe,phase);FeedMePackages.LOGGER.info("FMP_RETURN_JVM_WRITE role={} robo={} unsealed=true enchanted=true",mode[0],entry.getKey());
                }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }).thenSucceed();return;
        }
        h.assertTrue(!probe.data.isEmpty()&&!PROCESS.equals(probe.data.getUUID("process"))
                &&probe.data.getString("phase").equals(phase.equals("read")?"write":"read"),"Prior separate JVM phase missing");
        var target=player(h,probe.data.getUUID("target"),true,null);
        ServerPlayer sourcePlayer=maid?null:player(h,probe.data.getUUID("source"),true,null);
        ItemStack expectedBox=ItemStack.parse(level.registryAccess(),probe.data.getCompound("box")).orElseThrow();
        ItemStack book=ItemStack.parse(level.registryAccess(),probe.data.getCompound("book")).orElseThrow();
        boolean[] inspected={phase.equals("verify")};long[] stable={-1};
        h.onEachTick(()->{
            try {
                var entity=maid?level.getEntity(probe.data.getUUID("source")):sourcePlayer;if(!(entity instanceof LivingEntity source))return;
                h.assertTrue(ledger.find(probe.data.getUUID("cache")).state().cells().get(0).amount()==1,"Restart changed retained stock");
                Object robo=robos(level).get(probe.data.getUUID("robo"));
                if(!inspected[0]) {
                    h.assertTrue(robo!=null&&ItemStack.matches(cargo(robo),expectedBox),"Native restart lost or altered original unsealed cargo");
                    h.assertTrue(!maid||source.getUUID().equals(MaidReturn.maid(probe.data.getUUID("robo"))),"Native restart lost source maid");
                    inspected[0]=true;
                }
                if(delivered(level,book)!=1)return;
                h.assertTrue(target.getInventory().isEmpty(),"Warehouse return was redirected into the placer inventory");
                var port=level.getBlockEntity(PORT);var carriers=(IItemHandler)port.getClass().getMethod("getRoboBeeInventory").invoke(port);
                int atPort=0;for(int i=0;i<carriers.getSlots();i++)if(carriers.getStackInSlot(i).is(bee))atPort+=carriers.getStackInSlot(i).getCount();
                if(maid?carried(source,bee)!=1:carried(source,bee)+atPort!=1)return;
                h.assertTrue(robo==null,"Completed original bee remained active");
                if(stable[0]<0)stable[0]=h.getTick();if(h.getTick()-stable[0]<40)return;
                h.assertTrue(delivered(level,book)==1&&ledger.returnEscrow(probe.data.getUUID("cache"))==null,"Duplicate cargo or unresolved escrow after completion");
                mark(probe,phase);FeedMePackages.LOGGER.info("FMP_RETURN_JVM_PASSED role={} phase={} originalRobo={} delivered=1 retained=1 carrier=1",mode[0],phase,probe.data.getUUID("robo"));h.succeed();
            }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        });
    }
    private static void mark(Probe probe,String phase){probe.data.putString("phase",phase);probe.data.putUUID("process",PROCESS);probe.setDirty();}
    private static final class Probe extends SavedData {
        CompoundTag data=new CompoundTag();
        static Probe load(CompoundTag tag,HolderLookup.Provider registries){var p=new Probe();p.data=tag.copy();return p;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){return data.copy();}
    }
}
