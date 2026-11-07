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

/** The unofficial 1.1.8 port counts reserve ammo inside its HUD. */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.gui.overlay.GunHudOverlay", remap = false)
public abstract class TaczLegacyHudSourcesMixin {
    @WrapOperation(method = "handleCacheCount(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/api/item/IGun;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), remap = false)
    private static Inventory fmp$sources(LocalPlayer player, Operation<Inventory> original, @Local(argsOnly = true) ItemStack gun) {
        return TaczClientSources.hudSources(original.call(player), gun);
    }
}
