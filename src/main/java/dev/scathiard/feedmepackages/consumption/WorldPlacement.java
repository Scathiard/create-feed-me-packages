package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import java.util.UUID;

/** One synchronous native placement. Block snapshots stay owned by NeoForge, not a second rollback engine. */
public final class WorldPlacement {
    private WorldPlacement() {}
    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    private static long epoch;
    private static final class Lease {
        final ServerPlayer player; final CacheHandle handle; final int cell; final ItemVariantKey key;
        final ItemStack tool, material, prototype; final int offered; final UseOnContext context;
        int consumed; boolean observed, validResult, committed, nativeRollback;
        java.util.List<net.neoforged.neoforge.common.util.BlockSnapshot> snapshots = java.util.List.of();
        Lease(ServerPlayer player, CacheHandle handle, int cell, ItemVariantKey key, ItemStack material, UseOnContext context) {
            this.player = player; this.handle = handle; this.cell = cell; this.key = key; this.material = material;
            this.offered = material.getCount(); this.prototype = material.copyWithCount(1); this.tool = player.getMainHandItem(); this.context = context;
        }
    }
    public static long epoch() { return epoch; }
    public static int reserved(UUID cache, int cell) {
        var lease = CURRENT.get();
        return lease != null && !lease.committed && lease.handle.cacheId().equals(cache) && lease.cell == cell ? lease.offered : 0;
    }
    public static boolean material(ItemStack stack) { var lease = CURRENT.get(); return lease != null && lease.material == stack; }
    public static boolean context(UseOnContext context) { var lease = CURRENT.get(); return lease != null && lease.context == context; }
    public static InteractionResult use(ServerPlayer player, CacheHandle handle, int cell, ItemVariantKey key,
                                        ItemStack material, UseOnContext original) {
        var level = player.level();
        if (CURRENT.get() != null || level.captureBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) return PalletService.failed(player, "busy");
        var hit = new BlockHitResult(original.getClickLocation(), original.getClickedFace(), original.getClickedPos(), original.isInside());
        var context = new UseOnContext(level, player, original.getHand(), material, hit);
        var lease = new Lease(player, handle, cell, key, material, context); CURRENT.set(lease); epoch++;
        boolean returned = false;
        try {
            InteractionResult result = material.useOn(context);
            returned = true;
            if (!lease.committed) return PalletService.failed(player, result == InteractionResult.FAIL ? "denied" : "no_placement");
            return result;
        } finally {
            // Native hooks do not protect their capture flag when a mod callback throws. Restore the
            // original native snapshots only for an uncommitted/unfinished placement, then release our lease.
            try {
                if (!lease.committed && (!returned || !lease.nativeRollback)) {
                    var snapshots = lease.snapshots.isEmpty() ? java.util.List.copyOf(level.capturedBlockSnapshots) : lease.snapshots;
                    level.captureBlockSnapshots = false; level.capturedBlockSnapshots.clear();
                    for (int i = snapshots.size() - 1; i >= 0; i--) {
                        level.restoringBlockSnapshots = true;
                        try { var snapshot = snapshots.get(i); snapshot.restore(snapshot.getFlags() | net.minecraft.world.level.block.Block.UPDATE_CLIENTS); }
                        finally { level.restoringBlockSnapshots = false; }
                    }
                }
            } finally { level.captureBlockSnapshots = false; level.capturedBlockSnapshots.clear(); CURRENT.remove(); epoch++; }
        }
    }
    /** Runs immediately after the real Item.useOn, before NeoForge temporarily restores stack counts for listeners. */
    public static void observed(UseOnContext context, InteractionResult result) {
        var lease = CURRENT.get(); if (lease == null || lease.context != context) return;
        lease.observed = true; lease.consumed = lease.offered - lease.material.getCount();
        lease.snapshots = java.util.List.copyOf(context.getLevel().capturedBlockSnapshots);
        lease.validResult = result.consumesAction() && lease.consumed > 0 && lease.consumed <= lease.offered
                && (lease.material.isEmpty() || ItemStack.isSameItemSameComponents(lease.prototype, lease.material));
    }
    /** All native block-place listeners have run. true asks NeoForge to restore its own captured blocks. */
    public static boolean veto(UseOnContext context, boolean alreadyCanceled) {
        var lease = CURRENT.get(); if (lease == null || lease.context != context) return alreadyCanceled;
        lease.nativeRollback = true;
        if (alreadyCanceled) return true;
        if (lease.committed || !lease.observed || !lease.validResult || !PalletService.holding(lease.player)
                || lease.player.getMainHandItem() != lease.tool || lease.player.level() != context.getLevel()) return true;
        var access = AccessGate.resolve(lease.player);
        if (!access.active() || !access.handle().equals(lease.handle)) return true;
        var ledger = CacheLedger.get(lease.player.getServer()); var record = ledger.find(lease.handle.cacheId());
        if (record == null || lease.cell >= record.state().cells().size()) return true;
        var cell = record.state().cells().get(lease.cell);
        int others = CraftingReservations.reservedCache(lease.handle.cacheId(), lease.cell, null) - lease.offered;
        if (!lease.key.equals(cell.filter()) || cell.amount() - others < lease.consumed) return true;
        var edit = record.state().edit();
        if (edit.extract(lease.cell, lease.consumed) != lease.consumed) return true;
        ledger.replace(lease.handle, record.state().revision(), record.withState(edit.finish()));
        lease.committed = true; lease.nativeRollback = false; epoch++; return false;
    }
}
