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
 * Create: Storage 1.3.x, SERVER side. Their transfer takes the worn backpack handler and then
 * <em>passes it as an argument</em> to the two methods that do the real work. Those arguments are the stable,
 * LVT-proven injection points:
 * <ul>
 *   <li>{@code collectAndPlace(Ingredient, int, int, Inventory, IItemHandlerModifiable, List<Slot>)} -
 *       LVT: parameter slot 5, name {@code itemHandler}, type {@code IItemHandlerModifiable}; it reads with
 *       {@code getStackInSlot} and writes the remainder back with {@code setStackInSlot};</li>
 *   <li>{@code getMaxCraftableItems(List, Inventory, IItemHandler)} - LVT: parameter slot 3, name
 *       {@code backpack}, type {@code IItemHandler} (read-only; decides the shift/max amount).</li>
 * </ul>
 * The former {@code handle}-local injections are gone: those local types were never LVT-verified and never
 * matched, which is why nothing was ever exposed.
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.packet.TransferRecipePacket")
public abstract class TransferRecipePacketMixin {
    @ModifyVariable(method = "collectAndPlace", at = @At("HEAD"), argsOnly = true)   // the only IItemHandlerModifiable argument
    private IItemHandlerModifiable fmp$presentCacheToTheirPlacement(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:collectAndPlace", "FMP compat: hooked collectAndPlace (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:TransferRecipePacket#collectAndPlace");
    }

    @ModifyVariable(method = "getMaxCraftableItems", at = @At("HEAD"), argsOnly = true)   // the only IItemHandler argument
    private IItemHandler fmp$presentCacheToTheirCount(IItemHandler backpack) {
        CompatLog.once("hooked:getMaxCraftableItems", "FMP compat: hooked getMaxCraftableItems (IItemHandler)");
        return FxntCompat.presentSlots(backpack, "fxntstorage:TransferRecipePacket#getMaxCraftableItems");
    }
}
