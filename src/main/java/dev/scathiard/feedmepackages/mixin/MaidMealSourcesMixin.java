package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.scathiard.feedmepackages.compat.maid.MaidInventorySource;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = {"com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask",
        "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidHealSelfTask"}, remap = false)
public abstract class MaidMealSourcesMixin {
    @WrapMethod(method = "start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V", remap = false)
    private void fmp$meal(ServerLevel level, @Coerce LivingEntity maid, long time, Operation<Void> original) {
        try { original.call(level, maid, time); }
        catch (AmmoService.Unavailable changed) { /* No real food was extracted; leave the original hands alone. */ }
    }
    @WrapOperation(method = "start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;getAvailableBackpackInv()Lnet/neoforged/neoforge/items/wrapper/CombinedInvWrapper;"), remap = false)
    private CombinedInvWrapper fmp$source(@Coerce LivingEntity maid, Operation<CombinedInvWrapper> original) {
        return MaidInventorySource.meal(maid, original.call(maid));
    }
    @WrapOperation(method = "start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/util/ItemsUtil;findStackSlot(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;)I"), remap = false)
    private int fmp$nativeProviders(IItemHandler inv, Predicate<ItemStack> accepts, Operation<Integer> original) {
        return MaidInventorySource.find(inv, accepts, (source, filter) -> original.call(source, filter));
    }
}
