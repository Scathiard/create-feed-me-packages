package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.scathiard.feedmepackages.compat.maid.MaidInventorySource;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.task.TaskBowAttack", remap = false)
public abstract class MaidBowSourcesMixin {
    @Unique private static final ThreadLocal<CombinedInvWrapper> fmp$inventory = new ThreadLocal<>();
    @Unique private static final ThreadLocal<LivingEntity> fmp$actor = new ThreadLocal<>();
    @WrapMethod(method = "getArrow", remap = false)
    private AbstractArrow fmp$arrow(@Coerce LivingEntity maid, float distance, Operation<AbstractArrow> original) {
        var previous = fmp$inventory.get(); var actor = fmp$actor.get();
        fmp$inventory.remove(); fmp$actor.set(maid);
        try {
            var result = original.call(maid, distance);
            if (fmp$inventory.get() instanceof MaidInventorySource source) source.validateFreeArrow();
            return result;
        } catch (AmmoService.Unavailable changed) { return null; }
        finally {
            if (previous == null) fmp$inventory.remove(); else fmp$inventory.set(previous);
            if (actor == null) fmp$actor.remove(); else fmp$actor.set(actor);
        }
    }
    @WrapOperation(method = {"getArrow", "findArrow"}, at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;getAvailableInv(Z)Lnet/neoforged/neoforge/items/wrapper/CombinedInvWrapper;"), remap = false)
    private CombinedInvWrapper fmp$source(@Coerce LivingEntity maid, boolean handsFirst, Operation<CombinedInvWrapper> original) {
        if (fmp$actor.get() == maid && fmp$inventory.get() != null) return fmp$inventory.get();
        var source = MaidInventorySource.bow(maid, original.call(maid, handsFirst));
        if (fmp$actor.get() == maid) fmp$inventory.set(source);
        return source;
    }
    @WrapOperation(method = "findArrow", at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/util/ItemsUtil;findStackSlot(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;)I"), remap = false)
    private int fmp$nativeProviders(IItemHandler inv, Predicate<ItemStack> accepts, Operation<Integer> original) {
        return MaidInventorySource.find(inv, accepts, (source, filter) -> original.call(source, filter));
    }
}
