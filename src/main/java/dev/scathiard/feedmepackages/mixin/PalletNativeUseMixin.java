package dev.scathiard.feedmepackages.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.PalletUse;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ServerPlayerGameMode.class)
public abstract class PalletNativeUseMixin {
    @WrapMethod(method="useItemOn")
    private InteractionResult fmp$materialOn(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original) {
        if(!PalletUse.intercept(player,hand)) return original.call(player,level,stack,hand,hit);
        return PalletUse.perform(player,material->original.call(player,level,material,hand,hit));
    }
    @WrapMethod(method="useItem")
    private InteractionResult fmp$materialAir(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,Operation<InteractionResult> original) {
        if(!PalletUse.intercept(player,hand)) return original.call(player,level,stack,hand);
        return PalletUse.perform(player,material->original.call(player,level,material,hand));
    }
}
