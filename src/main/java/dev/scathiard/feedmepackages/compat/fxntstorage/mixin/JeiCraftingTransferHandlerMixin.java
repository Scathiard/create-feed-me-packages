package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Create: Storage (1.3.x / 1.1.x) JEI crafting transfer, CLIENT side: their own preview asks their worn
 * backpack for sources; we wrap that ONE handler local so their own logic also sees our cache, on their own
 * empty item slots. Their code decides everything else - availability, the button, what is placed.
 *
 * <p>{@code @Pseudo} + a soft config: with the mod absent (or its shape changed) this mixin simply does not
 * apply, and the game behaves exactly as it does today.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler")
public abstract class JeiCraftingTransferHandlerMixin {
    @ModifyVariable(method = "transferRecipe", at = @At("STORE"), ordinal = 0)
    private ItemStackHandler fmp$presentCacheToTheirPreview(ItemStackHandler original) {
        return FxntCompat.present(original, "fxntstorage:JEICraftingTransferHandler#transferRecipe");
    }
}
