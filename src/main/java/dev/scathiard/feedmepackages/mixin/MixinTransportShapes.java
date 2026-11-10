package dev.scathiard.feedmepackages.mixin;

import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.calls;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.getClassNode;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.hasCodecCall;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.hasMethod;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Bytecode shape gates for optional logistics network and courier mods:
 * <ul>
 *   <li>Create: Biotech ({@code create_biotech})</li>
 *   <li>Create: Phantom 1.0.2 &amp; 1.0.3 ({@code createphantom})</li>
 *   <li>Create: Mobile Packages ({@code create_mobile_packages})</li>
 *   <li>Create: Package Couriers ({@code cmpackagecouriers})</li>
 *   <li>Create: Factory Logistics ({@code create_factory_logistics})</li>
 * </ul>
 */
public final class MixinTransportShapes {
    private static Boolean biotechReady;
    static String biotechRoot = "com.nobodiiiii.createbiotech.content.allay.";
    private static boolean factoryReady;
    private static Boolean maidPaperReady;
    private static Boolean maidBeeReady;
    private static Boolean mobileStorageReady;

    // Create Phantom 1.0.2 settlement and flight gates (fail-closed until probed).
    private static Boolean phantom102Ready;
    private static Boolean phantom102FlightReady;
    private static Boolean phantom102BeltReady;
    private static Boolean phantom102PortReturnReady;

    // Create Phantom 1.0.3 flight, port return, and belt gates.
    private static Boolean phantom103FlightReady;
    private static Boolean phantom103PortReady;
    private static Boolean phantom103BeltReady;
    private static boolean phantom100FlightReady;

    static boolean phantom100FlightCompatible() { return phantom100FlightReady; }

    static boolean inspectPhantom100Flight(String targetClassName) {
        try {
            var task = getClassNode(targetClassName);
            String server = "Lnet/minecraft/server/MinecraftServer;";
            String level = "Lnet/minecraft/server/level/ServerLevel;";
            String root = "Lcom/yision/phantom/logistics/courier/AirCourierTask";
            phantom100FlightReady = hasMethod(task, "tickLanding", "(" + server + level + ")V")
                    && hasMethod(task, "resolveTarget", "(" + server + ")" + root + "$ResolvedTarget;")
                    && hasMethod(task, "doFinishDeliveryAt", "(" + server + level + ")V")
                    && hasMethod(task, "startCarrierReturn", "(" + server + ")V")
                    && hasMethod(task, "save", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
                    && hasMethod(task, "load", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)" + root + ";")
                    && !hasMethod(task, "resolveFlightTarget", "(" + server + ")" + root + "$FlightTarget;")
                    && List.of("landingTarget", "cruiseTarget", "completionDistance").stream().allMatch(name ->
                            task.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                                    .anyMatch(i -> i instanceof MethodInsnNode call
                                            && call.owner.equals("com/yision/phantom/logistics/courier/flight/AirCourierFlightTargets")
                                            && call.name.equals(name)
                                            && call.desc.equals("(Lcom/yision/phantom/logistics/courier/flight/AirCourierFlightProfile;Lcom/yision/phantom/block/phantomport/PhantomPortBlockEntity;Lnet/minecraft/server/level/ServerPlayer;)"
                                                    + (name.equals("completionDistance") ? "D" : "Lnet/minecraft/world/phys/Vec3;"))));
            return phantom100FlightReady;
        } catch (ClassNotFoundException | IOException unavailable) { return false; }
    }

    private MixinTransportShapes() {}

    static boolean inspectLegacyPaper(String targetClassName) {
        try {
            var node = getClassNode(targetClassName);
            return hasMethod(getClassNode("com.kreidev.cmpackagecouriers.CourierTarget"), "getEntity", "()Lnet/minecraft/world/entity/Entity;")
                    && calls(node, "addPlane", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFLnet/minecraft/world/item/ItemStack;Z)Z",
                    "com/kreidev/cmpackagecouriers/CourierTarget", "getActiveTarget",
                    "(Ljava/lang/String;)Lcom/kreidev/cmpackagecouriers/CourierTarget;") == 1;
        } catch (ClassNotFoundException | IOException absent) { return false; }
    }

