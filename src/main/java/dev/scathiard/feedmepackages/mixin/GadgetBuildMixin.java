package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.scathiard.feedmepackages.compat.gadgets.GadgetMaterials;
import net.minecraft.core.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Pseudo
@Mixin(targets = "com.direwolf20.buildinggadgets2.common.events.ServerTickHandler", remap = false)
public abstract class GadgetBuildMixin {
    private static final String BUILD = "build(Lcom/direwolf20/buildinggadgets2/common/events/ServerBuildList;Lnet/minecraft/world/entity/player/Player;)V";
    private static final String EXCHANGE = "exchange(Lcom/direwolf20/buildinggadgets2/common/events/ServerBuildList;Lnet/minecraft/world/entity/player/Player;)V";
    private static final String REMOVE = "Lcom/direwolf20/buildinggadgets2/util/BuildingUtils;removeStacksFromInventory(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;ZLnet/minecraft/core/GlobalPos;Lnet/minecraft/core/Direction;)Z";
    @WrapOperation(method = {BUILD, EXCHANGE}, at = @At(value = "INVOKE", target = REMOVE), remap = false)
    private static boolean fmp$paid(Player player, List<ItemStack> needed, boolean simulate, GlobalPos bound, Direction face, Operation<Boolean> original,
                                    @Share("fmp$guard") LocalBooleanRef guard, @Share("fmp$failed") LocalBooleanRef failed) {
        if (simulate) guard.set(GadgetMaterials.guard(player));
        boolean result = original.call(player, needed, simulate, bound, face);
        if (!simulate && guard.get() && !result) failed.set(true);
        return result;
    }
    @WrapOperation(method = {BUILD, EXCHANGE}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), remap = false)
    private static boolean fmp$temporary(Level level, BlockPos pos, BlockState state, Operation<Boolean> original,
                                         @Share("fmp$guard") LocalBooleanRef guard, @Share("fmp$placement") LocalRef<GadgetMaterials.Placement> placement) {
        var before = guard.get() ? BlockSnapshot.create(level.dimension(), level, pos) : null;
        boolean result = original.call(level, pos, state);
        if (result && before != null) placement.set(new GadgetMaterials.Placement(before, level, state, level.getBlockEntity(pos)));
        return result;
    }
    @Inject(method = {BUILD, EXCHANGE}, at = @At(value = "INVOKE", target = REMOVE, ordinal = 1, shift = At.Shift.AFTER), cancellable = true, remap = false)
    private static void fmp$rejectUnpaid(@Coerce Object batch, Player player, CallbackInfo callback,
                                       @Share("fmp$failed") LocalBooleanRef failed, @Share("fmp$placement") LocalRef<GadgetMaterials.Placement> placement) {
        if (!failed.get()) return;
        if (placement.get() != null) placement.get().restoreIfOwned();
        callback.cancel();
    }
}
