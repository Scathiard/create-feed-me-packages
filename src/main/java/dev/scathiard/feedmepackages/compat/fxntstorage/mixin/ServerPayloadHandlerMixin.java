package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Create: Storage 1.1.x, SERVER side: there the transfer packet body lives in the payload handler class
 * ({@code ServerPayloadHandler.handleTransferRecipePacket} and its lambda). {@code method = "*"} keeps us
 * independent of which method the compiler put the body in - only that class's ItemStackHandler locals are
 * touched, and each one is wrapped for the duration of that call.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.handler.ServerPayloadHandler")
public abstract class ServerPayloadHandlerMixin {
    @ModifyVariable(method = "*", at = @At("STORE"), ordinal = 0)
    private ItemStackHandler fmp$presentCacheToTheirServerTransfer(ItemStackHandler original) {
        return FxntCompat.present(original, "fxntstorage:ServerPayloadHandler#transfer");
    }
}
