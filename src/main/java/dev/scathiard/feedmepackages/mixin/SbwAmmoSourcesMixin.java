package dev.scathiard.feedmepackages.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.compat.sbw.SbwMaterials;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = {"com.atsuishio.superbwarfare.data.gun.ammo_consumer_strategy.ItemAmmoStrategy", "com.atsuishio.superbwarfare.data.gun.ammo_consumer_strategy.PlayerAmmoStrategy"}, remap = false)
public abstract class SbwAmmoSourcesMixin {
    @WrapOperation(method = {
            "consume(Lcom/atsuishio/superbwarfare/data/gun/GunData;Lcom/atsuishio/superbwarfare/data/gun/AmmoConsumer;Lnet/minecraft/world/entity/Entity;I)I",
            "count(Lcom/atsuishio/superbwarfare/data/gun/GunData;Lcom/atsuishio/superbwarfare/data/gun/AmmoConsumer;Lnet/minecraft/world/entity/Entity;)I"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;)Ljava/lang/Object;"))
    private Object fmp$lastSource(Entity entity, EntityCapability<?, ?> capability, Operation<Object> original, @Local(argsOnly = true) GunData gun) {
        var nativeSource = (IItemHandler) original.call(entity, capability);
        return SbwMaterials.append(nativeSource, gun, entity);
    }
}
