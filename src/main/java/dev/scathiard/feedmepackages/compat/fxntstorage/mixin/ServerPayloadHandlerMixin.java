package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Create: Storage 1.1.x, SERVER side: there the transfer body lives in the payload handler class
 * ({@code ServerPayloadHandler.handleTransferRecipePacket} enqueues the work, the body is a lambda). LVT
 * ({@code javap -l}) gives both points:
 * <ul>
 *   <li>{@code lambda$handleTransferRecipePacket$9(IPayloadContext, TransferRecipePacket)} - local slot 8,
 *       name {@code itemHandler}, type {@code IItemHandlerModifiable}: this is the handler their inline
 *       placement reads and writes back. Targeted by the local's TYPE with {@code method = "*"} so the
 *       compiler-generated lambda name does not matter;</li>
 *   <li>{@code getMaxCraftableItems(List, Inventory, IItemHandler)} - LVT: parameter slot 3, name
 *       {@code backpack}, type {@code IItemHandler} (same shape as in 1.3.x).</li>
 * </ul>
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.handler.ServerPayloadHandler")
public abstract class ServerPayloadHandlerMixin {
    @ModifyVariable(method = "*", at = @At("STORE"), ordinal = 0)
    private IItemHandlerModifiable fmp$presentCacheToTheirPlacement(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:ServerPayloadHandler#itemHandler", "FMP compat: hooked ServerPayloadHandler transfer (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:ServerPayloadHandler#transfer");
    }

    @ModifyVariable(method = "getMaxCraftableItems", at = @At("HEAD"), argsOnly = true)   // the only IItemHandler argument
    private IItemHandler fmp$presentCacheToTheirCount(IItemHandler backpack) {
        CompatLog.once("hooked:getMaxCraftableItems", "FMP compat: hooked getMaxCraftableItems (IItemHandler)");
        return FxntCompat.presentSlots(backpack, "fxntstorage:ServerPayloadHandler#getMaxCraftableItems");
    }
}
