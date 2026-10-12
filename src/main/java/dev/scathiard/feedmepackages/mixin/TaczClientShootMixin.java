package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.tacz.TaczClientSources;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerShoot", remap = false)
public abstract class TaczClientShootMixin {
    @WrapOperation(method = "doShoot(Lcom/tacz/guns/client/resource/GunDisplayInstance;Lcom/tacz/guns/api/item/IGun;Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/resource/pojo/data/gun/GunData;JF)V", at = @At(value = "INVOKE", target = "Lcom/tacz/guns/api/item/IGun;getCurrentAmmoCount(Lnet/minecraft/world/item/ItemStack;)I"), remap = false)
    private int fmp$available(@Coerce Object nativeGun, ItemStack stack, Operation<Integer> original) {
        return TaczClientSources.firingCount(stack, original.call(nativeGun, stack));
    }
}
