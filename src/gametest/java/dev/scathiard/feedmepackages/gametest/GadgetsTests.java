package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.registries.RegisterEvent;
import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class GadgetsTests {
    private static final String ROOT = "com.direwolf20.buildinggadgets2.";
    private static Block callbackBlock;
    private static final Map<BlockPos, Runnable> CALLBACKS = new HashMap<>();
    static void onRemoval(GameTestHelper h, BlockPos pos, Runnable action) {
        h.getLevel().setBlockAndUpdate(pos, callbackBlock.defaultBlockState()); CALLBACKS.put(pos, action);
    }
    static void clearRemoval(BlockPos pos) { CALLBACKS.remove(pos); }
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            callbackBlock = new Block(BlockBehaviour.Properties.of().replaceable().noCollission()) {
                @Override protected List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
                    return List.of(new ItemStack(Items.DIRT));
                }
                @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
                    super.onRemove(state, level, pos, next, moving);
                    var action = CALLBACKS.remove(pos); if (action != null) action.run();
                }
            };
            helper.register(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "test_build_callback"), callbackBlock);
        });
    }
    @FunctionalInterface private interface Body { void run(GameTestHelper h) throws ReflectiveOperationException; }
    @GameTestGenerator public static Collection<TestFunction> installed() {
        if (!ModList.get().isLoaded("buildinggadgets2")) return List.of();
        return List.of(test("native_query", GadgetsTests::query), test("native_build", GadgetsTests::build),
                test("reentrant_material_loss", GadgetsTests::reentrantLoss), test("exact_reserved_batch", GadgetsTests::exactReservedBatch),
                test("bound_inventory_priority", GadgetsTests::boundInventoryPriority), test("config_disabled", GadgetsTests::configDisabled),
                test("queued_depletion_unequip", GadgetsTests::queuedDepletionUnequip), test("exchange_and_undo", GadgetsTests::exchangeUndo),
                test("exchange_reentrant_loss", GadgetsTests::exchangeReentrantLoss), test("canceled_and_creative", GadgetsTests::canceledAndCreative),
                test("full_inventory_undo", GadgetsTests::fullInventoryUndo), test("shape_guard", GadgetsTests::shapeGuard));
    }
    private static TestFunction test(String name, Body body) {
        return new TestFunction("gadgets", "fmp_gadgets_" + name, FeedMePackages.MOD_ID + ":empty", 200, 0, true,
                h -> { try { body.run(h); } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native gadget fixture failed", e); } });
    }
    private static ServerPlayer player(GameTestHelper h) {
        var p = TestPlayers.real(h, UUID.randomUUID()); TestPlayers.nativePackets(p);
        TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        p.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(1,2,1))); return p;
    }
    private static boolean remove(ServerPlayer p, List<ItemStack> list, boolean simulate) throws ReflectiveOperationException {
        return (boolean)Class.forName(ROOT + "util.BuildingUtils").getMethod("removeStacksFromInventory", Player.class, List.class,
                boolean.class, GlobalPos.class, Direction.class).invoke(null, p, list, simulate, null, null);
    }
    private static void query(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); var list = List.of(new ItemStack(Items.STONE)); p.getInventory().setItem(9, new ItemStack(Items.STONE));
        h.assertTrue(remove(p, list, true) && p.getInventory().getItem(9).getCount() == 1, "Native inventory control failed");
        p.getInventory().setItem(9, ItemStack.EMPTY); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 5);
        h.assertTrue(remove(p, list, true) && ConsumptionTests.stock(p, 0) == 5, "Native gadget query cannot use cache");
        h.assertTrue(remove(p, list, false) && ConsumptionTests.stock(p, 0) == 4, "Native gadget debit failed"); h.succeed();
    }
    private static Object batch(GameTestHelper h, ServerPlayer p, BlockPos pos) throws ReflectiveOperationException {
        return batch(h, p, pos, Blocks.STONE.defaultBlockState(), "BUILD");
    }
    private static Object batch(GameTestHelper h, ServerPlayer p, BlockPos pos, BlockState state, String mode) throws ReflectiveOperationException {
        var statePos = Class.forName(ROOT + "util.datatypes.StatePos");
        var entries = new ArrayList<>(); entries.add(statePos.getConstructor(BlockState.class, BlockPos.class).newInstance(state, pos));
        var gadget = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("buildinggadgets2:gadget_" + (mode.equals("EXCHANGE") ? "exchanging" : "building"))));
        h.assertTrue(!gadget.isEmpty(), "Native gadget item missing");
        p.getInventory().setItem(0, gadget);
        var type = Class.forName(ROOT + "common.events.ServerBuildList$BuildType");
        Object buildType = Arrays.stream(type.getEnumConstants()).filter(x -> x.toString().equals(mode)).findFirst().orElseThrow();
        return Class.forName(ROOT + "common.events.ServerBuildList").getConstructor(Level.class, ArrayList.class, byte.class, UUID.class,
                boolean.class, boolean.class, UUID.class, ItemStack.class, type, boolean.class, BlockPos.class, GlobalPos.class, int.class)
                .newInstance(h.getLevel(), entries, (byte)0, p.getUUID(), true, mode.equals("EXCHANGE"), UUID.randomUUID(), gadget, buildType, true, BlockPos.ZERO, null, -1);
    }
    private static void run(Object batch, ServerPlayer p) throws ReflectiveOperationException {
        String mode = batch.getClass().getField("buildType").get(batch).toString();
        Class.forName(ROOT + "common.events.ServerTickHandler").getMethod(mode.equals("DESTROY") ? "remove" : mode.toLowerCase(Locale.ROOT), batch.getClass(), Player.class).invoke(null, batch, p);
    }
    private static void finishRender(GameTestHelper h, BlockPos pos) throws ReflectiveOperationException {
        for (int i=0;i<64;i++) {
            var be = h.getLevel().getBlockEntity(pos); if (be == null) return;
            if (!be.getClass().getName().equals(ROOT + "common.blockentities.RenderBlockBE")) return;
            be.getClass().getMethod("tickServer").invoke(be);
        }
    }
    private static void build(GameTestHelper h) throws ReflectiveOperationException {
        var p = player(h); ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 3);
        var pos=h.absolutePos(new BlockPos(3,2,3)); run(batch(h,p,pos),p); finishRender(h,pos);
        h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.STONE) && ConsumptionTests.stock(p,0)==2,
                "Native queued build did not turn exactly one cached item into one block"); h.succeed();
    }
    private static void reentrantLoss(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); var pos=h.absolutePos(new BlockPos(3,2,3));
        // The ordinary item remains owned by the player, but is no longer a gadget material source.
        p.getInventory().setItem(9,new ItemStack(Items.STONE)); h.getLevel().setBlockAndUpdate(pos,callbackBlock.defaultBlockState());
        CALLBACKS.put(pos,()-> { p.getEnderChestInventory().setItem(0,p.getInventory().getItem(9)); p.getInventory().setItem(9,ItemStack.EMPTY); });
        try {
            run(batch(h,p,pos),p); finishRender(h,pos);
            h.assertTrue(h.getLevel().getBlockState(pos).is(callbackBlock) && p.getEnderChestInventory().getItem(0).getCount()==1,
                    "Native pipeline material disappeared after preflight but unpaid block was finalized");
        } finally { CALLBACKS.remove(pos); }
        h.succeed();
    }
    private static int count(ServerPlayer p, ItemStack material) throws ReflectiveOperationException {
        return (int)Class.forName(ROOT + "util.BuildingUtils").getMethod("countItemStacks", Player.class, ItemStack.class).invoke(null, p, material);
    }
    private static void exactReservedBatch(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); var named=new ItemStack(Items.STONE);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Keep variant"));
        ConsumptionTests.seed(p,0,named,7); ConsumptionTests.seed(p,1,new ItemStack(Items.STONE),5);
        var view=CacheActions.open(p);
        h.assertTrue(CacheActions.execute(p,new CacheActions.Intent(view.session(),view.record().state().revision(),CacheActions.Action.TAKE_CURSOR,1,3,-1,""))==CacheActions.Result.OK,"Reservation fixture failed");
        h.assertTrue(count(p,new ItemStack(Items.STONE))==2 && count(p,named)==7,"Native count ignored reservation or exact components");
        var repeated=List.of(new ItemStack(Items.STONE,2),new ItemStack(Items.STONE));
        h.assertTrue(!remove(p,repeated,true) && !remove(p,repeated,false) && ConsumptionTests.stock(p,1)==5,"Repeated requirements reused cache or partially consumed failed batch");
        h.assertTrue(remove(p,List.of(new ItemStack(Items.STONE,2)),false) && ConsumptionTests.stock(p,1)==3 && ConsumptionTests.stock(p,0)==7,"Native extraction spent reserved/wrong variant items");
        h.assertTrue(remove(p,List.of(named.copyWithCount(2)),false) && ConsumptionTests.stock(p,0)==5,"Exact component request not fulfilled");
        p.closeContainer(); h.succeed();
    }
    private static void boundInventoryPriority(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5);
        var pos=h.absolutePos(new BlockPos(3,2,3)); h.getLevel().setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
        var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(pos);
        chest.setItem(0,new ItemStack(Items.STONE)); p.getInventory().setItem(9,new ItemStack(Items.STONE));
        var method=Class.forName(ROOT+"util.BuildingUtils").getMethod("removeStacksFromInventory",Player.class,List.class,boolean.class,GlobalPos.class,Direction.class);
        for(int pass=0;pass<3;pass++) {
            h.assertTrue((boolean)method.invoke(null,p,List.of(new ItemStack(Items.STONE)),false,GlobalPos.of(h.getLevel().dimension(),pos),Direction.UP),"Native bound source failed");
            h.assertTrue(chest.isEmpty() && p.getInventory().getItem(9).getCount()==(pass==0?1:0) && ConsumptionTests.stock(p,0)==(pass<2?5:4),"Bound/ordinary/cache source priority changed at "+pass);
        }
        h.succeed();
    }
    private static void configDisabled(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),5); boolean prior=FmpConfig.GADGETS.get();
        try {
            FmpConfig.GADGETS.set(false); FmpConfig.refreshServer();
            h.assertTrue(CompatibilityStatus.gadgets()==CompatibilityStatus.State.CONFIG_DISABLED && count(p,new ItemStack(Items.STONE))==0 && !remove(p,List.of(new ItemStack(Items.STONE)),false),"Disabled adapter exposed cache");
            p.getInventory().setItem(9,new ItemStack(Items.STONE));
            h.assertTrue(remove(p,List.of(new ItemStack(Items.STONE)),false) && ConsumptionTests.stock(p,0)==5 && p.getInventory().getItem(9).isEmpty(),"Native fallback failed");
        } finally { FmpConfig.GADGETS.set(prior); FmpConfig.refreshServer(); }
        h.assertTrue(count(p,new ItemStack(Items.STONE))==5,"Re-enabled adapter remained disabled"); h.succeed();
    }
    private static void queuedDepletionUnequip(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),1);
        var first=h.absolutePos(new BlockPos(2,2,2)); var second=first.east();
        var a=batch(h,p,first); var b=batch(h,p,second);
        run(a,p); run(b,p); finishRender(h,first); finishRender(h,second);
        h.assertTrue(h.getLevel().getBlockState(first).is(Blocks.STONE) && h.getLevel().isEmptyBlock(second) && ConsumptionTests.stock(p,0)==0,"Queued build reused exhausted stock");
        ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),1);
        var pendant=TestPlayers.necklace(p).getStackInSlot(0); b=batch(h,p,second);
        TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY); run(b,p);
        TestPlayers.necklace(p).setStackInSlot(0,pendant);
        h.assertTrue(h.getLevel().isEmptyBlock(second) && ConsumptionTests.stock(p,0)==1,"Queued build accessed unequipped cache");
        h.getLevel().setBlockAndUpdate(second,callbackBlock.defaultBlockState()); b=batch(h,p,second);
        CALLBACKS.put(second,()->TestPlayers.necklace(p).setStackInSlot(0,ItemStack.EMPTY));
        try { run(b,p); finishRender(h,second); }
        finally { CALLBACKS.remove(second); TestPlayers.necklace(p).setStackInSlot(0,pendant); }
        h.assertTrue(h.getLevel().getBlockState(second).is(callbackBlock) && ConsumptionTests.stock(p,0)==1,"Unequip during placement lost original block or bypassed captured guard"); h.succeed();
    }
    @SuppressWarnings("unchecked") private static Map<UUID,Object> queue() throws ReflectiveOperationException {
        return (Map<UUID,Object>)Class.forName(ROOT+"common.events.ServerTickHandler").getField("buildMap").get(null);
    }
    private static void history(GameTestHelper h,ServerPlayer p,Object batch) throws ReflectiveOperationException {
        Class.forName(ROOT+"util.GadgetUtils").getMethod("addToUndoList",Level.class,ItemStack.class,ArrayList.class,UUID.class)
                .invoke(null,h.getLevel(),p.getMainHandItem(),new ArrayList<>(),batch.getClass().getField("buildUUID").get(batch));
    }
    private static int inventory(ServerPlayer p,Item item) { return p.getInventory().items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void exchangeUndo(GameTestHelper h) throws ReflectiveOperationException {
        for(boolean beforeAnimation : new boolean[]{false,true}) for(boolean cached : new boolean[]{false,true}) {
            var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),cached?3:0);
            if(!cached) p.getInventory().setItem(9,new ItemStack(Items.STONE,3));
            var pos=h.absolutePos(new BlockPos(beforeAnimation?3:2,2,3)); h.getLevel().setBlockAndUpdate(pos,Blocks.DIRT.defaultBlockState());
            var batch=batch(h,p,pos,Blocks.STONE.defaultBlockState(),"EXCHANGE"); var gadget=p.getMainHandItem();
            var energy=gadget.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM); h.assertTrue(energy!=null,"Native gadget energy capability missing"); energy.receiveEnergy(100000,false);
            history(h,p,batch); run(batch,p); if(!beforeAnimation) finishRender(h,pos);
            h.assertTrue(ConsumptionTests.stock(p,0)==(cached?2:0) && inventory(p,Items.STONE)==(cached?0:2) && inventory(p,Items.DIRT)==1,"Native exchange did not debit cache/return original once");
            gadget.getItem().getClass().getMethod("undo",Level.class,Player.class,ItemStack.class).invoke(gadget.getItem(),h.getLevel(),p,gadget);
            for(var entry:new ArrayList<>(queue().entrySet())) if(entry.getValue().getClass().getField("playerUUID").get(entry.getValue()).equals(p.getUUID())) {
                run(entry.getValue(),p); queue().remove(entry.getKey());
            }
            finishRender(h,pos);
            // Unenchanted native exchanger breaks stone into cobblestone, including on undo.
            h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.DIRT) && inventory(p,Items.DIRT)==0 && inventory(p,Items.STONE)==(cached?0:2) && inventory(p,Items.COBBLESTONE)==1 && ConsumptionTests.stock(p,0)==(cached?2:0),
                    "Native undo mismatch beforeAnimation="+beforeAnimation+" block="+h.getLevel().getBlockState(pos)+" dirt="+inventory(p,Items.DIRT)+" stone="+inventory(p,Items.STONE)+" cobble="+inventory(p,Items.COBBLESTONE)+" cache="+ConsumptionTests.stock(p,0));
            gadget.getItem().getClass().getMethod("undo",Level.class,Player.class,ItemStack.class).invoke(gadget.getItem(),h.getLevel(),p,gadget);
            h.assertTrue(inventory(p,Items.COBBLESTONE)==1 && ConsumptionTests.stock(p,0)==(cached?2:0),"Repeated undo duplicated materials");
        }
        h.succeed();
    }
    private static void fullInventoryUndo(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.OAK_PLANKS),3); var pos=h.absolutePos(new BlockPos(3,2,3));
        var batch=batch(h,p,pos,Blocks.OAK_PLANKS.defaultBlockState(),"BUILD"); var gadget=p.getMainHandItem();
        gadget.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM).receiveEnergy(100000,false);
        history(h,p,batch); run(batch,p); finishRender(h,pos);
        for(int i=1;i<36;i++) p.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        gadget.getItem().getClass().getMethod("undo",Level.class,Player.class,ItemStack.class).invoke(gadget.getItem(),h.getLevel(),p,gadget);
        for(var entry:new ArrayList<>(queue().entrySet())) if(entry.getValue().getClass().getField("playerUUID").get(entry.getValue()).equals(p.getUUID())) {
            run(entry.getValue(),p); queue().remove(entry.getKey());
        }
        finishRender(h,pos);
        int dropped=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(4)).stream()
                .map(net.minecraft.world.entity.item.ItemEntity::getItem).filter(s->s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
        h.assertTrue(h.getLevel().isEmptyBlock(pos) && ConsumptionTests.stock(p,0)==2 && inventory(p,Items.OAK_PLANKS)==0 && dropped==1,"Full native inventory undo lost/duplicated material or returned directly to cache: drops="+dropped); h.succeed();
    }
    private static void exchangeReentrantLoss(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); var pos=h.absolutePos(new BlockPos(3,2,3));
        p.getInventory().setItem(9,new ItemStack(Items.STONE)); h.getLevel().setBlockAndUpdate(pos,callbackBlock.defaultBlockState());
        var batch=batch(h,p,pos,Blocks.STONE.defaultBlockState(),"EXCHANGE"); history(h,p,batch);
        CALLBACKS.put(pos,()->{p.getEnderChestInventory().setItem(0,p.getInventory().getItem(9));p.getInventory().setItem(9,ItemStack.EMPTY);});
        try { run(batch,p); finishRender(h,pos); }
        finally { CALLBACKS.remove(pos); }
        h.assertTrue(h.getLevel().getBlockState(pos).is(callbackBlock) && inventory(p,Items.DIRT)==0 && p.getEnderChestInventory().getItem(0).getCount()==1
                && ((List<?>)batch.getClass().getField("actuallyBuildList").get(batch)).isEmpty(),"Failed exchange refunded original or recorded unpaid undo"); h.succeed();
    }
    private static void canceledAndCreative(GameTestHelper h) throws ReflectiveOperationException {
        var p=player(h); ConsumptionTests.seed(p,0,new ItemStack(Items.STONE),3); var pos=h.absolutePos(new BlockPos(3,2,3));
        var batch=batch(h,p,pos); var gadget=p.getMainHandItem(); var energy=gadget.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        energy.receiveEnergy(100000,false); int before=energy.getEnergyStored();
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getEntity()==p)e.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try {
            var id=(UUID)Class.forName(ROOT+"util.BuildingUtils").getMethod("build",Level.class,Player.class,ArrayList.class,BlockPos.class,ItemStack.class,boolean.class)
                    .invoke(null,h.getLevel(),p,batch.getClass().getField("statePosList").get(batch),BlockPos.ZERO,gadget,true);
            h.assertTrue(!queue().containsKey(id) && energy.getEnergyStored()==before && ConsumptionTests.stock(p,0)==3 && h.getLevel().isEmptyBlock(pos),"Canceled native entry spent energy/material or queued construction");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel); }
        p.setGameMode(GameType.CREATIVE); run(batch,p); finishRender(h,pos);
        h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.STONE) && ConsumptionTests.stock(p,0)==3,"Creative native construction consumed cache"); h.succeed();
    }
    private static void shapeGuard(GameTestHelper h) {
        var sources=bytecode(ROOT+"util.BuildingUtils"); var build=bytecode(ROOT+"common.events.ServerTickHandler");
        h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.hasGadgetsPorts(sources,build),"Author artifact failed complete guard");
        for(var name:List.of("removeStacksFromInventory","countItemStacks","build","exchange")) {
            var node=name.equals("build")||name.equals("exchange")?build:sources;
            var method=node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow(); node.methods.remove(method);
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasGadgetsPorts(sources,build),"Partial adapter accepted missing "+name); node.methods.add(method);
        }
        for(var name:List.of("build","exchange")) {
            var changed=bytecode(ROOT+"common.events.ServerTickHandler");
            var method=changed.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
            org.objectweb.asm.tree.AbstractInsnNode placement=null, second=null; int removes=0;
            for(var instruction:method.instructions) if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if(call.name.equals("setBlockAndUpdate")) placement=call;
                if(call.name.equals("removeStacksFromInventory") && ++removes==2) second=call;
            }
            // Keep signatures and call counts intact but move the debit ahead of world placement.
            method.instructions.remove(second);method.instructions.insertBefore(placement,second);
            h.assertTrue(!dev.scathiard.feedmepackages.mixin.OptionalMixins.hasGadgetsPorts(sources,changed),"Changed native call order accepted: "+name);
        }
        h.succeed();
    }
    private static org.objectweb.asm.tree.ClassNode bytecode(String name) {
        try(var stream=GadgetsTests.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            if(stream==null) throw new IllegalStateException("Missing native class "+name);
            var node=new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(stream).accept(node,0); return node;
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
