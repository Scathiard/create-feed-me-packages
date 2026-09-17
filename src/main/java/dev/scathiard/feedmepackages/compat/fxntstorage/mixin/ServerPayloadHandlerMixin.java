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
 * Create: Storage 1.1.x, SERVER side: there the transfer body is a compiler-generated lambda, so the handler
 * local is targeted by TYPE with method = "*" (LVT: local slot 8 {@code itemHandler IItemHandlerModifiable}),
 * and the count helper takes the whole ingredient list as its third argument (LVT: parameter 3
 * {@code backpack IItemHandler}).
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.handler.ServerPayloadHandler")
public abstract class ServerPayloadHandlerMixin {
    @ModifyVariable(method = "*", at = @At("STORE"), ordinal = 0)
    private IItemHandlerModifiable fmp$presentCacheToTheirPlacement(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:ServerPayloadHandler#itemHandler", "FMP compat: hooked ServerPayloadHandler transfer (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:ServerPayloadHandler#transfer");
    }

    @ModifyVariable(method = "getMaxCraftableItems", at = @At("HEAD"), argsOnly = true)
    private IItemHandler fmp$presentCacheToTheirCount(IItemHandler backpack) {
        CompatLog.once("hooked:getMaxCraftableItems", "FMP compat: hooked getMaxCraftableItems (IItemHandler)");
        return FxntCompat.presentSlots(backpack, "fxntstorage:ServerPayloadHandler#getMaxCraftableItems");
    }
}