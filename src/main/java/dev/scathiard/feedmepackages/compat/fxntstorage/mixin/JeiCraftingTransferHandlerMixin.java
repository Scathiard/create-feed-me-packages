package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Create: Storage's JEI crafting transfer, CLIENT side: their own preview asks their worn backpack for
 * sources; we wrap that ONE local so their own logic also sees our cache on their own empty item slots.
 *
 * <p>Injection point proven by LVT ({@code javap -l}, 1.3.4): {@code JEICraftingTransferHandler.transferRecipe}
 * has local slot 11, name {@code itemHandler}, type
 * {@code Lnet/neoforged/neoforge/items/IItemHandlerModifiable;} - the local is WIDENED to the interface after
 * {@code IBackpackContainer.getItemHandler()}, which is why a handler declared as {@code ItemStackHandler}
 * silently never matched.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler")
public abstract class JeiCraftingTransferHandlerMixin {
    @ModifyVariable(method = "transferRecipe", at = @At("STORE"), ordinal = 0)
    private IItemHandlerModifiable fmp$presentCacheToTheirPreview(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:transferRecipe", "FMP compat: hooked transferRecipe (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:JEICraftingTransferHandler#transferRecipe");
    }
}
