package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierDispatchService", "com.yision.allay.logistics.courier.AllayCourierDispatchService"}, remap = false)
public abstract class BiotechAddressMixin {
    @Inject(method = {"resolvePackageTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Lcom/nobodiiiii/createbiotech/content/allay/logistics/courier/AllayCourierTarget;", "resolvePackageTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Lcom/yision/allay/logistics/courier/AllayCourierTarget;"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$recipient(ServerLevel level, ItemStack box, Vec3 origin, ResourceKey<Level> dimension, BlockPos port,
                                      Predicate<?> acceptedPort, CallbackInfoReturnable<Object> cir) {
        if (BiotechCompatibility.reservedAddress(box)) cir.setReturnValue(BiotechCompatibility.resolve(level, box, origin));
    }
}
