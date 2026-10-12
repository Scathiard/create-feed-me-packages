package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.client.ClientTransferOrigin;
import dev.scathiard.feedmepackages.network.TransferOrigin;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = {"net.fxnt.fxntstorage.compat.emi.EMIInventoryRecipeHandler", "net.fxnt.fxntstorage.compat.emi.EMICraftingRecipeHandler",
        "net.fxnt.fxntstorage.compat.emi.EMIStonecuttingRecipeHandler", "net.fxnt.fxntstorage.compat.emi.EMIBackpackCraftingRecipeHandler"}, remap = false)
public abstract class FxntEmiOriginMixin {
    @WrapOperation(method = "craft", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"), remap = false)
    private void fmp$origin(CustomPacketPayload packet, CustomPacketPayload[] rest, Operation<Void> original) {
        ClientTransferOrigin.send(TransferOrigin.Viewer.EMI, () -> original.call(packet, rest));
    }
}
