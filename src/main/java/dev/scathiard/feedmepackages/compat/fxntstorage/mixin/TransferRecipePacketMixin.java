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
 * Create: Storage 1.3.x, SERVER side. Their placement runs per ingredient and their count helper runs on the
 * whole ingredient list, so each call records ITS OWN materials first (HEAD) and only cache cells those
 * materials accept are lent - the same rule the client uses (both go through CacheBorrow.plan).
 *
 * <p>LVT facts: {@code collectAndPlace(Ingredient,int,int,Inventory,IItemHandlerModifiable,List<Slot>)}
 * (parameter 5 {@code itemHandler}) and {@code getMaxCraftableItems(List,Inventory,IItemHandler)}
 * (parameter 3 {@code backpack}).
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
    private IItemHandlerModifiable fmp$presentCacheToTheirPlacement(IItemHandlerModifiable itemHandler) {
        CompatLog.once("hooked:collectAndPlace", "FMP compat: hooked collectAndPlace (IItemHandlerModifiable)");
        return FxntCompat.presentModifiable(itemHandler, "fxntstorage:TransferRecipePacket#collectAndPlace");
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
    private IItemHandler fmp$presentCacheToTheirCount(IItemHandler backpack) {
        CompatLog.once("hooked:getMaxCraftableItems", "FMP compat: hooked getMaxCraftableItems (IItemHandler)");
        return FxntCompat.presentSlots(backpack, "fxntstorage:TransferRecipePacket#getMaxCraftableItems");
    }

    @Inject(method = "getMaxCraftableItems", at = @At("RETURN"))
    private void fmp$clearIngredients(List<Ingredient> ingredients, Inventory inventory, IItemHandler backpack, CallbackInfoReturnable<Integer> info) {
        FxntContext.clear();
    }
}