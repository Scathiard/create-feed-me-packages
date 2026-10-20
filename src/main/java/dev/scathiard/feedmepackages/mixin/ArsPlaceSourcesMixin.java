package dev.scathiard.feedmepackages.mixin;

import com.hollingsworth.arsnouveau.api.item.inv.*;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.scathiard.feedmepackages.compat.ars.ArsMaterials;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets="com.hollingsworth.arsnouveau.common.spell.effect.EffectPlaceBlock",remap=false)
public abstract class ArsPlaceSourcesMixin {
    @WrapMethod(method="place")
    private void fmp$placementScope(BlockHitResult hit,Level level,LivingEntity shooter,SpellStats stats,SpellContext context,SpellResolver resolver,Player proxy,Operation<Void> original) {
        try(var scope=ArsMaterials.open(shooter,context)) { original.call(hit,level,shooter,stats,context,resolver,proxy); }
    }
    @WrapOperation(method="place",at=@At(value="INVOKE",target="Lcom/hollingsworth/arsnouveau/api/item/inv/InventoryManager;extractItem(Ljava/util/function/Predicate;I)Lcom/hollingsworth/arsnouveau/api/item/inv/ExtractedStack;"))
    private ExtractedStack fmp$lastSource(InventoryManager manager,Predicate<ItemStack> predicate,int count,Operation<ExtractedStack> original) {
        return ArsMaterials.fallback(original.call(manager,predicate,count),predicate,count,false);
    }
    @WrapOperation(method="place",at=@At(value="INVOKE",target="Lcom/hollingsworth/arsnouveau/api/item/inv/InventoryManager;extractRandomItem(Ljava/util/function/Predicate;I)Lcom/hollingsworth/arsnouveau/api/item/inv/ExtractedStack;"))
    private ExtractedStack fmp$randomLastSource(InventoryManager manager,Predicate<ItemStack> predicate,int count,Operation<ExtractedStack> original) {
        return ArsMaterials.fallback(original.call(manager,predicate,count),predicate,count,true);
    }
    @Inject(method="attemptPlace",at=@At("HEAD"),cancellable=true)
    private static void fmp$recheck(Level level,ItemStack stack,BlockItem item,BlockHitResult hit,Player proxy,CallbackInfoReturnable<InteractionResult> ci) {
        if(!ArsMaterials.allowed(stack)) ci.setReturnValue(InteractionResult.FAIL);
    }
}
