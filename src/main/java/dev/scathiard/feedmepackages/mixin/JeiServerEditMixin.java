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

/** The legacy server transfer edits real slots directly, without menu.clicked. */
@Pseudo
@Mixin(targets = "mezz.jei.common.transfer.BasicRecipeTransferHandlerServer", remap = false)
public abstract class JeiServerEditMixin {
    @WrapMethod(method = "setItems", remap = false)
    private static void fmp$nativeEdit(Player player, List<?> operations, List<?> inputs, List<?> inventory,
                                       boolean maximum, boolean completeSets, Operation<Void> original) {
        if (!(player instanceof ServerPlayer server) || !CraftingService.supported(server.containerMenu)) {
            original.call(player, operations, inputs, inventory, maximum, completeSets); return;
        }
        try (var cursor = CursorReservations.beforeClick(server, server.containerMenu);
             var grid = CraftingReservations.beginGridEdit(server, server.containerMenu)) {
            original.call(player, operations, inputs, inventory, maximum, completeSets);
        }
    }
}
