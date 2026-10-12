package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.compat.maid.MaidPickup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid", remap = false)
public abstract class MaidPickupMixin {
    @WrapMethod(method = "pickupItem", remap = false)
    private boolean fmp$afterNativePickup(ItemEntity ground, boolean simulate, Operation<Boolean> original) {
        var maid = (LivingEntity)(Object)this;
        var capture = MaidPickup.before(maid, ground, simulate);
        boolean result = original.call(ground, simulate);
        MaidPickup.after(maid, capture, result);
        return result;
    }
}
