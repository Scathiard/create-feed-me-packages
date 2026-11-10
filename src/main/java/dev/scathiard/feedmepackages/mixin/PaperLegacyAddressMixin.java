package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Earlier couriers register bare player names. Preserve exact native addresses, then accept @player aliases. */
@Pseudo
@Mixin(targets = "com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager", remap = false)
public abstract class PaperLegacyAddressMixin {
    @Unique private static Method fmp$entity;
    @Coerce
    @WrapOperation(method = "addPlane(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFLnet/minecraft/world/item/ItemStack;Z)Z",
            at = @At(value = "INVOKE", target = "Lcom/kreidev/cmpackagecouriers/CourierTarget;getActiveTarget(Ljava/lang/String;)Lcom/kreidev/cmpackagecouriers/CourierTarget;"), remap = false)
    private static Object fmp$playerAlias(String address, Operation<Object> original) {
        var exact = original.call(address);
        if (exact != null || !address.startsWith("@")) return exact;
        var alias = original.call(address.substring(1));
        if (alias == null) return null;
        try {
            if (fmp$entity == null) fmp$entity = alias.getClass().getMethod("getEntity");
            return fmp$entity.invoke(alias) instanceof Player player
                    && player.getGameProfile().getName().equals(address.substring(1)) ? alias : null;
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("Cannot resolve the native courier player target", unavailable);
        }
    }
}
