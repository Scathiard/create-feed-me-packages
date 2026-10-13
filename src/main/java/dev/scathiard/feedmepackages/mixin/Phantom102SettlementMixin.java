package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import dev.scathiard.feedmepackages.compat.maid.phantom.v102.Phantom102Gate;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Phantom 1.0.2, settlement side (0.3.0 T2-b): when a courier's package target is one of
 * <b>our</b> maids, her own capacity rules decide - not the native player-inventory check, which can
 * never be satisfied by a maid.
 *
 * <p>Fail-closed by construction. {@link OptionalMixins#phantom102Compatible()} refuses to apply this
 * mixin at all when the target class shape differs, so a shape change silently disables the feature
 * instead of breaking the game. Inside the handler, <b>every</b> branch returns without cancelling
 * unless the complete maid condition holds, which keeps the native player path byte-for-byte unchanged:
 * <ol>
 *   <li>the read-only version/shape gate must be enabled ({@link Phantom102Gate#usable(boolean)});</li>
 *   <li>the target must be a native {@code AirCourierTarget$PlayerTarget} carrying a UUID;</li>
 *   <li>that UUID must resolve, on this server thread, to a maid wearing an active FMP pendant
 *       ({@link MaidAccess#find} - the same strict path used everywhere else);</li>
 *   <li>the native dimension rule must allow the delivery (section 15: we never hard-code
 *       "another dimension is unreachable");</li>
 *   <li>and she must actually be able to take the stack ({@link MaidCompatibility#canReceive}, read-only).</li>
 * </ol>
 *
 * <p>A maid who is ours but cannot take the package is deliberately <b>not</b> cancelled: the native
 * flow then treats the target as unavailable and waits 600 ticks before returning the package by its
 * original address, which is exactly what a player with a full inventory gets - and it avoids
 * {@code doFail} dropping the package.
 */
@Pseudo
@Mixin(targets = "com.yision.phantom.logistics.courier.AirCourierDispatchService", remap = false)
public abstract class Phantom102SettlementMixin {

    /** The only native target subtype we are allowed to reinterpret, and the dimension rule class. */
    private static final String PLAYER_TARGET = "com.yision.phantom.logistics.courier.AirCourierTarget$PlayerTarget";
    private static final String DIMENSION_RULES = "com.yision.phantom.logistics.courier.AirCourierDimensionRules";

    /**
     * Static, because {@code canReceivePackageTarget} is a static utility method on the target class -
     * Mixin rejects a non-static handler for a static target and the whole game fails to start
     * (found the hard way in the first {@code -PwithPhantom} GameTest run).
     */
    @Inject(method = "canReceivePackageTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$maidCanReceivePackage(ServerLevel level, @Coerce Object target, ItemStack stack,
                                                  CallbackInfoReturnable<Boolean> cir) {
        // Reaching this handler is itself the proof that the injection was applied, hence usable(true).
        if (!Phantom102Gate.usable(true)) return;
        if (!OptionalMixins.phantom102Compatible()) return;
        UUID maidId = fmp$playerTargetId(target);
        if (maidId == null) return;
        LivingEntity maid = MaidAccess.find(level.getServer(), MaidAccess.ADDRESS_PREFIX + maidId);
        if (maid == null) return;
        if (!fmp$dimensionAllowed(level, maid)) return;
        if (!MaidCompatibility.canReceive(maid, stack)) return;
        cir.setReturnValue(Boolean.TRUE);
    }

    /** The UUID inside a native player target, or null for anything else (including any failure). */
    private static UUID fmp$playerTargetId(Object target) {
        if (target == null || !target.getClass().getName().equals(PLAYER_TARGET)) return null;
        try {
            Method playerId = target.getClass().getMethod("playerId");
            return playerId.invoke(target) instanceof UUID id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The mod's own rule for "may this courier serve a target in that dimension" (section 15). */
    private static boolean fmp$dimensionAllowed(ServerLevel level, LivingEntity maid) {
        try {
            Class<?> rules = Class.forName(DIMENSION_RULES);
            Method canTarget = rules.getMethod("canTarget", ServerLevel.class, ResourceKey.class);
            return Boolean.TRUE.equals(canTarget.invoke(null, level, maid.level().dimension()));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
