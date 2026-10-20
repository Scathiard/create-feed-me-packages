package dev.scathiard.feedmepackages.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.PalletUse;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(MultiPlayerGameMode.class)
public abstract class PalletNativeClientMixin {
    @WrapMethod(method="useItemOn")
    private InteractionResult fmp$predictMaterial(LocalPlayer player,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original) {
        if(!PalletUse.intercept(player,hand)) return original.call(player,hand,hit);
        return PalletUse.perform(player,material->original.call(player,hand,hit));
    }
    @WrapMethod(method="useItem")
    private InteractionResult fmp$predictUse(Player player,InteractionHand hand,Operation<InteractionResult> original) {
        if(!PalletUse.intercept(player,hand)) return original.call(player,hand);
        return PalletUse.perform(player,material->original.call(player,hand));
    }
}
