package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility;
import dev.scathiard.feedmepackages.logistics.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.nobodiiiii.createbiotech.content.allay.logistics.courier.AllayCourierHelper", "com.yision.allay.logistics.courier.AllayCourierHelper"}, remap = false)
public abstract class BiotechReceiveMixin {
    @Inject(method = "canReceiveDelivery", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$capacity(ServerPlayer player, ItemStack box, CallbackInfoReturnable<Boolean> cir) {
        if (BiotechCompatibility.ready() && ReceiveService.canReceive(LogisticsEndpoint.player(player), box)) cir.setReturnValue(true);
    }
    @Inject(method = {"deliverPackage", "deliverPackageOnly"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$receive(ServerPlayer player, ItemStack box, CallbackInfoReturnable<Boolean> cir) {
        // Native helper hands off a copy; its caller retains the envelope until task settlement/HUD.
        if (BiotechCompatibility.ready() && ReceiveService.receive(player, box.copy())) cir.setReturnValue(true);
    }
}
