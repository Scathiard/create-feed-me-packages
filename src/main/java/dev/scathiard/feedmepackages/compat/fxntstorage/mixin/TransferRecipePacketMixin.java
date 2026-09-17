package dev.scathiard.feedmepackages.compat.fxntstorage.mixin;

import dev.scathiard.feedmepackages.compat.fxntstorage.CompatLog;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntCompat;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntContext;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * F-6, SERVER side: the two methods that do the real work already receive the player's vanilla {@link Inventory}
 * as an argument, so that argument is the whole seam - the owner comes from it, nothing is matched, and the
 * third-party layout reflection is gone. Each call records its own materials first (HEAD) and only cache cells
 * those materials accept are lent (both sides go through CacheBorrow.plan).
 */
@Pseudo
@Mixin(remap = false, targets = "net.fxnt.fxntstorage.network.packet.TransferRecipePacket")
public abstract class TransferRecipePacketMixin {
    @Inject(method = "collectAndPlace", at = @At("HEAD"))
    private void fmp$recordIngredient(Ingredient ingredient, int placed, int index, Inventory inventory,
                                      IItemHandlerModifiable handler, List<Slot> slots, CallbackInfoReturnable<Boolean> info) {
        FxntContext.materials(ingredient == null ? List.of() : List.of(ingredient));
    }

    @ModifyVariable(method = "collectAndPlace", at = @At("HEAD"), argsOnly = true)
    private Inventory fmp$presentCacheToTheirPlacement(Inventory inventory) {
        CompatLog.once("hooked:collectAndPlace", "FMP compat: hooked collectAndPlace (Inventory)");
        return FxntCompat.presentInventory(inventory, "fxntstorage:TransferRecipePacket#collectAndPlace");
    }

    @Inject(method = "collectAndPlace", at = @At("RETURN"))
    private void fmp$clearIngredient(Ingredient ingredient, int placed, int index, Inventory inventory,
                                     IItemHandlerModifiable handler, List<Slot> slots, CallbackInfoReturnable<Boolean> info) {
        FxntContext.clear();
    }

    @Inject(method = "getMaxCraftableItems", at = @At("HEAD"))
    private void fmp$recordIngredients(List<Ingredient> ingredients, Inventory inventory, IItemHandler backpack, CallbackInfoReturnable<Integer> info) {
        FxntContext.materials(ingredients == null ? List.of() : ingredients);
    }

    @ModifyVariable(method = "getMaxCraftableItems", at = @At("HEAD"), argsOnly = true)
    private Inventory fmp$presentCacheToTheirCount(Inventory inventory) {
        CompatLog.once("hooked:getMaxCraftableItems", "FMP compat: hooked getMaxCraftableItems (Inventory)");
        return FxntCompat.presentInventory(inventory, "fxntstorage:TransferRecipePacket#getMaxCraftableItems");
    }

    @Inject(method = "getMaxCraftableItems", at = @At("RETURN"))
    private void fmp$clearIngredients(List<Ingredient> ingredients, Inventory inventory, IItemHandler backpack, CallbackInfoReturnable<Integer> info) {
        FxntContext.clear();
    }
}