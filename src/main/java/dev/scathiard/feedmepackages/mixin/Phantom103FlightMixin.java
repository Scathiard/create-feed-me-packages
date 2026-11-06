package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.*;
import dev.scathiard.feedmepackages.compat.maid.phantom.v103.Phantom103Flight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Only recipient lookup and item handover change; native 1.0.3 owns every flight and failure transition. */
@Pseudo
@Mixin(targets="com.yision.phantom.logistics.courier.AirCourierTask", remap=false)
public abstract class Phantom103FlightMixin implements PhantomMaidTaskAccess {
    @Unique private final PhantomMaidTaskState fmp$state = new PhantomMaidTaskState();
    @Override public PhantomMaidTaskState fmp$maidState() { return fmp$state; }
    @Inject(method="save",at=@At("RETURN"),remap=false)
    private void fmp$save(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        fmp$state.write(cir.getReturnValue());
    }
    @Inject(method="load",at=@At("RETURN"),remap=false)
    private static void fmp$load(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof PhantomMaidTaskAccess task) task.fmp$maidState().read(tag);
    }
    @Inject(method="forPackageToPlayer",at=@At("RETURN"),remap=false)
    private static void fmp$markRecipient(UUID id, ItemStack box, ServerLevel level, UUID recipient, ResourceKey<Level> dimension,
            Vec3 position, Vec3 direction, Vec3 motion, ResourceKey<Level> sourceDimension, BlockPos sourcePort,
            UUID hudPlayer, UUID hudEntry, UUID source, CallbackInfoReturnable<Object> cir) {
        if (!Phantom103Flight.enabled()) return;
        var maid = MaidAccess.find(level.getServer(), com.simibubi.create.content.logistics.box.PackageItem.getAddress(box));
        if (maid != null && maid.getUUID().equals(recipient)) MaidPhantomTasks.remember(cir.getReturnValue(), maid);
    }
    @Coerce
    @WrapOperation(method="prepareFlightTarget",at=@At(value="INVOKE",target="Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService;targetState(Lnet/minecraft/server/MinecraftServer;Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService$TargetState;"),remap=false)
    private Object fmp$availability(MinecraftServer server, @Coerce Object mission, ResourceKey<Level> dimension,
            BlockPos port, UUID recipient, ItemStack box, Operation<Object> original) {
        Object state = Phantom103Flight.availability(this,server);
        return state == null ? original.call(server,mission,dimension,port,recipient,box) : state;
    }
    @Inject(method="forCarrierReturnToPlayer",at=@At("RETURN"),remap=false)
    private static void fmp$markCarrier(UUID id, ServerLevel level, UUID recipient, ResourceKey<Level> dimension,
            Vec3 position, Vec3 direction, Vec3 motion, CallbackInfoReturnable<Object> cir) {
        if (!Phantom103Flight.enabled()) return;
        var maid = MaidPhantomTasks.maidByEntity(level.getServer(),recipient);
        if (maid != null) MaidPhantomTasks.rememberReturn(cir.getReturnValue(),maid);
    }
    @Inject(method="resolveFlightTarget",at=@At("HEAD"),cancellable=true,remap=false)
    private void fmp$followMaid(MinecraftServer server, CallbackInfoReturnable<Object> cir) {
        Object target = Phantom103Flight.flightTarget(this,server);
        if (target != null) cir.setReturnValue(target);
    }
    @WrapOperation(method="doFinishDelivery",at=@At(value="INVOKE",target="Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService;finishDelivery(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/item/ItemStack;Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Ljava/util/UUID;Ljava/util/UUID;)Z"),remap=false)
    private boolean fmp$deliver(MinecraftServer server, ItemStack box, @Coerce Object mission, ResourceKey<Level> sourceDimension,
            BlockPos sourcePort, UUID source, ResourceKey<Level> targetDimension, BlockPos targetPort, UUID target,
            UUID hudPlayer, UUID hudEntry, Operation<Boolean> original) {
        Boolean delivered = Phantom103Flight.deliver(this,server);
        return delivered == null ? original.call(server,box,mission,sourceDimension,sourcePort,source,targetDimension,targetPort,target,hudPlayer,hudEntry) : delivered;
    }
    @Inject(method="startCarrierReturn",at=@At("HEAD"),remap=false)
    private void fmp$returnToDispatcher(MinecraftServer server, CallbackInfo ci) { Phantom103Flight.prepareReturn(this); }
}
