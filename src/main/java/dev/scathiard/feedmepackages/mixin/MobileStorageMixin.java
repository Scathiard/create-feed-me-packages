package dev.scathiard.feedmepackages.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Matches Mobile's registry-aware writer for every cargo, including legitimate unsealed returns. */
@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.robo.VirtualRobo", remap = false)
public abstract class MobileStorageMixin {
    @Inject(method = "setRemoved", at = @At("RETURN"), remap = false)
    private void fmp$forgetMaidReturn(ServerLevel level, CallbackInfo ci) {
        dev.scathiard.feedmepackages.compat.maid.MaidReturn.forget(
                dev.scathiard.feedmepackages.compat.maid.MaidReturn.roboId(this));
    }
    /**
     * The maid a bee must return to rides in the robo's <b>own</b> tag (user 2026-10-13, plan B): the parcel seal is
     * gone once the cargo is delivered. The runtime index is rebuilt from this tag and cleared on removal.
     */
    @Inject(method = "serializeNBT", at = @At("RETURN"), remap = false)
    private void fmp$writeMaidReturn(CallbackInfoReturnable<CompoundTag> cir) {
        dev.scathiard.feedmepackages.compat.maid.MaidReturn.writeReturn(this, cir.getReturnValue());
    }

    @Inject(method = "deserializeNBT", at = @At("RETURN"), remap = false)
    private static void fmp$readMaidReturn(ServerLevel level, CompoundTag roboTag, CallbackInfoReturnable<Object> cir) {
        dev.scathiard.feedmepackages.compat.maid.MaidReturn.readReturn(cir.getReturnValue(), roboTag);
    }

    @Redirect(method = "deserializeNBT", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"), remap = false)
    private static <T> DataResult<?> fmp$registryContext(Codec<?> codec, DynamicOps<T> ops, T input,
                                                        ServerLevel level, CompoundTag roboTag) {
        // The shape gate proves this is the one native ItemStack.CODEC read, not an arbitrary codec call.
        // Preserve the original codec/input and restore only the writer's registry context; no receipt is granted.
        return codec.parse(level.registryAccess().createSerializationContext(ops), input);
    }
}
