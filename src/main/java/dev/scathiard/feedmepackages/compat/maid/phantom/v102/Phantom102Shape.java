package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shape Create Phantom 1.0.2 must have for FMP to touch it at all (T2-a, plan section 14). Every value here was
 * measured against the real shipped jar with {@code javap -p -s}; nothing is inferred.
 *
 * <p>Pure Java on purpose: the verdict must be unit-testable without Minecraft, and the caller supplies the values
 * it actually found. A single mismatch means <b>not enabled</b> - no partial enable, no silent degradation of a
 * required member.
 */
public final class Phantom102Shape {
    private Phantom102Shape() {}
    public static final String MOD_ID = "createphantom";
    public static final String VERSION = "1.0.2";
    public static final String SHA256 = "7c62765341613a982886fafc644ad1e7face09cf6da76496b97062ba04454c27";

    /** Member (methods) and field descriptors exactly as measured. */
    public static Map<String, String> members() {
        var m = new LinkedHashMap<String, String>();
        // --- movement / resolution (L2 injection points) ---
        m.put("AirCourierTask#resolveFlightTarget", "(Lnet/minecraft/server/MinecraftServer;)Lcom/yision/phantom/logistics/courier/AirCourierTask$FlightTarget;");
        m.put("AirCourierTask#resolveTarget", "(Lnet/minecraft/server/MinecraftServer;)Lcom/yision/phantom/logistics/courier/AirCourierTask$ResolvedTarget;");
        m.put("AirCourierTask#tickWaitingForDestination", "(Lnet/minecraft/server/MinecraftServer;)V");
        // --- settlement (L3 injection points) ---
        m.put("AirCourierTask#doFinishDeliveryAt", "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;)V");
        m.put("AirCourierTask#startCarrierReturn", "(Lnet/minecraft/server/MinecraftServer;)V");
        m.put("AirCourierTask#tryReturnUndeliveredPackage", "(Lnet/minecraft/server/MinecraftServer;)Z");
        // --- target records ---
        m.put("AirCourierTask$FlightTarget#<init>", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;D)V");
        m.put("AirCourierTask$FlightTarget#playerTarget", "()Z");
        m.put("AirCourierTask$ResolvedTarget#<init>", "(Lnet/minecraft/server/level/ServerLevel;Lcom/yision/phantom/block/phantomport/PhantomPortBlockEntity;Lnet/minecraft/server/level/ServerPlayer;)V");
        // --- reception gates (L4) ---
        m.put("AirCourierDispatchService#canReceivePackageTarget", "(Lnet/minecraft/server/level/ServerLevel;Lcom/yision/phantom/logistics/courier/AirCourierTarget;Lnet/minecraft/world/item/ItemStack;)Z");
        m.put("AirCourierHelper#canReceiveDelivery", "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)Z");
        m.put("AirCourierHelper#findTargetPlayer", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/server/level/ServerPlayer;");
        m.put("AirCourierHelper#findTargetPlayerAnyDimension", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/server/level/ServerPlayer;");
        m.put("AirCourierHelper#findTargetPlayer@3", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/server/level/ServerPlayer;");
        m.put("AirCourierDispatchService#canReceiveCarrierTarget", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;)Z");
        // --- launch (L5) ---
        m.put("AirCourierBeltHooks#launchPackageCourier", "(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)Z");
        m.put("AirCourierBeltHooks#getLaunchGeometry", "(Lnet/minecraft/server/level/ServerLevel;Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)Lcom/yision/phantom/logistics/courier/AirCourierBeltHooks$LaunchGeometry;");
        // --- dimension rule (section 15: never hard-code "another dimension is unreachable") ---
        m.put("AirCourierDimensionRules#canTarget", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;)Z");
        m.put("AirCourierDimensionRules#allowCrossDimensionDelivery", "()Z");
        // --- required fields (persistence verdicts rest on these) ---
        m.put("field AirCourierTask#targetPlayerId", "Ljava/util/UUID;");
        m.put("field AirCourierTask#targetPhantomPortPos", "Lnet/minecraft/core/BlockPos;");
        m.put("field AirCourierTask#mission", "Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;");
        m.put("field AirCourierTask#phase", "Lcom/yision/phantom/entity/courier/AirCourierEntity$Phase;");
        m.put("field AirCourierTask#returningUndeliveredPackage", "Z");
        m.put("field AirCourierTask#destinationUnavailableTicks", "I");
        m.put("field AirCourierTask$FlightTarget#player", "Lnet/minecraft/server/level/ServerPlayer;");
        return Map.copyOf(m);
    }

    /** The one log line this project uses for shape gates; stable text, safe to grep and to paste into support. */
    public static final String LOG_PREFIX = "FMP phantom102 gate: ";

    public static String logLine(String verdict) { return LOG_PREFIX + verdict; }

    /**
     * Verdict for the values actually found. {@code actualMemberDescriptors} maps the same keys as
     * {@link #members()} to the descriptors found in the loaded artifact; a missing key means "member absent".
     */
    public static String verdict(boolean modLoaded, String version, String sha256, Map<String, String> actualMemberDescriptors) {
        if (!modLoaded) return "disabled reason=mod-not-loaded";
        if (!VERSION.equals(version)) return "disabled reason=version-mismatch";
        if (!SHA256.equals(sha256)) return "disabled reason=sha256-mismatch";
        for (var expected : members().entrySet()) {
            String actual = actualMemberDescriptors == null ? null : actualMemberDescriptors.get(expected.getKey());
            if (actual == null) return "disabled reason=missing-member " + expected.getKey();
            if (!expected.getValue().equals(actual)) return "disabled reason=descriptor-mismatch " + expected.getKey();
        }
        // Shape alone is not proof that an injection landed: the mixin layer must report its own success flag.
        return "enabled";
    }
}
