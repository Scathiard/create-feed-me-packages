package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.compat.tacz.TaczMaterials;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.EntityCapability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tacz.guns.api.item.gun.AbstractGunItem", remap = false)
public abstract class TaczGunSourcesMixin {
    @WrapOperation(method = {"canReload(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z", "hasInventoryAmmo(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Z)Z"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object fmp$ammo(LivingEntity actor, EntityCapability<?, ?> capability, Object context, Operation<Object> original,
                           @Local(argsOnly = true) ItemStack gun) {
        return TaczMaterials.append(actor, gun, original.call(actor, capability, context), 1);
    }
}
