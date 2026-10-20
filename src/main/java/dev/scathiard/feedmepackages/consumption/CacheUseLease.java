package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** A real cache item reserved for one native use. No physical copy is ever exposed to a menu. */
public final class CacheUseLease implements AutoCloseable {
    private static final Set<CacheUseLease> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long epoch;
    public final ServerPlayer player;
    public final CacheHandle handle;
    public final int slot;
    public final ItemVariantKey key;
    private final long filterRevision;
    private boolean closed, spent;
    private CacheUseLease(ServerPlayer player, CacheHandle handle, int slot, ItemVariantKey key, long revision) {
        this.player=player; this.handle=handle; this.slot=slot; this.key=key; filterRevision=revision;
    }
    public static Optional<CacheUseLease> reserve(ServerPlayer player, ItemStack prototype) {
        var access=AccessGate.resolve(player);
        if (!access.active() || !player.getServer().isSameThread() || CraftingReservations.operating(access.handle().cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId())) return Optional.empty();
        var record=CacheLedger.get(player.getServer()).find(access.handle().cacheId());
        var key=ItemVariantKey.of(prototype,player.registryAccess()); int slot=record.state().find(key);
        if(slot<0) return Optional.empty(); var cell=record.state().cells().get(slot);
        if(cell.amount()<=CraftingReservations.reservedCache(access.handle().cacheId(),slot,null)) return Optional.empty();
        var lease=new CacheUseLease(player,access.handle(),slot,key,cell.filterRevision()); LIVE.add(lease);epoch++;
        return Optional.of(lease);
    }
    public static long epoch() { return epoch; }
    public static int reserved(UUID cache,int slot) {
        return (int)LIVE.stream().filter(l->!l.spent && l.handle.cacheId().equals(cache) && l.slot==slot).count();
    }
    public boolean valid() {
        var access=AccessGate.resolve(player);
        return !closed && !spent && player.isAlive() && !player.isSpectator() && access.active() && handle.equals(access.handle()) && owned();
    }
    private boolean owned() {
        var record=CacheLedger.get(player.getServer()).find(handle.cacheId());
        if(record==null || slot>=record.state().cells().size()) return false;
        var cell=record.state().cells().get(slot);
        return key.equals(cell.filter()) && filterRevision==cell.filterRevision()
                && cell.amount()>=CraftingReservations.reservedCache(handle.cacheId(),slot,null);
    }
    /** Called after the already authorized native operation consumed its one item, even if an effect callback unequipped it. */
    public void spent() {
        if(closed || spent || !owned()) throw new IllegalStateException("Native cache use lost its reserved item");
        var ledger=CacheLedger.get(player.getServer()); var record=ledger.find(handle.cacheId()); var edit=record.state().edit();
        if(edit.extract(slot,1)!=1) throw new IllegalStateException("Native cache use underflow");
        ledger.replace(handle,record.state().revision(),record.withState(edit.finish())); spent=true;epoch++;
    }
    public boolean consumed() { return spent; }
    @Override public void close() { if(!closed) { LIVE.remove(this);closed=true;epoch++; } }
}
