package dev.scathiard.feedmepackages.compat.ars;

import com.hollingsworth.arsnouveau.api.item.inv.ExtractedStack;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.spell.wrapped_caster.PlayerCaster;
import dev.scathiard.feedmepackages.consumption.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.util.*;
import java.util.function.Predicate;

/** One original EffectPlaceBlock invocation, including its own proxy, selection and remainder path. */
public final class ArsMaterials {
    private ArsMaterials() {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final ServerPlayer player;
        private final List<CachedStack> extracted=new ArrayList<>();
        private Scope(ServerPlayer player) { this.player=player; previous=CURRENT.get(); CURRENT.set(this); }
        @Override public void close() { try { for(var entry:extracted) entry.settle(); } finally { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); } }
    }
    public static Scope open(LivingEntity shooter, SpellContext context) {
        ServerPlayer player=shooter instanceof ServerPlayer server && !(server instanceof FakePlayer)
                && context.getCaster() instanceof PlayerCaster caster && caster.player==server ? server : null;
        return new Scope(player);
    }
    public static ExtractedStack fallback(ExtractedStack nativeResult, Predicate<ItemStack> predicate, int count, boolean random) {
        var scope=CURRENT.get();
        if(!dev.scathiard.feedmepackages.FmpConfig.arsEnabled() || !nativeResult.isEmpty() || scope==null || scope.player==null || count!=1) return nativeResult;
        var tx=MaterialTransaction.open(scope.player).orElse(null); if(tx==null) return nativeResult;
        var candidates=tx.candidates(s->s.getItem() instanceof BlockItem && predicate.test(s))
                .stream().filter(s->tx.cacheAvailable(s)>0).toList();
        if(candidates.isEmpty()) return nativeResult;
        var selected=candidates.get(random?scope.player.getRandom().nextInt(candidates.size()):0);
        var lease=CacheUseLease.reserve(scope.player,selected).orElse(null); if(lease==null) return nativeResult;
        var extracted=new CachedStack(lease,selected.copyWithCount(1)); scope.extracted.add(extracted);return extracted;
    }
    public static boolean allowed(ItemStack stack) {
        var scope=CURRENT.get(); if(scope==null) return true;
        for(var entry:scope.extracted) if(entry.stack==stack) return entry.lease.valid();
        return true;
    }
    private static final class CachedStack extends ExtractedStack {
        final CacheUseLease lease; boolean settled;
        CachedStack(CacheUseLease lease,ItemStack stack) { super(stack,new ItemStackHandler(1),0);this.lease=lease; }
        void settle() {
            if(settled) return;
            try { if(stack.isEmpty()) lease.spent(); }
            finally { stack.setCount(0); lease.close();settled=true; }
        }
        @Override public void returnOrDrop(Level level,BlockPos pos) { settle(); }
        @Override public ItemStack returnStack() { settle(); return ItemStack.EMPTY; }
    }
}
