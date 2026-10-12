package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.compat.tacz.TaczMaterials;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.EntityCapability;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunScriptAPI", remap = false)
public abstract class TaczScriptSourcesMixin {
    @Shadow private ItemStack itemStack;
    @WrapOperation(method = "hasAmmoToConsume()Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object fmp$query(LivingEntity actor, EntityCapability<?, ?> capability, Object context, Operation<Object> original) {
        return TaczMaterials.append(actor, itemStack, original.call(actor, capability, context), 1);
    }
    @WrapOperation(method = "consumeAmmoFromPlayer(I)I", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object fmp$consume(LivingEntity actor, EntityCapability<?, ?> capability, Object context, Operation<Object> original,
                              @Local(argsOnly = true) int amount) {
        return TaczMaterials.append(actor, itemStack, original.call(actor, capability, context), amount);
    }
}
