package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packagerLink.*;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Real factory output, native flights and two receipt/consumption cycles. No direct receipt shortcut. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class NativeDeliveryTests {
    static final String BEE = "de.theidler.create_mobile_packages.";
    static final String PAPER = "com.kreidev.cmpackagecouriers.";
    static final String PHANTOM = "com.yision.phantom.";
    static final String BIO = "com.nobodiiiii.createbiotech.content.allay.";
    enum Transport { MOBILE, PAPER, PHANTOM, BIOTECH }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (var transport : Transport.values()) {
            String id = switch (transport) {
                case MOBILE -> "create_mobile_packages";
                case PAPER -> "cmpackagecouriers";
                case PHANTOM -> "createphantom";
                case BIOTECH -> "create_biotech";
            };
            if (!net.neoforged.fml.ModList.get().isLoaded(id)) continue;
            tests.add(test(transport + "_items_two_cycles", h -> cycle(h, transport)));
            if (net.neoforged.fml.ModList.get().isLoaded("fluidlogistics"))
                tests.add(test(transport + "_fluids_two_cycles", h -> fluidCycle(h, transport)));
        }
        log("selected count={}", tests.size());
        return tests;
    }
    static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("native_delivery", "native_delivery_" + name.toLowerCase(Locale.ROOT), FeedMePackages.MOD_ID + ":empty", 2400, 0, true, body);
    }
    static void log(String format, Object... args) { FeedMePackages.LOGGER.info("FMP_NATIVE_DELIVERY " + format, args); }
    static Class<?> type(String name) { try { return Class.forName(name); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    static Object call(Object object, String name, Object... args) {
        try {
            Class<?> owner = object instanceof Class<?> c ? c : object.getClass();
            var found = Arrays.stream(owner.getMethods()).filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
                    .filter(m -> { for (int i=0;i<args.length;i++) if(!fits(m.getParameterTypes()[i],args[i]))return false; return true; }).toList();
            if (found.size() != 1) throw new IllegalStateException("Ambiguous " + owner + "." + name + "=" + found);
            return found.getFirst().invoke(object instanceof Class<?> ? null : object, args);
        } catch (InvocationTargetException e) { throw new IllegalStateException(name, e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
    static Object field(Object object, String name) {
        try {
            var owner = object instanceof Class<?> c ? c : object.getClass();
            for(var current=owner;current!=null;current=current.getSuperclass()) {
                try { var field=current.getDeclaredField(name);field.setAccessible(true);return field.get(object instanceof Class<?> ? null : object); }
                catch(NoSuchFieldException inherited) { }
            }
            throw new NoSuchFieldException(owner+"."+name);
        }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
    static boolean fits(Class<?> type,Object value) {
        if(value==null)return !type.isPrimitive();
        if(!type.isPrimitive())return type.isInstance(value);
        return type==int.class&&value instanceof Integer || type==long.class&&value instanceof Long
                || type==float.class&&value instanceof Float || type==double.class&&value instanceof Double
                || type==boolean.class&&value instanceof Boolean;
    }
    static Object construct(String name, Class<?>[] types, Object... args) {
        try { return type(name).getConstructor(types).newInstance(args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e instanceof InvocationTargetException x ? x.getCause() : e); }
    }
    static ItemStack item(String id) { var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); if (stack.isEmpty()) throw new IllegalStateException("Missing item " + id); return stack; }
    static int cargo(ItemStack box) { if (!PackageItem.isPackage(box)) return 0; int count = 0; var contents = PackageItem.getContents(box); for (int i=0;i<contents.getSlots();i++) count+=contents.getStackInSlot(i).getCount(); return count; }
    static int stock(ReceiveTests.Fixture f) { return f.record().state().cells().getFirst().amount(); }

    record Online(ReceiveTests.Fixture f, EmbeddedChannel channel, Map<UUID, ServerPlayer> map) {
        void close() {
            map.remove(f.player().getUUID()); TestPlayers.nativeSavePlayers(f.player().getServer()).remove(f.player());
            f.player().discard(); channel.finishAndReleaseAll();
        }
    }
    @SuppressWarnings("unchecked") static Online online(GameTestHelper h) {
        var old = ReceiveTests.setup(h, 0); var p = TestPlayers.real(h, old.player().getUUID());
        TestPlayers.necklace(p).setStackInSlot(0, TestPlayers.necklace(old.player()).getStackInSlot(0)); TestPlayers.necklace(old.player()).setStackInSlot(0, ItemStack.EMPTY);
        for (int i=0;i<36;i++) p.getInventory().setItem(i, i<5 ? ItemStack.EMPTY : new ItemStack(Items.DIRT,64));
        p.setPos(h.absoluteVec(new Vec3(3.5,1,3.5))); p.setNoGravity(true); p.setInvulnerable(true); TestPlayers.nativePackets(p);
        var channel = new EmbeddedChannel(p.connection.getConnection());
        TestPlayers.nativeSavePlayers(p.getServer()).add(p);
        var map = (Map<UUID,ServerPlayer>)field(p.getServer().getPlayerList(), "playersByUUID"); map.put(p.getUUID(),p);
        h.getLevel().addNewPlayer(p);
        return new Online(new ReceiveTests.Fixture(p,old.ledger(),old.handle(),old.key(),old.order()),channel,map);
    }
    static void cycle(GameTestHelper h, Transport transport) {
        var online=online(h); var f=online.f(); var network=UUID.randomUUID(); var before=f.record(); var edit=before.state().edit();
        edit.reset(0); edit.settings(0,new CellSettings(32,-1,1,-1)); f.ledger().replace(f.handle(),before.state().revision(),before.withState(edit.finish()));
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(),network);
        BlockPos packing=new BlockPos(3,1,1), chestPos=packing.south(), linkPos=packing.above();
        h.setBlock(chestPos,Blocks.CHEST); h.setBlock(packing,AllBlocks.PACKAGER.get().defaultBlockState().setValue(PackagerBlock.FACING,Direction.NORTH));
        h.setBlock(linkPos,AllBlocks.STOCK_LINK.get().defaultBlockState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR));
        var chest=(ChestBlockEntity)h.getBlockEntity(chestPos); chest.setItem(0,new ItemStack(Items.STONE,64)); chest.setItem(1,new ItemStack(Items.STONE,64)); chest.setChanged();
        var delivery=new Delivery(h,f,transport); var ids=new HashSet<UUID>(); int[] round={0}; boolean[] configured={false}, launched={false}, finished={false};
        h.onEachTick(()->{
            if(finished[0] || h.getTick()<3)return;
            try {
                if(!configured[0]) { var link=(PackagerLinkBlockEntity)h.getBlockEntity(linkPos); LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId=network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour); configured[0]=true; }
                // Native flight owns delivery; the production scanner handles any parcel left in inventory.
                f.player().tickCount=0; SupplyService.tick(f.player()); delivery.refresh();
                var handler=h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,h.absolutePos(packing),Direction.NORTH);
                if(!launched[0] && handler!=null && PackageItem.isPackage(handler.extractItem(0,1,true))) {
                    var box=handler.extractItem(0,1,false); var seal=box.get(FmpRegistries.PARCEL_SEAL.get());
                    h.assertTrue(cargo(box)==32 && seal!=null && ids.add(seal.requestId()) && PackageItem.getAddress(box).equals(SupplyService.address(f.player())),"Native source did not create distinct 32-item request");
                    h.assertTrue(f.record().state().pending(0)==32,"Native dispatch not counted"); delivery.send(box); launched[0]=true;
                    log("dispatch transport={} round={} pending=32 request={}",transport,round[0]+1,seal.requestId());
                }
                if(!launched[0] || stock(f)!=32)return;
                h.assertTrue(f.record().state().pending(0)==0,"Arrival failed to settle in-transit");
                if(round[0]==0){f.extract(32); round[0]=1; launched[0]=false; log("consumed transport={} stock=0 pending=0 requestable={}",transport,f.record().state().requestable(0));return;}
                int remaining=chest.getItem(0).getCount()+chest.getItem(1).getCount();
                h.assertTrue(remaining==64 && ids.size()==2 && f.record().state().orders().isEmpty(),"Second cycle conservation failed: "+remaining);
                for(int n=0;n<5;n++)ReceiveService.resume(f.player());
                h.assertTrue(stock(f)==32 && f.record().state().pending(0)==0,"Repeat scanner changed settled stock");
                log("CYCLE_OK transport={} source_initial=128 source_final=64 consumed=32 cache=32 pending=0 requests=2",transport);
                finished[0]=true;online.close();h.succeed();
            }catch(RuntimeException | AssertionError error){finished[0]=true;online.close();throw error;}
        });
    }
    static void fluidCycle(GameTestHelper h,Transport transport) {
        var online=online(h);var f=online.f();var network=UUID.randomUUID();
        var key=dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1),f.player().registryAccess());
        var before=f.record();var items=before.state().edit();items.reset(0);items.filter(0,null);
        var fluid=before.fluids().state().edit();fluid.filter(0,key);fluid.settings(0,new CellSettings(1000,-1,1,-1));
        f.ledger().replace(f.handle(),before.state().revision(),before.withState(items.finish()).withFluids(before.fluids().withState(fluid.finish())));
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(),network);
        var source=new SupplyTests.FluidSupply(f.player(),f.ledger(),f.handle(),key,network);var pos=new BlockPos(3,1,1);SupplyTests.placeFluidFactory(h,pos,1);
        var delivery=new Delivery(h,f,transport);var ids=new HashSet<UUID>();boolean[] configured={false},launched={false},done={false};int[] round={0};
        h.onEachTick(()->{
            if(done[0] || h.getTick()<10)return;
            try {
                if(!configured[0]){SupplyTests.fillSource(h,source,pos,4000);SupplyTests.connect(h,pos,network);configured[0]=true;}
                f.player().tickCount=0;SupplyService.tick(f.player());delivery.refresh();
                if(!launched[0]) {
                    var box=SupplyTests.takeOutput(h,pos);
                    if(!box.isEmpty()) {
                        var seal=box.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
                        h.assertTrue(seal!=null && ids.add(seal.requestId()) && dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.read(box).getAmount()==1000,"Fluid factory did not output distinct one-bucket request");
                        h.assertTrue(f.record().fluids().state().pending(0)==1000,"Fluid request pending missing");delivery.send(box);launched[0]=true;
                        log("fluid_dispatch transport={} round={} pending=1000 request={}",transport,round[0]+1,seal.requestId());
                    }
                }
                if(!launched[0] || f.record().fluids().state().cells().getFirst().amount()!=1000)return;
                h.assertTrue(f.record().fluids().state().pending(0)==0,"Fluid native delivery did not settle pending");
                if(round[0]==0) {
                    var current=f.record();var change=current.fluids().state().edit();change.extract(0,1000);
                    f.ledger().replace(f.handle(),current.state().revision(),current.withFluids(current.fluids().withState(change.finish())));round[0]=1;launched[0]=false;return;
                }
                int remaining=SupplyTests.source(h,pos).getFluidInTank(0).getAmount();
                h.assertTrue(remaining==2000 && ids.size()==2 && f.record().fluids().state().orders().isEmpty(),"Fluid second cycle violated conservation");
                log("FLUID_CYCLE_OK transport={} source_initial=4000 source_final=2000 consumed=1000 cache=1000 pending=0 requests=2",transport);
                done[0]=true;online.close();h.succeed();
            }catch(RuntimeException | AssertionError error){done[0]=true;online.close();throw error;}
        });
    }
    static final class Delivery {
        final GameTestHelper h; final ReceiveTests.Fixture f; final Transport transport;
        Object port; ItemStack transmitter; 
        @SuppressWarnings("unchecked") Delivery(GameTestHelper h,ReceiveTests.Fixture f,Transport transport) {
            this.h=h;this.f=f;this.transport=transport;
            if(transport==Transport.MOBILE) {
                var pos=new BlockPos(1,1,4); var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_mobile_packages:bee_port"));h.setBlock(pos,block);
                block.setPlacedBy(h.getLevel(),h.absolutePos(pos),h.getBlockState(pos),f.player(),new ItemStack(block));port=h.getBlockEntity(pos);
                call(port,"addBeeToRoboBeeInventory",1);call(port,"setBeeReturnModeEnabled",true);var network=(UUID)call(port,"getLogisticsNetworkId");
                call(construct(BEE+"network_settings.AddPlayerToNetworkPackage",new Class<?>[]{UUID.class,UUID.class},f.player().getUUID(),network),"handle",f.player());
            } else if(transport==Transport.PAPER) {
                transmitter=item("cmpackagecouriers:location_transmitter");call(transmitter.getItem().getClass(),"setEnabled",transmitter,true);refresh();
            } else if(transport==Transport.BIOTECH) {
                var pos=new BlockPos(1,1,4);h.setBlock(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_biotech:allay_port")));port=h.getBlockEntity(pos);
                var nativePort=(PackagePortBlockEntity)port;nativePort.addressFilter="native-"+UUID.randomUUID();nativePort.acceptsPackages=true;
                call(type(BIO+"block.allayport.AllayPortTargetRegistry"),"update",h.getLevel(),h.absolutePos(pos),nativePort.addressFilter);
                ((ItemStackHandler)call(port,"getCarrierInventory")).setStackInSlot(0,item("create_biotech:allay_courier"));
            }
        }
        void refresh(){if(transmitter!=null)transmitter.getItem().inventoryTick(transmitter,h.getLevel(),f.player(),0,false);}
        void send(ItemStack box) {
            switch(transport) {
                case MOBILE -> {h.assertTrue((boolean)call(port,"addItemStack",box.copy(),false),"Bee port refused native parcel");box.setCount(0);}
                case PAPER -> {h.assertTrue((boolean)call(type(PAPER+"plane.CardboardPlaneManager"),"addPlane",h.getLevel(),f.player().position().add(0,0,4),180F,0F,box.copy(),false),"Paper native launch refused");box.setCount(0);}
                case BIOTECH -> {((PackagePortBlockEntity)port).inventory.setStackInSlot(0,box.copy());box.setCount(0);call(port,"tryDispatch");}
                case PHANTOM -> {
                    var sender=TestPlayers.create(h,ItemStack.EMPTY);sender.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FIREWORK_ROCKET));
                    var courier=(Entity)call(type(PHANTOM+"entity.courier.AirCourierEntity"),"createWaiting",h.getLevel(),box.copy(),new Vec3(0,0,1));
                    courier.setPos(f.player().position().add(0,1,-6));h.assertTrue(h.getLevel().addFreshEntity(courier),"Phantom entity creation failed");box.setCount(0);
                    call(courier,"interact",sender,InteractionHand.MAIN_HAND);h.assertTrue(courier.isRemoved() && sender.getMainHandItem().isEmpty(),"Native phantom firework launch failed");
                }
            }
        }
    }
}
