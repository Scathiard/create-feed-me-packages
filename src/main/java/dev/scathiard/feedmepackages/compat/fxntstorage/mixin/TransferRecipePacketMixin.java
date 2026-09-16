package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Create: Storage 1.3.x, SERVER side: their transfer packet resolves the worn backpack handler (and, as a
 * fallback, the one from an open backpack menu) and then takes from it and places the recipe inputs. We wrap
 * those handler locals so THEIR placement also finds our cache, on their own empty item slots - and so the
 * amount they actually took is debited from our cache when they write the remainder back.
 *
 * <p>Two stores, two ordinals: the worn backpack first, then the open menu's container.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.packet.TransferRecipePacket")
public abstract class TransferRecipePacketMixin {
    @ModifyVariable(method = "handle", at = @At("STORE"), ordinal = 0)
    private ItemStackHandler fmp$presentCacheToTheirWornBackpack(ItemStackHandler original) {
        return FxntCompat.present(original, "fxntstorage:TransferRecipePacket#handle(worn)");
    }
    @ModifyVariable(method = "handle", at = @At("STORE"), ordinal = 1)
    private ItemStackHandler fmp$presentCacheToTheirOpenMenu(ItemStackHandler original) {
        return FxntCompat.present(original, "fxntstorage:TransferRecipePacket#handle(menu)");
    }
}
