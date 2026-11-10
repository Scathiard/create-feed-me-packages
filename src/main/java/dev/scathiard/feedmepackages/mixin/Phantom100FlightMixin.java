package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** The original Phantom flight takes a resolved port/player rather than the later FlightTarget record. */
@Pseudo
@Mixin(targets = "com.yision.phantom.logistics.courier.AirCourierTask", remap = false)
public abstract class Phantom100FlightMixin implements PhantomMaidTaskAccess {
    @Unique private final PhantomMaidTaskState fmp$state = new PhantomMaidTaskState();
    @Unique private LivingEntity fmp$maid;
    @Override public PhantomMaidTaskState fmp$maidState() { return fmp$state; }

    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void fmp$save(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        fmp$state.write(cir.getReturnValue());
    }

    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private static void fmp$load(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof PhantomMaidTaskAccess access) access.fmp$maidState().read(tag);
    }

    @Inject(method = "resolveTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$target(MinecraftServer server, CallbackInfoReturnable<Object> cir) {
        fmp$maid = MaidPhantomTasks.maidRecipient(server, this);
        if (fmp$maid == null) fmp$maid = MaidPhantomTasks.carrierRecipient(server, this);
        if (fmp$maid == null) return;
        MaidPhantomTasks.followDimension(this, (ServerLevel) fmp$maid.level());
        cir.setReturnValue(MaidPhantomFlight.classicTarget(fmp$maid));
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightTargets;landingTarget(Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightProfile;Lcom/yision/phantom/block/phantomport/PhantomPortBlockEntity;Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/world/phys/Vec3;"), remap = false)
    private Vec3 fmp$landing(@Coerce Object profile, @Coerce Object port, ServerPlayer player, Operation<Vec3> original) {
        return fmp$maid != null && port == null && player == null ? MaidPhantomFlight.landing(fmp$maid) : original.call(profile, port, player);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightTargets;cruiseTarget(Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightProfile;Lcom/yision/phantom/block/phantomport/PhantomPortBlockEntity;Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/world/phys/Vec3;"), remap = false)
    private Vec3 fmp$cruise(@Coerce Object profile, @Coerce Object port, ServerPlayer player, Operation<Vec3> original) {
        return fmp$maid != null && port == null && player == null ? MaidPhantomFlight.cruise(fmp$maid) : original.call(profile, port, player);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightTargets;completionDistance(Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightProfile;Lcom/yision/phantom/block/phantomport/PhantomPortBlockEntity;Lnet/minecraft/server/level/ServerPlayer;)D"), remap = false)
    private double fmp$distance(@Coerce Object profile, @Coerce Object port, ServerPlayer player, Operation<Double> original) {
        return fmp$maid != null && port == null && player == null ? MaidPhantomFlight.completionDistance() : original.call(profile, port, player);
    }

    @Inject(method = "tickLanding", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$arrive(MinecraftServer server, ServerLevel level, CallbackInfo ci) {
        if (fmp$maid != null && fmp$maid.level() == level && MaidPhantomFlight.reached(fmp$maid, MaidPhantomTasks.position(this))
                && fmp$deliver(server)) ci.cancel();
    }

    @Inject(method = "doFinishDeliveryAt", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$finish(MinecraftServer server, ServerLevel level, CallbackInfo ci) {
        if (fmp$deliver(server)) ci.cancel();
    }

    @Unique private boolean fmp$deliver(MinecraftServer server) {
        var maid = MaidPhantomTasks.maidRecipient(server, this);
        if (maid != null) return MaidPhantomTasks.handOver(this, maid, server);
        maid = MaidPhantomTasks.carrierRecipient(server, this);
        if (maid == null) return false;
        var carrier = MaidPhantomTasks.nativeCarrier();
        if (carrier == null || carrier.isEmpty() || !MaidPhantomTasks.handCarrierTo(maid, carrier)) return false;
        return MaidPhantomTasks.endTask(this);
    }

    @Inject(method = "startCarrierReturn", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$return(MinecraftServer server, CallbackInfo ci) {
        if (MaidPhantomTasks.returnCarrierToMaid(this, server)) ci.cancel();
    }
}
