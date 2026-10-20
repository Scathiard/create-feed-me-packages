package dev.scathiard.feedmepackages.gametest;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.gametest.framework.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.*;

/** Optional classes load only after the native mod has registered its real spell pipeline. */
@GameTestHolder(FeedMePackages.MOD_ID)
public final class ArsTests {
    @GameTestGenerator public static Collection<TestFunction> installed() {
        return ModList.get().isLoaded("ars_nouveau") ? Native.tests() : List.of();
    }
    private static final class Native {
        static Collection<TestFunction> tests() {
            return List.of(test("native_material_priority_and_cache",Native::priority),test("native_protection_and_fake_caster",Native::protection),test("native_touch_mana_gate",Native::mana),test("native_aoe_partial_stock",Native::aoe));
        }
        static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body) {
            return new TestFunction("ars","fmp_ars_"+name,FeedMePackages.MOD_ID+":empty",100,0,true,body);
        }
        static net.minecraft.server.level.ServerPlayer player(GameTestHelper h) {
            h.assertTrue(dev.scathiard.feedmepackages.mixin.OptionalMixins.arsCompatible(),"Ars exact native ports disabled");
            var p=TestPlayers.real(h,UUID.randomUUID());TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);ConsumptionTests.seed(p,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE),7);
            h.setBlock(2,1,2,net.minecraft.world.level.block.Blocks.DIRT);return p;
        }
        static net.minecraft.world.phys.BlockHitResult hit(GameTestHelper h) {
            return new net.minecraft.world.phys.BlockHitResult(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5,2,2.5)),net.minecraft.core.Direction.UP,h.absolutePos(new net.minecraft.core.BlockPos(2,1,2)),false);
        }
        static com.hollingsworth.arsnouveau.api.spell.SpellResolver resolver(net.minecraft.server.level.ServerPlayer p) {
            var spell=new com.hollingsworth.arsnouveau.api.spell.Spell(com.hollingsworth.arsnouveau.common.spell.method.MethodTouch.INSTANCE,com.hollingsworth.arsnouveau.common.spell.effect.EffectPlaceBlock.INSTANCE);
            return new com.hollingsworth.arsnouveau.api.spell.SpellResolver(com.hollingsworth.arsnouveau.api.spell.SpellContext.fromEntity(spell,p,net.minecraft.world.item.ItemStack.EMPTY));
        }
        static void resolve(GameTestHelper h,net.minecraft.server.level.ServerPlayer p,boolean random) {
            var resolver=resolver(p);var builder=new com.hollingsworth.arsnouveau.api.spell.SpellStats.Builder();if(random)builder.randomize();
            com.hollingsworth.arsnouveau.common.spell.effect.EffectPlaceBlock.INSTANCE.onResolveBlock(hit(h),h.getLevel(),p,builder.build(),resolver.spellContext,resolver);
        }
        static void priority(GameTestHelper h) {
            var p=player(h);p.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
            resolve(h,p,false);
            h.assertTrue(h.getBlockState(new net.minecraft.core.BlockPos(2,2,2)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)
                    && ConsumptionTests.stock(p,0)==7 && p.getInventory().getItem(0).isEmpty(),"Native hotbar material lost priority");
            h.setBlock(2,2,2,net.minecraft.world.level.block.Blocks.AIR);resolve(h,p,true);
            h.assertTrue(h.getBlockState(new net.minecraft.core.BlockPos(2,2,2)).is(net.minecraft.world.level.block.Blocks.STONE)
                    && ConsumptionTests.stock(p,0)==6,"Native randomized placement did not use the exact cached block");
            h.assertTrue(dev.scathiard.feedmepackages.consumption.CacheUseLease.reserved(dev.scathiard.feedmepackages.service.AccessGate.resolve(p).handle().cacheId(),0)==0,"Ars placement stranded a lease");h.succeed();
        }
        static void protection(GameTestHelper h) {
            var p=player(h);
            java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> listener=e->{if(e.getEntity()!=null && e.getEntity().getUUID().equals(p.getUUID()))e.setCanceled(true);};
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
            try { resolve(h,p,false); } finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);}
            h.assertTrue(ConsumptionTests.stock(p,0)==7 && h.getBlockState(new net.minecraft.core.BlockPos(2,2,2)).isAir(),"Ars proxy protection was bypassed");
            var fake=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());ConsumptionTests.seed(fake,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE),7);
            resolve(h,fake,false);h.assertTrue(ConsumptionTests.stock(fake,0)==7 && h.getBlockState(new net.minecraft.core.BlockPos(2,2,2)).isAir(),"Autonomous fake caster took a pendant cache");h.succeed();
        }
        static void aoe(GameTestHelper h) {
            var p=player(h);
            var resolver=resolver(p);var stats=new com.hollingsworth.arsnouveau.api.spell.SpellStats.Builder().setAOE(3).build();
            var positions=com.hollingsworth.arsnouveau.api.util.SpellUtil.calcAOEBlocks(p,hit(h).getBlockPos(),hit(h),stats)
                    .stream().map(pos->pos.relative(net.minecraft.core.Direction.UP)).toList();
            h.assertTrue(positions.size()>7,"Native AOE fixture must exceed available stock");
            for(var pos:positions){h.getLevel().setBlockAndUpdate(pos.below(),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());h.getLevel().setBlockAndUpdate(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
            com.hollingsworth.arsnouveau.common.spell.effect.EffectPlaceBlock.INSTANCE.onResolveBlock(hit(h),h.getLevel(),p,stats,resolver.spellContext,resolver);
            long placed=positions.stream().filter(pos->h.getLevel().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.STONE)).count();
            h.assertTrue(placed==7 && ConsumptionTests.stock(p,0)==0,"Native AOE must stop at exactly seven cached blocks; placed="+placed+" area="+positions.size());
            h.succeed();
        }
        static void mana(GameTestHelper h) {
            var p=player(h);var mana=com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry.getMana(p);
            mana.setMana(0);var blocked=resolver(p);blocked.onCastOnBlock(hit(h));
            h.assertTrue(ConsumptionTests.stock(p,0)==7 && h.getBlockState(new net.minecraft.core.BlockPos(2,2,2)).isAir(),"Cache bypassed native mana gate");
            mana.setMaxMana(1000);mana.setMana(1000);double before=mana.getCurrentMana();var nativeResolver=resolver(p);
            boolean cast=nativeResolver.onCastOnBlock(hit(h));
            h.assertTrue(cast && ConsumptionTests.stock(p,0)==6 && mana.getCurrentMana()<before,"Native touch cast failed: cast="+cast+" stock="+ConsumptionTests.stock(p,0)+" mana="+before+"->"+mana.getCurrentMana()+" cost="+nativeResolver.getResolveCost()+" canceled="+nativeResolver.spellContext.getCancelReason());h.succeed();
        }
    }
}
