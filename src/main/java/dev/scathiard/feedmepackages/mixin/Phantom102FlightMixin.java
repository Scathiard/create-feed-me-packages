package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomFlight;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.MaidPhantomTasks;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Phantom 1.0.2, flight side (0.3.0 T4): a courier task whose native player slot holds one of <b>our</b>
 * maids follows her live position instead of failing to resolve a player.
 *
 * <p>Native facts this rests on, all measured: {@code resolveFlightTarget} is re-resolved every tick; the native
 * flight profile is only used when {@code FlightTarget.playerTarget()} is true, and the native port branch is used
 * otherwise; the native completion test is player-typed, so an unmodified task would hover and then return the
 * package. We therefore rebuild the target from her live position each tick, answer her availability ourselves,
 * force the arrival when she is actually reached, and take the delivery over at the native finish entry.
 *
 * <p>Fail-closed by construction: {@link OptionalMixins#phantom102FlightCompatible()} refuses to apply this mixin
 * at all when the task shape differs, and every branch below returns without cancelling unless a maid task is
 * positively identified - a player task is never touched, byte for byte.
 */
@Pseudo
@Mixin(targets = "com.yision.phantom.logistics.courier.AirCourierTask", remap = false)
public abstract class Phantom102FlightMixin implements dev.scathiard.feedmepackages.compat.maid.phantom.v102.PhantomMaidTaskAccess {

    @Unique private final dev.scathiard.feedmepackages.compat.maid.phantom.v102.PhantomMaidTaskState fmp$state =
            new dev.scathiard.feedmepackages.compat.maid.phantom.v102.PhantomMaidTaskState();

    @Override public dev.scathiard.feedmepackages.compat.maid.phantom.v102.PhantomMaidTaskState fmp$maidState() { return fmp$state; }

    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void fmp$save(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag tag,
            CallbackInfoReturnable<net.minecraft.nbt.CompoundTag> cir) {
        fmp$state.write(cir.getReturnValue());
    }

    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private static void fmp$load(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag tag,
            CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof dev.scathiard.feedmepackages.compat.maid.phantom.v102.PhantomMaidTaskAccess access)
            access.fmp$maidState().read(tag);
    }

    /** The maid this task follows right now; refreshed on every flight resolution. */
    @Unique private LivingEntity fmp$maid;
    /** The maid this task was last seen tracking, kept only so a transition can be reported once. */
    @Unique private LivingEntity fmp$tracked;

    @Unique private boolean fmp$enabled() {
        return Phantom102Gate.usable(true) && OptionalMixins.phantom102FlightCompatible();
    }

    @Inject(method = "startCarrierReturn", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$returnToDispatcher(MinecraftServer server, CallbackInfo ci) {
        if (fmp$enabled() && MaidPhantomTasks.returnCarrierToMaid(this, server)) ci.cancel();
    }

    /** Rebuild the flight target from her live position every tick, so she is followed rather than a stale point. */
    @Inject(method = "resolveFlightTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$trackMaid(MinecraftServer server, CallbackInfoReturnable<Object> cir) {
        // Debug-only task state; no global set of task UUIDs is retained for diagnostic deduplication.
        String registry = MaidPhantomTasks.registryState(this);
        java.util.UUID playerSlot = MaidPhantomTasks.targetId(this);
        boolean maidAddressed = playerSlot != null
                && MaidPhantomTasks.maidByEntity(server, playerSlot) != null;
        if (!"NONE".equals(registry) || maidAddressed) {
            MaidPhantomTasks.note("task seen: mission=" + MaidPhantomTasks.missionName(this)
                    + " boxEmpty=" + MaidPhantomTasks.boxIsEmpty(this)
                    + " undelivered=" + MaidPhantomTasks.undeliveredReturn(this)
                    + " target=" + playerSlot + " targetIsMaidAddress=" + maidAddressed
                    + " registry=" + registry
                    + " returnsToSourcePlayer=" + MaidPhantomTasks.returnsToSourcePlayer(this)
                    + " phase=" + MaidPhantomTasks.phaseName(this));
        }
        fmp$maid = null;
        if (!fmp$enabled()) return;
        LivingEntity maid = MaidPhantomTasks.maidRecipient(server, this);
        String leg = "outbound";
        // Return leg with no source port (the carrier would go to her owner): the native player slot holds that owner,
        // byte-identical to a player's own carrier return, so only our own registry can tell them apart. A carrier
        // return that DOES have a source port must stay native - it flies home to that port, which the user verified.
        if (maid == null && MaidPhantomTasks.returnsToSourcePlayer(this)) {
            maid = MaidPhantomTasks.carrierRecipient(server, this);
            leg = "return";
        }
        if (maid == null) {
            // The most useful diagnostic there is: a task that used to be ours and stopped being ours, with its phase.
            if (fmp$tracked != null) {
                MaidPhantomTasks.note("stopped tracking " + fmp$tracked.getUUID() + " at phase "
                        + MaidPhantomTasks.phaseName(this) + " undeliveredReturn=" + MaidPhantomTasks.undeliveredReturn(this));
                fmp$tracked = null;
            }
            return;
        }
        fmp$tracked = maid;
        MaidPhantomTasks.note("tracking " + maid.getUUID() + " (" + leg + ") phase=" + MaidPhantomTasks.phaseName(this));
        Object target = MaidPhantomFlight.flightTarget(maid);
        if (target == null) {
            MaidPhantomTasks.note("no native flight target could be built for " + maid.getUUID());
            return;
        }
        fmp$maid = maid;
        if (maid.level() instanceof ServerLevel level) MaidPhantomTasks.followDimension(this, level);
        cir.setReturnValue(target);
    }

    /** Cargo needs an active pendant; returning an empty carrier only needs the source maid to be present. */
    @Inject(method = "isDestinationAvailable", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$maidAvailable(@Coerce Object target, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity maid = fmp$maid;
        if (maid == null || maid.isRemoved() || !maid.isAlive()) return;
        if (!MaidPhantomTasks.returnsToSourcePlayer(this) && !MaidAccess.resolve(maid).active()) return;
        cir.setReturnValue(Boolean.TRUE);
    }

    /**
     * Force the arrival. The native landing test needs a player, so a maid task would otherwise never finish.
     * The arrival is judged against her live position and only then handed to the delivery step.
     */
    @Inject(method = "tickLanding", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$landOnMaid(MinecraftServer server, ServerLevel currentLevel, @Coerce Object target, CallbackInfo ci) {
        LivingEntity maid = fmp$maid;
        if (maid == null || currentLevel == null || maid.level() != currentLevel) return;
        if (!MaidPhantomFlight.reached(maid, MaidPhantomTasks.position(this))) return;
        MaidPhantomTasks.note("reached " + maid.getUUID() + " at phase " + MaidPhantomTasks.phaseName(this));
        if (fmp$handOver(server)) ci.cancel();
    }

    /** The native finish entry, in case the native landing did reach it on its own. */
    @Inject(method = "doFinishDeliveryAt", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$deliverAtMaid(MinecraftServer server, ServerLevel level, CallbackInfo ci) {
        if (fmp$maid == null) return;
        if (fmp$handOver(server)) ci.cancel();
    }

    /** The delivery core lives in {@link MaidPhantomTasks#handOver} so it is reachable from tests with a real task. */
    @Unique private boolean fmp$handOver(MinecraftServer server) {
        LivingEntity maid = fmp$maid;
        if (maid == null || server == null) return false;
        if (MaidPhantomTasks.returnsToSourcePlayer(this)) {
            // The native carrier does not exist during flight - the target mod creates it from its own item registry at
            // the moment of delivery. We create it from that same source and hand it to her. When she cannot take it we
            // do not cancel: the native path then drops it exactly where the courier now is, at her feet, instead of us
            // inventing a drop or swallowing the item.
            var carrier = MaidPhantomTasks.nativeCarrier();
            if (carrier == null || carrier.isEmpty()) {
                MaidPhantomTasks.note("the native carrier item could not be created");
                return false;
            }
            if (!MaidPhantomTasks.handCarrierTo(maid, carrier)) return false;
            MaidPhantomTasks.note("carrier handed to " + maid.getUUID());
            MaidPhantomTasks.endTask(this);
            return true;
        }
        return MaidPhantomTasks.handOver(this, maid, server);
    }
}
