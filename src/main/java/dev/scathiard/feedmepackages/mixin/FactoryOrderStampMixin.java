package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.FactoryLogistics;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "ru.zznty.create_factory_abstractions.generic.support.GenericOrder", remap = false)
public abstract class FactoryOrderStampMixin {
    @Inject(method = "set", at = @At("TAIL"))
    private static void fmp$seal(HolderLookup.Provider registries, ItemStack box, int orderId, int linkIndex,
            boolean finalLink, int index, boolean finalPackage, @Coerce Object context, CallbackInfo ci) {
        FactoryLogistics.stamp(box);
    }
}
