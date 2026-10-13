package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.lang.reflect.Constructor;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The native Create Phantom 1.0.2 <b>launch</b> side (0.3.0 T3). The native resolver only understands players and
 * ports ({@code AirCourierHelper.findTargetPlayer*}), so a package addressed to one of our maids resolves to
 * nothing and the courier never departs - measured at bytecode level, and the reason the user's factory/belt send
 * to a maid did not take off.
 *
 * <p>Here we build the native player target for her ourselves: her UUID in the native player slot. The native
 * resolver then accepts it, because its own acceptance check ({@code canReceivePackageTarget}) is already answered
 * for a maid by the settlement bridge - one identity, understood in exactly one place.
 *
 * <p>Reflective because Create Phantom is a runtime, never a compile, dependency, and fail-closed throughout: any
 * mismatch returns null, which leaves the native path to its own behaviour instead of launching a doomed flight.
 */
public final class MaidPhantomLaunch {
    private MaidPhantomLaunch() {}

    private static boolean attempted;
    private static Constructor<?> playerTarget;

    /** True once the native player-target record was resolved. Never throws. */
    public static synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                Class<?> target = Class.forName("com.yision.phantom.logistics.courier.AirCourierTarget$PlayerTarget");
                playerTarget = target.getDeclaredConstructor(UUID.class, ResourceKey.class);
                // Package-private record in another package: the constructor needs opening (learned the hard way).
                playerTarget.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                playerTarget = null;
            }
        }
        return playerTarget != null;
    }

    /**
     * The native player target for a package addressed to one of our maids, or null when it is not one of ours, she
     * is unreachable, she wears no active pendant, or she could not take the stack. Never throws.
     */
    public static Object nativeTargetFor(MinecraftServer server, ItemStack box) {
        if (server == null || box == null || box.isEmpty() || !available()) return null;
        try {
            String address = com.simibubi.create.content.logistics.box.PackageItem.getAddress(box);
            if (dev.scathiard.feedmepackages.compat.maid.MaidAccess.recipient(address) == null) return null;
            LivingEntity maid = dev.scathiard.feedmepackages.compat.maid.MaidAccess.find(server, address);
            if (maid == null) return null;
            if (!dev.scathiard.feedmepackages.compat.maid.MaidCompatibility.canReceive(maid, box)) return null;
            return playerTarget.newInstance(maid.getUUID(), maid.level().dimension());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notOurs) {
            return null;
        }
    }
}
