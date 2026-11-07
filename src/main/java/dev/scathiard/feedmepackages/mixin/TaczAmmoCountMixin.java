package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.compat.tacz.TaczClientSources;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Official 1.2 shares this reader between its HUD and inventory-fed shot scheduling. */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.gameplay.ClientAmmoCount", remap = false)
public abstract class TaczAmmoCountMixin {
    @WrapOperation(method = "get(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/api/item/IGun;)I", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), remap = false)
    private static Inventory fmp$sources(LocalPlayer player, Operation<Inventory> original, @Local(argsOnly = true) ItemStack gun) {
        return TaczClientSources.hudSources(original.call(player), gun);
    }
}
