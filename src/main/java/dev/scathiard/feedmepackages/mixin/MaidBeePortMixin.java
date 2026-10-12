package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidCouriers;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.blocks.bee_port.BeePortBlockEntity", remap = false)
public abstract class MaidBeePortMixin {
    @Inject(method = "sendItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void fmp$sendToMaid(ItemStack box, int slot, CallbackInfo ci) {
        if (MaidCouriers.beeSend(this, box, slot)) ci.cancel();
    }
}