    static boolean biotechCompatible() {
        return Boolean.TRUE.equals(biotechReady);
    }

    static boolean factoryCompatible() {
        return factoryReady;
    }

    static boolean mobileStorageCompatible() {
        return Boolean.TRUE.equals(mobileStorageReady);
    }

    static void setMobileStorageReady(boolean ready) {
        mobileStorageReady = ready;
    }

    static boolean maidPaperCompatible() {
        return Boolean.TRUE.equals(maidPaperReady);
    }

    static boolean maidBeeCompatible() {
        return Boolean.TRUE.equals(maidBeeReady);
    }

    static boolean phantom102Compatible() {
        return Boolean.TRUE.equals(phantom102Ready);
    }

    static boolean phantom102FlightCompatible() {
        return Boolean.TRUE.equals(phantom102FlightReady);
    }

    static boolean phantom102BeltCompatible() {
        return Boolean.TRUE.equals(phantom102BeltReady);
    }

    static boolean phantom102PortReturnCompatible() {
        return Boolean.TRUE.equals(phantom102PortReturnReady);
    }

    static boolean phantom103FlightCompatible() {
        return Boolean.TRUE.equals(phantom103FlightReady);
    }

    static boolean phantom103PortCompatible() {
        return Boolean.TRUE.equals(phantom103PortReady);
    }

    static boolean phantom103BeltCompatible() {
        return Boolean.TRUE.equals(phantom103BeltReady);
    }

