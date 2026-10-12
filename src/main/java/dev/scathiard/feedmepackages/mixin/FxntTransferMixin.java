package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.fxntstorage.*;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Pseudo
@Mixin(targets = "net.fxnt.fxntstorage.network.packet.TransferRecipePacket", remap = false)
public abstract class FxntTransferMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/handling/IPayloadContext;enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"), remap = false)
    private java.util.concurrent.CompletableFuture<Void> fmp$captureOrigin(IPayloadContext context, Runnable work,
                                                                          Operation<java.util.concurrent.CompletableFuture<Void>> original) {
        var viewer = context.player() instanceof ServerPlayer player ? dev.scathiard.feedmepackages.network.TransferOrigin.claim(player)
                : dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN;
        return original.call(context, (Runnable) () -> {
            try (var scope = new FxntTransferContext(List.of(), null, false, viewer)) { work.run(); }
        });
    }
    @WrapMethod(method = "getMaxCraftableItems", remap = false)
    private int fmp$count(List<Ingredient> ingredients, Inventory inventory, IItemHandler backpack, Operation<Integer> original) {
        return original.call(ingredients, FxntMaterials.countView(inventory, ingredients), backpack);
    }
    @WrapMethod(method = "collectAndPlace", remap = false)
    private boolean fmp$provide(Ingredient ingredient, int slot, int amount, Inventory inventory, IItemHandlerModifiable backpack,
                                List<Slot> destinations, Operation<Boolean> original) {
        try (var transfer = FxntMaterials.prepare(ingredient, slot, amount, inventory, backpack, destinations);
             var context = new FxntTransferContext(List.of(ingredient), transfer)) {
            return original.call(ingredient, slot, amount, transfer == null ? inventory : transfer.inventory(), backpack, destinations);
        }
    }
    @WrapOperation(method = "collectAndPlace", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;"), remap = false)
    private ItemStack fmp$observeRead(IItemHandlerModifiable source, int slot, Operation<ItemStack> original) {
        var stack = original.call(source, slot);
        var context = FxntTransferContext.current();
        if (context != null && context.transfer != null) context.transfer.read(source, slot, stack);
        return stack;
    }
    @WrapOperation(method = "collectAndPlace", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V"), remap = false)
    private void fmp$observeWrite(IItemHandlerModifiable source, int slot, ItemStack stack, Operation<Void> original) {
        var intendedRemainder = stack.copy();
        try { original.call(source, slot, stack); }
        finally {
            var context = FxntTransferContext.current();
            if (context != null && context.transfer != null) context.transfer.wrote(source, slot, intendedRemainder);
        }
    }
    @WrapOperation(method = "collectAndPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;setByPlayer(Lnet/minecraft/world/item/ItemStack;)V"), remap = false)
    private void fmp$commitSource(Slot slot, ItemStack stack, Operation<Void> original) {
        var context = FxntTransferContext.current();
        if (context == null || context.transfer == null) original.call(slot, stack);
        else context.transfer.place(slot, stack, value -> original.call(slot, value));
    }
    @WrapMethod(method = "lambda$handle$1", remap = false)
    private void fmp$releaseOwnPreview(IPayloadContext context, Operation<Void> original) {
        if (context.player() instanceof ServerPlayer player) CraftingReservations.cancel(player, player.containerMenu);
        try (var scope = new FxntTransferContext(List.of(), null, true)) { original.call(context); }
    }
    @WrapOperation(method = "lambda$handle$1", at = @At(value = "INVOKE", target = "Lnet/fxnt/fxntstorage/backpack/inventory/IBackpackContainer;setDataChanged()V"), remap = false)
    private void fmp$observePublication(@Coerce Object container, Operation<Void> original) {
        original.call(container);
        FxntTransferContext.nativePublished();
    }
}
