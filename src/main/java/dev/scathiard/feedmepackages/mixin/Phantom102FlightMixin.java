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
public abstract class Phantom102FlightMixin {

    /** The maid this task follows right now; refreshed on every flight resolution. */
    @Unique private LivingEntity fmp$maid;

    @Unique private boolean fmp$enabled() {
        return Phantom102Gate.usable(true) && OptionalMixins.phantom102FlightCompatible();
    }

    /** Rebuild the flight target from her live position every tick, so she is followed rather than a stale point. */
    @Inject(method = "resolveFlightTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$trackMaid(MinecraftServer server, CallbackInfoReturnable<Object> cir) {
        fmp$maid = null;
        if (!fmp$enabled()) return;
        LivingEntity maid = MaidPhantomTasks.maidRecipient(server, this);
        // Return leg (the carrier coming home): the package is gone and the native player slot holds her owner, which
        // is byte-identical to a player's own carrier return - only our own registry can tell them apart.
        if (maid == null && MaidPhantomTasks.onReturnLeg(this)) maid = MaidPhantomTasks.maidFor(this, server);
        if (maid == null) return;
        Object target = MaidPhantomFlight.flightTarget(maid);
        if (target == null) return;
        fmp$maid = maid;
        if (maid.level() instanceof ServerLevel level) MaidPhantomTasks.followDimension(this, level);
        cir.setReturnValue(target);
    }

    /** She is a valid destination while she is alive, present and wearing an active pendant. */
    @Inject(method = "isDestinationAvailable", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$maidAvailable(@Coerce Object target, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity maid = fmp$maid;
        if (maid == null || maid.isRemoved() || !maid.isAlive()) return;
        if (!MaidAccess.resolve(maid).active()) return;
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
        if (MaidPhantomTasks.onReturnLeg(this)) {
            // The native carrier does not exist during flight - the target mod creates it from its own item registry at
            // the moment of delivery. We create it from that same source and hand it to her. When she cannot take it we
            // do not cancel: the native path then drops it exactly where the courier now is, at her feet, instead of us
            // inventing a drop or swallowing the item.
            var carrier = MaidPhantomTasks.nativeCarrier();
            if (carrier == null || carrier.isEmpty()) return false;
            if (!MaidPhantomTasks.handCarrierTo(maid, carrier)) return false;
            MaidPhantomTasks.endTask(this);
            return true;
        }
        return MaidPhantomTasks.handOver(this, maid, server);
    }
}
