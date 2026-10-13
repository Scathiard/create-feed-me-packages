package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The native 1.0.2 flight geometry applied to a moving target that is not a player (0.3.0 T4).
 *
 * <p>Everything here is reflective: Create Phantom is a runtime dependency of this project, never a compile one,
 * so importing its classes is not an option. Every lookup fails closed - if the flight profile or the native
 * target record cannot be resolved, the caller must leave the task to the native behaviour rather than invent a
 * flight plan. Nothing is faked: the only unusual thing about the target we build is that its native player
 * field is null, which is exactly what the native code produces for a port target.
 */
public final class MaidPhantomFlight {
    private MaidPhantomFlight() {}

    private static boolean attempted;
    private static Object profile;
    private static Method targetHeight;
    private static Method forwardOffset;
    private static Method cruiseHeight;
    private static Method completionDistance;
    private static Constructor<?> flightTarget;

    /** True once the native profile and target record were resolved. Never throws. */
    public static synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                String root = "com.yision.phantom.logistics.courier.";
                Class<?> profileType = Class.forName(root + "flight.AirCourierFlightProfile");
                profile = profileType.getField("DEFAULT").get(null);
                targetHeight = profileType.getMethod("playerTargetHeight");
                forwardOffset = profileType.getMethod("playerForwardOffset");
                cruiseHeight = profileType.getMethod("playerCruiseHeight");
                completionDistance = profileType.getMethod("playerCompletionDistance");
                Class<?> targetType = Class.forName(root + "AirCourierTask$FlightTarget");
                flightTarget = targetType.getDeclaredConstructor(ServerLevel.class, ServerPlayer.class, Vec3.class, Vec3.class, double.class);
                flightTarget.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                profile = null;
                flightTarget = null;
            }
        }
        return profile != null && flightTarget != null;
    }

    /** The native landing point for a moving target: her feet, the native height, and the native forward offset. */
    public static Vec3 landing(LivingEntity target) {
        if (!available()) return null;
        try {
            Vec3 look = target.getLookAngle().multiply(1, 0, 1);
            if (look.lengthSqr() > 1.0E-6) look = look.normalize();
            else {
                double yaw = Math.toRadians(target.yBodyRot);
                look = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            }
            return target.position()
                    .add(0, (double) targetHeight.invoke(profile), 0)
                    .add(look.scale((double) forwardOffset.invoke(profile)));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** A native {@code FlightTarget} for her current position, or null when it cannot be built. */
    public static Object flightTarget(LivingEntity target) {
        if (!available()) return null;
        try {
            Vec3 landing = landing(target);
            if (landing == null) return null;
            Vec3 cruise = landing.add(0, (double) cruiseHeight.invoke(profile) - (double) targetHeight.invoke(profile), 0);
            return flightTarget.newInstance((ServerLevel) target.level(), null, cruise, landing,
                    (double) completionDistance.invoke(profile));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Arrival: her own bounding box, or the native completion distance from the current position. */
    public static boolean reached(LivingEntity target, Vec3 position) {
        if (position == null) return false;
        Vec3 landing = landing(target);
        if (landing == null) return false;
        return target.getBoundingBox().inflate(.45, .6, .45).contains(position) || position.distanceTo(landing) <= 1.5;
    }
}