    static synchronized boolean inspectBiotech() {
        if (biotechReady != null) {
            return biotechReady;
        }
        biotechReady = false;
        if (LoadingModList.get().getMods().stream().noneMatch(m -> m.getModId().equals("create_biotech"))) {
            return false;
        }
        try {
            ClassNode task;
            try {
                task = getClassNode(biotechRoot + "logistics.courier.AllayCourierTask");
            } catch (ClassNotFoundException legacyNamespace) {
                biotechRoot = "com.yision.allay.";
                task = getClassNode(biotechRoot + "logistics.courier.AllayCourierTask");
            }
            String root = biotechRoot + "logistics.courier.";
            var dispatch = getClassNode(root + "AllayCourierDispatchService");
            var helper = getClassNode(root + "AllayCourierHelper");
            String server = "Lnet/minecraft/server/MinecraftServer;";
            String player = "Lnet/minecraft/server/level/ServerPlayer;";
            String level = "Lnet/minecraft/server/level/ServerLevel;";
            String vec = "Lnet/minecraft/world/phys/Vec3;";
            String port = "L" + biotechRoot.replace('.', '/') + "block/allayport/AllayPortBlockEntity;";
            String registry = "Lnet/minecraft/core/HolderLookup$Provider;";
            String tag = "Lnet/minecraft/nbt/CompoundTag;";
            String owner = root.replace('.', '/');

            biotechReady = hasMethod(task, "resolveTarget", "(" + server + ")L" + owner + "AllayCourierTask$ResolvedTarget;")
                    && hasMethod(task, "landingTarget", "(" + port + player + ")" + vec)
                    && hasMethod(task, "nearTargetWaypoint", "(" + port + player + ")" + vec)
                    && hasMethod(task, "hasReachedPlayer", "(" + player + vec + ")Z")
                    && hasMethod(task, "startCarrierReturn", "(" + server + ")V")
                    && hasMethod(task, "save", "(" + registry + tag + ")" + tag)
                    && hasMethod(task, "load", "(" + registry + tag + ")L" + owner + "AllayCourierTask;")
                    && task.methods.stream().filter(m -> m.name.equals("forPackageToPlayer")).count() == 1
                    && hasMethod(dispatch, "resolvePackageTarget", "(" + level + "Lnet/minecraft/world/item/ItemStack;" + vec
                            + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)L" + owner + "AllayCourierTarget;")
                    && List.of("canReceiveDelivery", "deliverPackage", "deliverPackageOnly").stream()
                            .allMatch(name -> hasMethod(helper, name, "(" + player + "Lnet/minecraft/world/item/ItemStack;)Z"))
                    && task.methods.stream().filter(m -> m.name.equals("doFinishDeliveryAt")).anyMatch(m ->
                            Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                                    && call.owner.equals(owner + "AllayCourierDeliveryService")
                                    && call.name.equals("finishDelivery")).count() == 1);
        } catch (ClassNotFoundException | IOException | RuntimeException failed) {
            LogUtils.getLogger().warn("FMP Biotech ports unavailable", failed);
        }
        return biotechReady;
    }

    static boolean isPhantom103Loaded() {
        try {
            return hasMethod(getClassNode("com.yision.phantom.logistics.courier.AirCourierTask"), "prepareFlightTarget",
                    "(Lnet/minecraft/server/MinecraftServer;)Lcom/yision/phantom/logistics/courier/AirCourierTask$FlightTarget;");
        } catch (ClassNotFoundException | IOException unavailable) { return false; }
    }

    static boolean inspectPhantom103PortOrBelt(String targetClassName, String mixinClassName) {
        try {
            var node = getClassNode(targetClassName);
            if (mixinClassName.endsWith("Phantom103PortReturnMixin")) {
                phantom103PortReady = hasMethod(node, "tick", "()V")
                        && calls(node, "tick", "()V",
                                "com/yision/phantom/logistics/courier/AirCourierDeliveryService",
                                "targetState",
                                "(Lnet/minecraft/server/MinecraftServer;Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService$TargetState;") == 1;
                return phantom103PortReady;
            }
            phantom103BeltReady = hasMethod(node, "launchReturningCarrierToPlayer",
                    "(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)Z");
            return phantom103BeltReady;
        } catch (ClassNotFoundException | IOException absent) {
            return false;
        }
    }

    static boolean inspectPhantom103Flight(String targetClassName) {
        try {
            var node = getClassNode(targetClassName);
            String server = "Lnet/minecraft/server/MinecraftServer;";
            String task = "Lcom/yision/phantom/logistics/courier/AirCourierTask";
            phantom103FlightReady = targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierTask")
                    && hasMethod(node, "prepareFlightTarget", "(" + server + ")" + task + "$FlightTarget;")
                    && hasMethod(node, "prepareForDispatch", "(" + server + ")V")
                    && hasMethod(node, "doFinishDelivery", "(" + server + "Lnet/minecraft/server/level/ServerLevel;)V")
                    && hasMethod(node, "startCarrierReturn", "(" + server + ")V")
                    && hasMethod(node, "resolveFlightTarget", "(" + server + ")" + task + "$FlightTarget;")
                    && hasMethod(node, "save", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
                    && hasMethod(node, "load", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)" + task + ";");
            return phantom103FlightReady;
        } catch (ClassNotFoundException | IOException absent) {
            phantom103FlightReady = false;
            return false;
        }
    }

    static boolean inspectPhantom102Settlement(String targetClassName) {
        phantom102Ready = phantom102SettlementTarget(targetClassName);
        return phantom102Ready;
    }

    static boolean inspectPhantom102Flight(String targetClassName) {
        phantom102FlightReady = phantom102FlightTarget(targetClassName);
        return phantom102FlightReady;
    }

    static boolean inspectPhantom102Launch(String targetClassName) {
        try {
            var node = getClassNode(targetClassName);
            return targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierDispatchService")
                    && hasMethod(node, "resolvePackageTarget",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;"
                                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;"
                                    + "Lnet/minecraft/core/BlockPos;)Lcom/yision/phantom/logistics/courier/AirCourierTarget;");
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static boolean inspectPhantom102PortReturn(String targetClassName) {
        try {
            var node = getClassNode(targetClassName);
            phantom102PortReturnReady = targetClassName.equals("com.yision.phantom.block.phantomport.PhantomPortReturnQueue")
                    && hasMethod(node, "tryQueueStoredReturnCarrierToPlayer", "(Ljava/util/UUID;)Z");
            return phantom102PortReturnReady;
        } catch (ClassNotFoundException | IOException unavailable) {
            phantom102PortReturnReady = false;
            return false;
        }
    }

    static boolean inspectPhantom102Belt(String targetClassName) {
        String gate = "(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"
                + "Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)Z";
        String factory = "(Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/server/level/ServerLevel;"
                + "Ljava/util/UUID;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;"
                + "Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Ljava/util/UUID;Ljava/util/UUID;)"
                + "Lcom/yision/phantom/logistics/courier/AirCourierTask;";
        String geometry = "(Lnet/minecraft/server/level/ServerLevel;"
                + "Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"
                + "Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)"
                + "Lcom/yision/phantom/logistics/courier/AirCourierBeltHooks$LaunchGeometry;";
        try {
            var node = getClassNode(targetClassName);
            phantom102BeltReady = targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierBeltHooks")
                    && hasMethod(node, "launchPackageCourier", gate)
                    && calls(node, "launchPackageCourier", gate,
                            "com/yision/phantom/logistics/courier/AirCourierTask", "forPackageToPlayer", factory) >= 1
                    && calls(node, "launchPackageCourier", gate,
                            "com/yision/phantom/logistics/courier/AirCourierBeltHooks", "getLaunchGeometry", geometry) >= 1;
            return phantom102BeltReady;
        } catch (ClassNotFoundException | IOException unavailable) {
            phantom102BeltReady = false;
            return false;
        }
    }

    private static boolean phantom102SettlementTarget(String targetClassName) {
        if (!targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierDispatchService")) {
            return false;
        }
        String descriptor = "(Lnet/minecraft/server/level/ServerLevel;Lcom/yision/phantom/logistics/courier/AirCourierTarget;Lnet/minecraft/world/item/ItemStack;)Z";
        try {
            var node = getClassNode(targetClassName);
            return hasMethod(node, "canReceivePackageTarget", descriptor)
                    && calls(node, "canReceivePackageTarget", descriptor,
                            "net/minecraft/server/players/PlayerList", "getPlayer",
                            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;") >= 1;
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    private static boolean phantom102FlightTarget(String targetClassName) {
        if (!targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierTask")) {
            return false;
        }
        String flightTarget = "Lcom/yision/phantom/logistics/courier/AirCourierTask$FlightTarget;";
        try {
            var node = getClassNode(targetClassName);
            return hasMethod(node, "resolveFlightTarget", "(Lnet/minecraft/server/MinecraftServer;)" + flightTarget)
                    && hasMethod(node, "isDestinationAvailable", "(" + flightTarget + ")Z")
                    && hasMethod(node, "startCarrierReturn", "(Lnet/minecraft/server/MinecraftServer;)V")
                    && hasMethod(node, "resetForReturn", "(Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;)V")
                    && hasMethod(node, "tickLanding",
                            "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;" + flightTarget + ")V")
                    && hasMethod(node, "doFinishDeliveryAt",
                            "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;)V")
                    && hasMethod(node, "save", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
                    && hasMethod(node, "load", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lcom/yision/phantom/logistics/courier/AirCourierTask;");
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static synchronized boolean inspectMaidTransport(boolean bee) {
        Boolean prior = bee ? maidBeeReady : maidPaperReady;
        if (prior != null) {
            return prior;
        }
        boolean ready = false;
        try {
            var event = getClassNode("com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent");
            ready = hasMethod(event, "getMaid", "()Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;");
            if (bee) {
                String root = "de.theidler.create_mobile_packages.";
                var robo = getClassNode(root + "robo.VirtualRobo");
                var port = getClassNode(root + "blocks.bee_port.BeePortBlockEntity");
                var behavior = getClassNode(root + "entities.robo_entity.RoboBeeBehaviorController");
                ready &= hasStorageHook(robo)
                        && hasMethod(robo, "updateTarget", "()V")
                        && hasMethod(port, "sendItem", "(Lnet/minecraft/world/item/ItemStack;I)V")
                        && hasMethod(port, "sendDrone", "(Lnet/minecraft/world/item/ItemStack;I)V")
                        && hasMethod(behavior, "handleDeliverPackage", "(Lde/theidler/create_mobile_packages/robo/VirtualRobo;)V")
                        && robo.fields.stream().anyMatch(f -> f.name.equals("target")
                                && f.desc.equals("Lde/theidler/create_mobile_packages/robo/RoboTarget;"));
            } else {
                var plane = getClassNode("com.kreidev.cmpackagecouriers.plane.CardboardPlane");
                ready &= hasMethod(plane, "tick", "(Lnet/minecraft/server/MinecraftServer;)V")
                        && hasMethod(plane, "onReachedTarget", "(Lnet/minecraft/server/MinecraftServer;)V");
            }
        } catch (ClassNotFoundException absent) {
            ready = false;
        } catch (IOException | RuntimeException unreadable) {
            ready = false;
            LogUtils.getLogger().warn("FMP direct maid transport disabled: native ports unavailable", unreadable);
        }
        if (bee) {
            maidBeeReady = ready;
        } else {
            maidPaperReady = ready;
        }
        return ready;
    }

    static boolean inspectFactoryOrderStamp(String targetClassName) {
        try {
            var node = getClassNode(targetClassName);
            factoryReady = node.methods.stream().anyMatch(m -> m.name.equals("set")
                    && (m.access & Opcodes.ACC_STATIC) != 0
                    && m.desc.equals("(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/item/ItemStack;IIZIZLru/zznty/create_factory_abstractions/generic/support/GenericOrder;)V"));
            LogUtils.getLogger().info("FMP Factory order stamp shapeReady={}", factoryReady);
        } catch (ClassNotFoundException absent) {
            factoryReady = false;
        } catch (IOException unreadable) {
            factoryReady = false;
            LogUtils.getLogger().warn("FMP Factory order stamp disabled", unreadable);
        }
        return factoryReady;
    }

    static boolean hasStorageHook(ClassNode target) {
        String read = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lde/theidler/create_mobile_packages/robo/VirtualRobo;";
        if (!hasMethod(target, "serializeNBT", "()Lnet/minecraft/nbt/CompoundTag;")
                || !hasMethod(target, "setRemoved", "(Lnet/minecraft/server/level/ServerLevel;)V")
                || !hasCodecCall(target, "deserializeNBT", read, "parse")) {
            return false;
        }
        for (var method : target.methods) {
            if (method.name.equals("deserializeNBT") && method.desc.equals(read)) {
                if ((method.access & Opcodes.ACC_STATIC) == 0) {
                    return false;
                }
                int itemCodecs = 0;
                int otherCodecs = 0;
                for (var instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field
                            && field.desc.equals("Lcom/mojang/serialization/Codec;")) {
                        if (field.getOpcode() == Opcodes.GETSTATIC
                                && field.owner.equals("net/minecraft/world/item/ItemStack")
                                && field.name.equals("CODEC")) {
                            itemCodecs++;
                        } else {
                            otherCodecs++;
                        }
                    }
                }
                return itemCodecs == 1 && otherCodecs == 0;
            }
        }
        return false;
    }

    static boolean hasPaperStorageHooks(ClassNode target) {
        String arguments = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)";
        return hasCodecCall(target, "<init>", arguments + "V", "parse")
                && hasCodecCall(target, "save", arguments + "Lnet/minecraft/nbt/CompoundTag;", "encodeStart");
    }
}
