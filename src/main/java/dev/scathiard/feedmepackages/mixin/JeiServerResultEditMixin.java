package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** New JEI packets and its legacy adapter share this one server boundary. */
@Pseudo
@Mixin(targets = "mezz.jei.common.transfer.BasicRecipeTransferHandlerServer", remap = false)
public abstract class JeiServerResultEditMixin {
    @WrapMethod(method = "setItemsWithResult", remap = false)
    private static boolean fmp$nativeEdit(Player player, List<?> operations, List<?> inputs, List<?> inventory,
                                          boolean maximum, boolean completeSets, Operation<Boolean> original) {
        if (!(player instanceof ServerPlayer server) || !CraftingService.supported(server.containerMenu))
            return original.call(player, operations, inputs, inventory, maximum, completeSets);
        try (var cursor = CursorReservations.beforeClick(server, server.containerMenu);
             var grid = CraftingReservations.beginGridEdit(server, server.containerMenu)) {
            return original.call(player, operations, inputs, inventory, maximum, completeSets);
        }
    }
}
