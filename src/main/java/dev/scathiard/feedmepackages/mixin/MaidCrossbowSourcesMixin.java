package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.compat.maid.MaidMaterialSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.task.TaskCrossBowAttack", remap = false)
public abstract class MaidCrossbowSourcesMixin {
    @WrapMethod(method = "hasArrow", remap = false)
    private boolean fmp$hasArrow(@Coerce LivingEntity maid, Operation<Boolean> original) {
        if (original.call(maid)) return true;
        var weapon = maid.getMainHandItem();
        if (!(weapon.getItem() instanceof ProjectileWeaponItem projectile)) return false;
        var source = MaidMaterialSource.open(maid);
        return source != null && !source.candidates(projectile.getSupportedHeldProjectiles(weapon)).isEmpty();
    }
}
