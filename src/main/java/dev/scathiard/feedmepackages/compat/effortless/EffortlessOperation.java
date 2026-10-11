package dev.scathiard.feedmepackages.compat.effortless;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import java.util.*;

/** Synchronous native batch settlement, not a replacement builder or a persistent world transaction. */
public final class EffortlessOperation implements AutoCloseable {
    private static final ThreadLocal<EffortlessOperation> CURRENT=new ThreadLocal<>();
    private final EffortlessOperation previous;
    private final ServerPlayer player;
    private final boolean active;
    private final List<Placement> placements=new ArrayList<>();
    private final List<Receipt> receipts=new ArrayList<>();
    private final Map<BlockPos,List<Runnable>> returns=new HashMap<>();
    private BlockPos position;
    private boolean billing,finished;
    private record Placement(BlockSnapshot before,BlockState after,BlockEntity entity,Item material,boolean placed) {}
    private record Receipt(ItemStack stack,CacheHandle cache) {}
    private EffortlessOperation(ServerPlayer player) {
        this.player=player;previous=CURRENT.get();var hand=player.getMainHandItem();
        boolean recognized=hand.getItem() instanceof BlockItem && hand.getComponentsPatch().isEmpty()
                || hand.getItem().getClass().getName().equals("neoforge.nl.requios.effortlessbuilding.item.RandomizerToolItem");
        active=recognized && !player.isCreative() && player.getServer().isSameThread()
                && EffortlessMaterials.enabled() && AccessGate.resolve(player).active();
        CURRENT.set(this);
    }
    public static EffortlessOperation open(ServerPlayer player) {return new EffortlessOperation(player);}
    public static EffortlessOperation current() {
        var value=CURRENT.get();return value!=null && value.active && !value.finished ? value : null;
    }
    public boolean owns(ServerPlayer candidate) {return candidate==player;}
    public void position(BlockPos pos) {position=pos.immutable();}
    public void placement(BlockSnapshot before,BlockState after,BlockEntity entity,Item material,boolean placed) {
        placements.add(new Placement(before,after,entity,material,placed));
    }
    public boolean deferReturn(ServerPlayer owner,Runnable give) {
        if(owner!=player || position==null)return false;
        returns.computeIfAbsent(position,key->new ArrayList<>()).add(give);return true;
    }
    public boolean billing(boolean value) {boolean old=billing;billing=value;return old;}
    public static void paidInventory(net.minecraft.world.entity.player.Player player,ItemStack removed) {
        var op=current();if(op!=null && op.billing && op.player==player && !removed.isEmpty())op.receipts.add(new Receipt(removed.copy(),null));
    }
    public static void paidCache(ServerPlayer player,CacheHandle handle,ItemStack removed) {
        var op=current();if(op!=null && op.billing && op.player==player && !removed.isEmpty())op.receipts.add(new Receipt(removed.copy(),handle));
    }
    private boolean fund(Item item) {
        for(var receipt:receipts)if(receipt.stack.is(item) && !receipt.stack.isEmpty()) {receipt.stack.shrink(1);return true;}
        return false;
    }
    /** Mutates the author's pending undo map before it becomes authoritative history. */
    public void settle(Map<BlockPos,?> undo) {
        if(!active || finished)return;
        finished=true;
        var accepted=new HashSet<BlockPos>();
        for(var placement:placements) {
            var pos=placement.before.getPos();
            if(placement.placed && fund(placement.material)) {accepted.add(pos);continue;}
            if(undo!=null)undo.remove(pos);
            var level=player.serverLevel();
            if(level.getBlockState(pos).equals(placement.after) && level.getBlockEntity(pos)==placement.entity) {
                if(!placement.before.restore())FeedMePackages.LOGGER.error("FMP could not restore unpaid Effortless placement at {}",pos);
            }
        }
        for(var receipt:receipts)refund(receipt);
        for(var pos:accepted)for(var action:returns.getOrDefault(pos,List.of()))action.run();
    }
    private void refund(Receipt receipt) {
        var stack=receipt.stack;if(stack.isEmpty())return;
        // Re-resolve ordinary access: refunds never grant access to an unequipped or different cache.
        if(receipt.cache!=null) {
            var access=AccessGate.resolve(player);
            if(access.active() && access.handle().equals(receipt.cache)) {
                var ledger=CacheLedger.get(player.getServer());var record=ledger.find(receipt.cache.cacheId());
                if(record!=null && ledger.returnEscrow(receipt.cache.cacheId())==null) {
                    var key=ItemVariantKey.of(stack,player.registryAccess());int slot=record.state().find(key);
                    if(slot>=0) {
                        var edit=record.state().edit();int restored=edit.insert(slot,key,stack.getCount());
                        if(restored>0) {ledger.replace(receipt.cache,record.state().revision(),record.withState(edit.finish()));stack.shrink(restored);}
                    }
                }
            }
        }
        while(!stack.isEmpty())ItemHandlerHelper.giveItemToPlayer(player,stack.split(Math.max(1,stack.getMaxStackSize())));
    }
    @Override public void close() {
        try {settle(null);}finally {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
