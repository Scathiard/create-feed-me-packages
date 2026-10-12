package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

/** Calls the fixed author's inventory entry and actual place packet handler. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class EffortlessTests {
    static final String ROOT="neoforge.nl.requios.effortlessbuilding.";
    @FunctionalInterface private interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if(!ModList.get().isLoaded("effortlessbuilding"))return List.of();
        if(mismatchFixture())return List.of(test("real_release_mismatch",EffortlessTests::releaseMismatch));
        return List.of(test("native_materials",EffortlessTests::materials),test("native_packet_build",EffortlessTests::packetBuild),
                test("material_loss_during_placement",EffortlessTests::materialLoss),test("partial_payment_history",EffortlessTests::partialPayment),
                test("exact_reserved",EffortlessTests::exactReserved),test("unequip_during_batch",EffortlessTests::unequip),
                test("replacement_returns",EffortlessTests::replacement),test("randomizer_materials",EffortlessTests::randomizer),
                test("disabled_creative_components",EffortlessTests::nativeBoundaries),test("exception_restore",EffortlessTests::exceptionRestore),
                test("shape_guard",EffortlessTests::shapeGuard),test("failed_write_receipt_refunds",EffortlessTests::receiptRefunds),
                test("full_inventory_undo",EffortlessTests::fullInventoryUndo));
    }
    static boolean mismatchFixture(){return System.getProperty("fmp.optionalMismatch","").equals("effortless40");}
    private static void releaseMismatch(GameTestHelper h) throws ReflectiveOperationException {
        var version=ModList.get().getModContainerById("effortlessbuilding").orElseThrow().getModInfo().getVersion().toString();
        h.assertTrue(version.equals("4.0"),"Mismatch fixture must run the actual pinned 4.0 author release: "+version);
        h.assertTrue(dev.scathiard.feedmepackages.network.CompatibilityStatus.effortless()==dev.scathiard.feedmepackages.network.CompatibilityStatus.State.API_MISMATCH
                && !dev.scathiard.feedmepackages.mixin.OptionalMixins.effortlessCompatible(),"Actual older API was not detected and disabled");
        var p=player(h);p.getInventory().setItem(0,new ItemStack(Items.STONE,2));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        h.assertTrue(count(p,Items.STONE)==2 && consume(p,Items.STONE,3)==2 && ConsumptionTests.stock(p,0)==5,"Disabled optional port consumed cache or broke native inventory fallback");
        var view=dev.scathiard.feedmepackages.interaction.CacheActions.open(p);
        h.assertTrue(view!=null && view.record().state().cells().get(0).amount()==5,"Optional mismatch blocked normal cache access");
        h.succeed();
    }
    private static TestFunction test(String name,Body body) {
        return new TestFunction("effortless","fmp_effortless_"+name,FeedMePackages.MOD_ID+":empty",200,0,true,
                h->{try{body.run(h);}catch(ReflectiveOperationException e){throw new IllegalStateException("Native Effortless fixture failed",e);}});
    }
    private static ServerPlayer player(GameTestHelper h) {
        var p=TestPlayers.real(h,UUID.randomUUID());TestPlayers.nativePackets(p);p.setPos(h.absoluteVec(new Vec3(1,2,1)));
        TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());return p;
    }
    private static int count(ServerPlayer p,Item item) throws ReflectiveOperationException {
        return (int)Class.forName(ROOT+"utilities.InventoryHelper").getMethod("findTotalItemsInInventory",Player.class,Item.class).invoke(null,p,item);
    }
    private static int consume(ServerPlayer p,Item item,int amount) throws ReflectiveOperationException {
        return (int)Class.forName(ROOT+"utilities.InventoryHelper").getMethod("consumeItems",Player.class,Item.class,int.class).invoke(null,p,item,amount);
    }
    private static void materials(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);p.getInventory().setItem(0,new ItemStack(Items.STONE,2));
        h.assertTrue(count(p,Items.STONE)==2 && consume(p,Items.STONE,1)==1 && p.getMainHandItem().getCount()==1,"Native ordinary control failed");
        ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        h.assertTrue(count(p,Items.STONE)==6 && ConsumptionTests.stock(p,0)==5,"Native material query misses cache or spends it");
        h.assertTrue(consume(p,Items.STONE,4)==4 && p.getMainHandItem().isEmpty() && ConsumptionTests.stock(p,0)==2,"Native consume did not use ordinary one then cache three");h.succeed();
    }
    static Object packet(BlockPos first,BlockPos second) throws ReflectiveOperationException {
        var mode=Class.forName(ROOT+"buildmode.BuildModeEnum");var action=Class.forName(ROOT+"buildmode.ModeOptions$ActionEnum");var replace=Class.forName(ROOT+"buildmode.BuildSettings$ReplaceMode");
        return Class.forName(ROOT+"network.PlaceBuildModePacket").getConstructor(mode,BlockPos.class,BlockPos.class,BlockPos.class,Direction.class,Vec3.class,action,action,action,action,replace,boolean.class)
                .newInstance(mode.getField("LINE").get(null),first,second,second,Direction.UP,Vec3.atCenterOf(first),action.getField("FULL").get(null),action.getField("CUBE_FULL").get(null),
                        action.getField("SHORT_EDGE").get(null),action.getField("CIRCLE_START_CORNER").get(null),replace.getField("BLOCKS_AND_AIR").get(null),true);
    }
    static void place(ServerPlayer p,Object packet) throws ReflectiveOperationException {
        Class.forName(ROOT+"network.PacketHandler").getMethod("handlePlaceBuildMode",packet.getClass(),ServerPlayer.class).invoke(null,packet,p);
    }
    private static void packetBuild(GameTestHelper h) throws ReflectiveOperationException {
        for(boolean cached:new boolean[]{false,true}) {
            var p=player(h);var first=h.absolutePos(new BlockPos(1,2,cached?3:2));var end=first.east(2);
            p.getInventory().setItem(0,new ItemStack(Items.STONE,cached?1:3));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),cached?5:0);
            place(p,packet(first,end));int blocks=0;for(int i=0;i<3;i++)if(h.getLevel().getBlockState(first.east(i)).is(Blocks.STONE))blocks++;
            h.assertTrue(blocks==3 && p.getMainHandItem().isEmpty() && ConsumptionTests.stock(p,0)==(cached?3:0),"Native packet failed ordinary/cache boundary: cached="+cached+" placed="+blocks+" stock="+ConsumptionTests.stock(p,0));
        }
        h.succeed();
    }
    private static void materialLoss(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE,3));
        GadgetsTests.onRemoval(h,first,()->{p.getEnderChestInventory().setItem(0,p.getInventory().getItem(0));p.getInventory().setItem(0,ItemStack.EMPTY);});
        try {
            place(p,packet(first,first.east(2)));int blocks=0;for(int i=0;i<3;i++)if(h.getLevel().getBlockState(first.east(i)).is(Blocks.STONE))blocks++;
            h.assertTrue(blocks+p.getEnderChestInventory().getItem(0).getCount()==3,"Native operation created unpaid blocks after callback: blocks="+blocks+" ender="+p.getEnderChestInventory().getItem(0).getCount());
        } finally {GadgetsTests.clearRemoval(first);}
        h.succeed();
    }
    private static int blocks(GameTestHelper h,BlockPos first,net.minecraft.world.level.block.Block block) {
        int count=0;for(int i=0;i<3;i++)if(h.getLevel().getBlockState(first.east(i)).is(block))count++;return count;
    }
    private static int inventory(ServerPlayer p,Item item) {
        int result=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))result+=p.getInventory().getItem(i).getCount();return result;
    }
    private static int history(ServerPlayer p,String action) throws ReflectiveOperationException {
        return (int)Class.forName(ROOT+"utilities.UndoManager").getMethod(action,ServerPlayer.class).invoke(null,p);
    }
    private static void partialPayment(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE,3));
        GadgetsTests.onRemoval(h,first,()->p.getEnderChestInventory().setItem(0,p.getMainHandItem().split(2)));
        try {
            place(p,packet(first,first.east(2)));
            h.assertTrue(blocks(h,first,Blocks.STONE)==1 && p.getEnderChestInventory().getItem(0).getCount()==2 && inventory(p,Items.STONE)==0,"Partial payment did not keep exactly one paid placement");
            h.assertTrue(history(p,"undo")==1 && blocks(h,first,Blocks.STONE)==0 && inventory(p,Items.STONE)==1,"Undo included unpaid placements or lost paid return");
            h.assertTrue(history(p,"redo")==1 && blocks(h,first,Blocks.STONE)==1 && inventory(p,Items.STONE)==0,"Redo reconstructed unpaid history");
        } finally {GadgetsTests.clearRemoval(first);}h.succeed();
    }
    private static void exactReserved(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var named=new ItemStack(Items.STONE);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep components"));
        ConsumptionTests.seed(p,0,named,7);ConsumptionTests.seed(p,1,new ItemStack(Items.STONE),5);
        var view=dev.scathiard.feedmepackages.interaction.CacheActions.open(p);
        h.assertTrue(dev.scathiard.feedmepackages.interaction.CacheActions.execute(p,new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(),view.record().state().revision(),dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_CURSOR,1,3,-1,""))==dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK,"Could not establish reservation");
        h.assertTrue(count(p,Items.STONE)==2 && consume(p,Items.STONE,4)==2 && ConsumptionTests.stock(p,0)==7 && ConsumptionTests.stock(p,1)==3,"Item-only request spent non-default components or reserved material");
        p.closeContainer();h.succeed();
    }
    private static void unequip(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        var pendant=TestPlayers.necklace(p).getStackInSlot(0);
        GadgetsTests.onRemoval(h,first,()->TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY));
        try {
            place(p,packet(first,first.east(2)));h.assertTrue(blocks(h,first,Blocks.STONE)==1 && count(p,Items.STONE)==0,"Old preview retained cache access after unequip");
            TestPlayers.necklace(p).setStackInSlot(0,pendant);h.assertTrue(ConsumptionTests.stock(p,0)==5,"Unequipped cache was charged");
        }finally {TestPlayers.necklace(p).setStackInSlot(0,pendant);GadgetsTests.clearRemoval(first);}h.succeed();
    }
    private static void replacement(GameTestHelper h) throws ReflectiveOperationException {
        for(boolean fail:new boolean[]{false,true}) {
            var p=player(h);var first=h.absolutePos(new BlockPos(1,2,fail?3:2));p.getInventory().setItem(0,new ItemStack(Items.STONE,3));
            for(int i=0;i<3;i++)h.getLevel().setBlockAndUpdate(first.east(i),Blocks.DIRT.defaultBlockState());
            // The author permits survival replacement only for this player's tracked blocks by default.
            Class.forName(ROOT+"utilities.PlacedBlockTracker").getMethod("trackAll",UUID.class,net.minecraft.resources.ResourceKey.class,Collection.class)
                    .invoke(null,p.getUUID(),h.getLevel().dimension(),List.of(first,first.east(),first.east(2)));
            if(fail)GadgetsTests.onRemoval(h,first,()->{p.getEnderChestInventory().setItem(0,p.getMainHandItem());p.getInventory().setItem(0,ItemStack.EMPTY);});
            try {
                place(p,packet(first,first.east(2)));
                h.assertTrue(blocks(h,first,Blocks.STONE)==(fail?0:3) && inventory(p,Items.DIRT)==(fail?0:3),"Old block return escaped unpaid replacement: fail="+fail+" placed="+blocks(h,first,Blocks.STONE)+" dirt="+inventory(p,Items.DIRT)+" held="+inventory(p,Items.STONE));
                if(fail)h.assertTrue(blocks(h,first,Blocks.DIRT)==2 && history(p,"undo")==-1,"Failed replacement changed old world or recorded history");
            }finally {GadgetsTests.clearRemoval(first);}
        }h.succeed();
    }
    private static void randomizer(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));
        var tool=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().filter(i->i.getClass().getName().equals(ROOT+"item.RandomizerToolItem")).findFirst().orElseThrow();
        var stack=new ItemStack(tool);Class.forName(ROOT+"item.RandomizerToolData").getMethod("setConfiguration",ItemStack.class,List.class,List.class).invoke(null,stack,List.of(Items.STONE,Items.DIRT),List.of(1,1));
        p.getInventory().setItem(0,stack);ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),10);ConsumptionTests.seed(p,1,new ItemStack(Items.DIRT),10);
        place(p,packet(first,first.east(2)));int stone=blocks(h,first,Blocks.STONE),dirt=blocks(h,first,Blocks.DIRT);
        h.assertTrue(stone+dirt==3 && ConsumptionTests.stock(p,0)==10-stone && ConsumptionTests.stock(p,1)==10-dirt && p.getMainHandItem()==stack,"Native randomizer chose/spent mismatched materials");
        h.assertTrue(history(p,"undo")==3 && inventory(p,Items.STONE)==stone && inventory(p,Items.DIRT)==dirt,"Randomizer undo did not return original materials");
        h.assertTrue(history(p,"redo")==3 && inventory(p,Items.STONE)==0 && inventory(p,Items.DIRT)==0 && ConsumptionTests.stock(p,0)==10-stone && ConsumptionTests.stock(p,1)==10-dirt,"Redo double charged cache");h.succeed();
    }
    private static void nativeBoundaries(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        boolean prior=dev.scathiard.feedmepackages.FmpConfig.EFFORTLESS.get();
        try {
            dev.scathiard.feedmepackages.FmpConfig.EFFORTLESS.set(false);dev.scathiard.feedmepackages.FmpConfig.refreshServer();
            h.assertTrue(count(p,Items.STONE)==1,"Disabled adapter exposed cache");place(p,packet(first,first.east(2)));
            h.assertTrue(blocks(h,first,Blocks.STONE)==1 && ConsumptionTests.stock(p,0)==5,"Disabled native fallback changed");
        }finally {dev.scathiard.feedmepackages.FmpConfig.EFFORTLESS.set(prior);dev.scathiard.feedmepackages.FmpConfig.refreshServer();}
        for(int i=0;i<3;i++)h.getLevel().setBlockAndUpdate(first.east(i),Blocks.AIR.defaultBlockState());
        var named=new ItemStack(Items.STONE);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Native hand only"));p.getInventory().setItem(0,named);
        place(p,packet(first,first.east(2)));h.assertTrue(blocks(h,first,Blocks.STONE)==1 && ConsumptionTests.stock(p,0)==5,"Component hand path borrowed plain cache");
        for(int i=0;i<3;i++)h.getLevel().setBlockAndUpdate(first.east(i),Blocks.AIR.defaultBlockState());
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getInventory().setItem(0,new ItemStack(Items.STONE));place(p,packet(first,first.east(2)));
        h.assertTrue(blocks(h,first,Blocks.STONE)==3 && p.getMainHandItem().getCount()==1 && ConsumptionTests.stock(p,0)==5,"Creative native path spent cache");h.succeed();
    }
    private static void exceptionRestore(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE,3));
        GadgetsTests.onRemoval(h,first,()->{throw new IllegalStateException("Effortless fixture callback");});var before=h.getLevel().getBlockState(first);boolean thrown=false;
        try {place(p,packet(first,first.east(2)));}catch(java.lang.reflect.InvocationTargetException expected){thrown=true;}finally {GadgetsTests.clearRemoval(first);}
        int undo=history(p,"undo");
        h.assertTrue(thrown && h.getLevel().getBlockState(first).equals(before) && inventory(p,Items.STONE)==3 && undo==-1,"Interrupted native placement was not restored without payment/history: thrown="+thrown+" actual="+h.getLevel().getBlockState(first)+" before="+before+" stock="+inventory(p,Items.STONE)+" undo="+undo);
        place(p,packet(first,first.east(2)));h.assertTrue(blocks(h,first,Blocks.STONE)==3 && inventory(p,Items.STONE)==0,"Failed batch leaked into the next native invocation");h.succeed();
    }
    private static org.objectweb.asm.tree.ClassNode bytecode(String name) {
        try(var stream=EffortlessTests.class.getClassLoader().getResourceAsStream((ROOT+name).replace('.','/')+".class")) {
            var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(Objects.requireNonNull(stream)).accept(node,0);return node;
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    /** Settlement fixture: real native debits, but a controlled failed world-write record, not a native packet. */
    private static void receiptRefunds(GameTestHelper h) throws ReflectiveOperationException {
        for(int variant=0;variant<4;variant++) {
            var p=player(h);var pos=h.absolutePos(new BlockPos(1,2,1+variant));
            var named=new ItemStack(Items.STONE,3);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Exact refund"));
            var expectedVariant=named.copy();
            p.getInventory().setItem(0,variant==2?named:new ItemStack(Items.STONE));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
            var pendant=TestPlayers.necklace(p).getStackInSlot(0);int requested=variant==2?3:4;
            // Open using a recognized plain building item; the native Item-only source may then find named ordinary stacks.
            var hand=p.getInventory().getItem(0);p.getInventory().setItem(0,new ItemStack(Items.STONE));
            try(var operation=dev.scathiard.feedmepackages.compat.effortless.EffortlessOperation.open(p)) {
                p.getInventory().setItem(0,hand);operation.billing(true);
                h.assertTrue(consume(p,Items.STONE,requested)==requested,"Receipt fixture failed to debit actual sources");operation.billing(false);
                var before=net.neoforged.neoforge.common.util.BlockSnapshot.create(h.getLevel().dimension(),h.getLevel(),pos);
                h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());operation.placement(before,Blocks.STONE.defaultBlockState(),null,Items.STONE,false);
                if(variant==1 || variant==3)TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY);
                if(variant==3)for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
                operation.settle(new HashMap<>());operation.settle(new HashMap<>());
                h.assertTrue(h.getLevel().getBlockState(pos).equals(before.getState()),"Failed write was not restored");
            }finally {TestPlayers.necklace(p).setStackInSlot(0,pendant);}
            if(variant==0)h.assertTrue(ConsumptionTests.stock(p,0)==5 && inventory(p,Items.STONE)==1,"Unused cache receipt did not return to original accessible cache exactly once");
            if(variant==1)h.assertTrue(ConsumptionTests.stock(p,0)==2 && inventory(p,Items.STONE)==4,"Unequipped cache was accessed by refund");
            if(variant==2)h.assertTrue(ConsumptionTests.stock(p,0)==5 && inventory(p,Items.STONE)==3 && ItemStack.isSameItemSameComponents(p.getInventory().getItem(0),expectedVariant),"Refund lost ordinary item components");
            if(variant==3) {
                int dropped=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(2)).stream().filter(e->e.getItem().is(Items.STONE)).mapToInt(e->e.getItem().getCount()).sum();
                h.assertTrue(ConsumptionTests.stock(p,0)==2 && inventory(p,Items.STONE)==0 && dropped==4,"Full inventory fallback lost or copied actual receipts: "+dropped);
            }
        }h.succeed();
    }
    private static void fullInventoryUndo(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h);var first=h.absolutePos(new BlockPos(1,2,3));p.getInventory().setItem(0,new ItemStack(Items.STONE));ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        place(p,packet(first,first.east(2)));for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
        h.assertTrue(history(p,"undo")==3 && blocks(h,first,Blocks.STONE)==0 && ConsumptionTests.stock(p,0)==3,"Full inventory undo changed cache or left constructed blocks");
        int dropped=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(2)).stream().filter(e->e.getItem().is(Items.STONE)).mapToInt(e->e.getItem().getCount()).sum();
        h.assertTrue(dropped==3 && inventory(p,Items.STONE)==0,"Native full inventory undo did not drop exactly original materials: "+dropped);h.succeed();
    }
    private static void shapeGuard(GameTestHelper h) {
        var sources=bytecode("utilities.InventoryHelper");var build=bytecode("network.PacketHandler");
        h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.hasEffortlessPorts(sources,build),"Author artifact failed whole adapter shape");
        for(var name:List.of("findTotalItemsInInventory","consumeItems","handlePlaceBuildMode")) {
            var node=name.equals("handlePlaceBuildMode")?build:sources;var method=node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();node.methods.remove(method);
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasEffortlessPorts(sources,build),"Partial adapter accepted missing "+name);node.methods.add(method);
        }
        for(var name:List.of("consumeItems","giveOrDropItems","setBlock","recordOperation","canPlaceAt")) {
            var changed=bytecode("network.PacketHandler");var method=changed.methods.stream().filter(m->m.name.equals("handlePlaceBuildMode")).findFirst().orElseThrow();
            for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals(name)){method.instructions.remove(call);break;}
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasEffortlessPorts(sources,changed),"Partial batch guard accepted missing "+name);
        }
        var changed=bytecode("network.PacketHandler");var method=changed.methods.stream().filter(m->m.name.equals("handlePlaceBuildMode")).findFirst().orElseThrow();
        org.objectweb.asm.tree.AbstractInsnNode place=null,history=null;
        for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){if(call.name.equals("setBlock") && place==null)place=call;if(call.name.equals("recordOperation"))history=call;}
        method.instructions.remove(history);method.instructions.insertBefore(place,history);
        h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasEffortlessPorts(sources,changed),"Premature history accepted despite changed settlement order");h.succeed();
    }
}
