package dev.scathiard.feedmepackages.mixin;
import dev.scathiard.feedmepackages.consumption.PalletUse;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Player.class)
public abstract class PalletNativeHandMixin {
    @Inject(method="getItemBySlot",at=@At("HEAD"),cancellable=true)
    private void fmp$readLease(EquipmentSlot slot,CallbackInfoReturnable<ItemStack> ci) {
        var value=PalletUse.hand((Player)(Object)this,slot);if(value!=null) ci.setReturnValue(value);
    }
    @Inject(method="setItemSlot",at=@At("HEAD"),cancellable=true)
    private void fmp$returnToLease(EquipmentSlot slot,ItemStack stack,CallbackInfo ci) {
        if(PalletUse.hand((Player)(Object)this,slot,stack)) ci.cancel();
    }
}
