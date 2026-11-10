package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.scathiard.feedmepackages.compat.biotech.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets = {"com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierTask", "com.yision.allay.logistics.courier.AllayCourierTask"}, remap = false)
public abstract class BiotechFlightMixin implements BiotechTaskLink {
    @Unique private UUID fmp$target, fmp$source, fmp$owner;
    public UUID fmp$target() { return fmp$target; }
    public UUID fmp$source() { return fmp$source; }
    public UUID fmp$owner() { return fmp$owner; }
    public void fmp$target(UUID value) { fmp$target = value; }
    public void fmp$source(UUID value) { fmp$source = value; }
    public void fmp$owner(UUID value) { fmp$owner = value; }
    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void fmp$save(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        BiotechMaidFlight.save(this, cir.getReturnValue());
    }
    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private static void fmp$load(HolderLookup.Provider registry, CompoundTag tag, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof BiotechTaskLink link) BiotechMaidFlight.load(link, tag);
    }
    @Inject(method = "forPackageToPlayer", at = @At("RETURN"), remap = false)
    private static void fmp$maidTarget(UUID id, ItemStack box, ServerLevel level, UUID target, ResourceKey<Level> dimension,
            Vec3 position, Vec3 direction, ResourceKey<Level> sourceDimension, BlockPos sourcePort, UUID subLevel, UUID sourcePlayer,
            @Coerce Object mode, CallbackInfoReturnable<Object> cir) {
        BiotechCompatibility.markTarget(cir.getReturnValue(), level, box);
    }
    @Inject(method = "resolveTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$loadedMaid(MinecraftServer server, CallbackInfoReturnable<Object> cir) {
        if (BiotechMaidFlight.targetsMaid(this)) cir.setReturnValue(BiotechMaidFlight.resolve(this, server));
    }
    @Inject(method = "landingTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$landing(@Coerce Object port, ServerPlayer player, CallbackInfoReturnable<Vec3> cir) {
        if (port == null && BiotechMaidFlight.targetsMaid(this)) cir.setReturnValue(BiotechMaidFlight.position(this, false));
    }
    @Inject(method = "nearTargetWaypoint", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$approach(@Coerce Object port, ServerPlayer player, CallbackInfoReturnable<Vec3> cir) {
        if (port == null && BiotechMaidFlight.targetsMaid(this)) cir.setReturnValue(BiotechMaidFlight.position(this, true));
    }
    @Inject(method = "hasReachedPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$arrival(ServerPlayer player, Vec3 landing, CallbackInfoReturnable<Boolean> cir) {
        if (BiotechMaidFlight.targetsMaid(this)) cir.setReturnValue(BiotechMaidFlight.reached(this, landing));
    }
    @Coerce
    @WrapOperation(method = "doFinishDeliveryAt", at = {@At(value = "INVOKE", target = "Lcom/nobodiiiii/createbiotech/content/allay/logistics/courier/AllayCourierDeliveryService;finishDelivery(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/item/ItemStack;Lcom/nobodiiiii/createbiotech/content/allay/entity/courier/AllayCourierEntity$Mission;Lcom/nobodiiiii/createbiotech/content/allay/logistics/courier/AllayCourierReturnMode;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Ljava/util/UUID;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/nobodiiiii/createbiotech/content/allay/logistics/courier/AllayCourierDeliveryService$DeliveryResult;"), @At(value = "INVOKE", target = "Lcom/yision/allay/logistics/courier/AllayCourierDeliveryService;finishDelivery(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/item/ItemStack;Lcom/yision/allay/entity/courier/AllayCourierEntity$Mission;Lcom/yision/allay/logistics/courier/AllayCourierReturnMode;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Ljava/util/UUID;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/yision/allay/logistics/courier/AllayCourierDeliveryService$DeliveryResult;")}, remap = false)
    private Object fmp$delivery(MinecraftServer server, ItemStack box, @Coerce Object mission, @Coerce Object mode, ResourceKey<Level> dimension,
            BlockPos port, UUID subLevel, UUID player, ServerLevel level, Vec3 position, Vec3 landing, Operation<Object> original) {
        return BiotechMaidFlight.targetsMaid(this) ? BiotechMaidFlight.deliver(this, server, level, landing)
                : original.call(server, box, mission, mode, dimension, port, subLevel, player, level, position, landing);
    }
    @Inject(method = "startCarrierReturn", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$return(MinecraftServer server, CallbackInfo ci) {
        if (BiotechCompatibility.ready() && BiotechMaidFlight.startReturn(this, server)) ci.cancel();
    }
}
