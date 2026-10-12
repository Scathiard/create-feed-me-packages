package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.emi.EmiCacheSources;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Pseudo
@Mixin(targets = "dev.emi.emi.network.FillRecipeC2SPacket", remap = false)
public abstract class EmiPacketSourcesMixin {
    @WrapMethod(method = "apply", remap = false)
    private void fmp$releaseOwnAliases(Player player, Operation<Void> original) {
        if (player instanceof ServerPlayer server) CraftingReservations.cancel(server, player.containerMenu);
        original.call(player);
    }
    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Ldev/emi/emi/network/FillRecipeC2SPacket;grabMatching(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Ljava/util/List;Ljava/util/List;Lnet/minecraft/world/item/ItemStack;)I"), remap = false)
    private int fmp$sources(Player player, List<Slot> sources, List<ItemStack> cleared, List<Slot> crafting, ItemStack wanted, Operation<Integer> original) {
        return original.call(player, EmiCacheSources.append(player, sources, wanted), cleared, crafting, wanted);
    }
}
