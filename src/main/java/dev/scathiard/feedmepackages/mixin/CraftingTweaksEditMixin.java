package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** All native bulk edits share one accounting boundary, including early return/exception paths. */
@Pseudo
@Mixin(targets = {
        "net.blay09.mods.craftingtweaks.network.RotateMessage",
        "net.blay09.mods.craftingtweaks.network.BalanceMessage",
        "net.blay09.mods.craftingtweaks.network.ClearMessage",
        "net.blay09.mods.craftingtweaks.network.TransferStackMessage",
        "net.blay09.mods.craftingtweaks.network.RefillLastCraftedMessage",
        "net.blay09.mods.craftingtweaks.network.CompressMessage",
        "net.blay09.mods.craftingtweaks.network.CraftStackMessage"
}, remap = false)
public abstract class CraftingTweaksEditMixin {
    @WrapMethod(method = "handle", remap = false)
    private static void fmp$nativeEdit(ServerPlayer player, @Coerce Object message, Operation<Void> original) {
        if (player == null) { original.call(null, message); return; }
        try (var cursor = CursorReservations.beforeClick(player, player.containerMenu);
             var grid = CraftingReservations.beginGridEdit(player, player.containerMenu)) {
            original.call(player, message);
        }
    }
}
