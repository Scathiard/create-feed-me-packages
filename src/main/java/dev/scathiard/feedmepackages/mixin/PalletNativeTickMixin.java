package dev.scathiard.feedmepackages.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.PalletUse;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LivingEntity.class)
public abstract class PalletNativeTickMixin {
    @WrapMethod(method="updatingUsingItem")
    private void fmp$nativeDuration(Operation<Void> original) {
        if((Object)this instanceof Player player) PalletUse.nativeTick(player,()->original.call()); else original.call();
    }
}
