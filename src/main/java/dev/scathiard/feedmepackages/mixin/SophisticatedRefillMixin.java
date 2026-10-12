package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedMaterials;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeWrapper", remap = false)
public abstract class SophisticatedRefillMixin {
    @Inject(method = "extractFromStorageOrPlayer", at = @At("RETURN"), cancellable = true, remap = false)
    private void fmp$lastSource(Player player, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && SophisticatedMaterials.refill(this, player, stack)) cir.setReturnValue(true);
    }
}
