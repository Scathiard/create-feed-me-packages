package dev.scathiard.feedmepackages.compat.maid;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where a dispatched transport bee has to fly back to: the maid who sent it (user 2026-10-13, plan B - "every
 * behaviour looks at the player target"). The delivery leg consumes the parcel seal, so once the cargo is empty
 * nothing in the parcel says who sent it; the return point is therefore recorded when the bee is created.
 *
 * <p>The record lives in the robo's <b>own</b> saved data (see {@code MobileStorageMixin}), so it travels with the
 * entity and survives a restart. The runtime index is cleared on native removal and server stop.
 * Unreachable targets use native fallback; a full recipient retires this override instead of looping.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = dev.scathiard.feedmepackages.FeedMePackages.MOD_ID)
public final class MaidReturn {
    private MaidReturn() {}
    /** FMP-owned key inside the robo tag; the transport mod never reads it. */
    public static final String TAG = "fmp_maid_return";
    private static final Map<UUID, UUID> RETURN = new HashMap<>();

    public static void register(UUID robo, UUID maid) { if (robo != null && maid != null) RETURN.put(robo, maid); }
    public static void forget(UUID robo) { if (robo != null) RETURN.remove(robo); }
    public static UUID maid(UUID robo) { return robo == null ? null : RETURN.get(robo); }
    static int size() { return RETURN.size(); }

    @net.neoforged.bus.api.SubscribeEvent
    public static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) { RETURN.clear(); }

    /**
     * Reachability of the dispatching maid, deliberately identical to what a player target needs: alive, present in
     * the level the bee is in and its chunk loaded. Death, a recall (soul charm) or a dimension change therefore
     * make her unreachable without any special case - the target just fails and the native chain takes over.
     */
    public static boolean reachable(LivingEntity maid, ServerLevel level) {
        return maid != null && level != null && MaidCompatibility.isMaid(maid) && maid.isAlive() && !maid.isRemoved()
                && maid.level() == level && level.getEntity(maid.getUUID()) == maid
                && level.isLoaded(maid.blockPosition());
    }

    /** The maid this bee must return to, if she is reachable right now. */
    public static LivingEntity find(ServerLevel level, UUID maid) {
        if (maid == null || level == null) return null;
        return level.getEntity(maid) instanceof LivingEntity found && reachable(found, level) ? found : null;
    }

    private static java.lang.reflect.Method getId;
    /** The robo class is not on the compile classpath, so its id is read reflectively. */
    public static UUID roboId(Object robo) {
        if (robo == null) return null;
        try {
            if (getId == null) getId = robo.getClass().getMethod("getId");
            return (UUID) getId.invoke(robo);
        } catch (ReflectiveOperationException | RuntimeException unsupported) { return null; }
    }
    /** Called from {@code MobileStorageMixin} when the robo writes its own tag. */
    public static void writeReturn(Object robo, net.minecraft.nbt.CompoundTag tag) {
        UUID maid = maid(roboId(robo));
        if (maid != null && tag != null) tag.putUUID(TAG, maid);
    }
    /** Called from {@code MobileStorageMixin} when a robo is restored, so a restart keeps the return leg. */
    public static void readReturn(Object robo, net.minecraft.nbt.CompoundTag tag) {
        forget(roboId(robo));
        if (tag != null && tag.hasUUID(TAG)) register(roboId(robo), tag.getUUID(TAG));
    }
}
