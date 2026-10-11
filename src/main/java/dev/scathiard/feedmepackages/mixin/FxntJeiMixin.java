package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.compat.fxntstorage.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = {"net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler",
        "net.fxnt.fxntstorage.compat.jei.JEIBackpackCraftingTransferHandler",
        "net.fxnt.fxntstorage.compat.jei.JEIStonecuttingTransferHandler"}, remap = false)
public abstract class FxntJeiMixin {
    @WrapOperation(method = "transferRecipe", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"), remap = false)
    private void fmp$origin(net.minecraft.network.protocol.common.custom.CustomPacketPayload packet,
                            net.minecraft.network.protocol.common.custom.CustomPacketPayload[] rest, Operation<Void> original) {
        dev.scathiard.feedmepackages.client.ClientTransferOrigin.send(dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.JEI,
                () -> original.call(packet, rest));
    }
    @WrapOperation(method = "transferRecipe", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), remap = false)
    private Inventory fmp$readSources(Player player, Operation<Inventory> original, @Local(argsOnly = true) RecipeHolder<?> recipe) {
        var inventory = original.call(player);
        return !ClientCompatibility.fxntSourcesAvailable() || !FmpConfig.jeiCraftingEnabled() || !ClientMaterials.active() ? inventory
                : FxntMaterials.preview(inventory, ClientMaterials.craftingStacks(), recipe.value().getIngredients());
    }
}
