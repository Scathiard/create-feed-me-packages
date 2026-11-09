package dev.scathiard.feedmepackages.mixin;

import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.calls;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.getClassNode;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.hasMethod;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Bytecode shape gates for optional material/ammo consumers and maid interactions:
 * <ul>
 *   <li>Timeless and Classics Zero ({@code tacz})</li>
 *   <li>Superb Warfare ({@code superbwarfare})</li>
 *   <li>Effortless Building ({@code effortlessbuilding})</li>
 *   <li>Building Gadgets 2 ({@code buildinggadgets2})</li>
 *   <li>Touhou Little Maid ({@code touhou_little_maid})</li>
 * </ul>
 */
final class MixinConsumptionShapes {
    private static Boolean sbwReady;
    private static Boolean effortlessReady;
    private static Boolean gadgetsReady;
    private static Boolean taczReady;
    private static Boolean taczClientReady;
    private static boolean taczSharedCount;

    private MixinConsumptionShapes() {}

    static boolean sbwCompatible() {
        return Boolean.TRUE.equals(sbwReady);
    }

    static boolean effortlessCompatible() {
        return Boolean.TRUE.equals(effortlessReady);
    }

    static boolean gadgetsCompatible() {
        return Boolean.TRUE.equals(gadgetsReady);
    }

    static boolean taczCompatible() {
        return Boolean.TRUE.equals(taczReady);
    }

    static boolean taczClientCompatible() {
        return Boolean.TRUE.equals(taczClientReady);
    }

    static boolean taczSharedCount() {
        return taczSharedCount;
    }

    static boolean inspectMaidPickup(String targetClassName) {
        try {
            return hasMethod(getClassNode(targetClassName), "pickupItem", "(Lnet/minecraft/world/entity/item/ItemEntity;Z)Z");
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static synchronized boolean inspectSbw() {
        if (sbwReady != null) {
            return sbwReady;
        }
        try {
            String prefix = "com.atsuishio.superbwarfare.data.gun.";
            String data = "Lcom/atsuishio/superbwarfare/data/gun/GunData;";
            String consumer = "Lcom/atsuishio/superbwarfare/data/gun/AmmoConsumer;";
            String entity = "Lnet/minecraft/world/entity/Entity;";
            String cap = "Lnet/neoforged/neoforge/capabilities/EntityCapability;";
            var gun = getClassNode(prefix + "GunData");
            boolean ready = hasMethod(gun, "countBackupAmmo", "(" + entity + ")I")
                    && gun.fields.stream().anyMatch(f -> f.name.equals("cachedBackupAmmo") && f.desc.equals("I"));
            for (String type : List.of("ItemAmmoStrategy", "PlayerAmmoStrategy")) {
                var strategy = getClassNode(prefix + "ammo_consumer_strategy." + type);
                ready &= calls(strategy, "consume", "(" + data + consumer + entity + "I)I",
                                "net/minecraft/world/entity/Entity", "getCapability", "(" + cap + ")Ljava/lang/Object;") == 1
                        && calls(strategy, "count", "(" + data + consumer + entity + ")I",
                                "net/minecraft/world/entity/Entity", "getCapability", "(" + cap + ")Ljava/lang/Object;") == 1;
            }
            var vehicle = getClassNode("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
            ready &= hasMethod(vehicle, "getGunData", "(I)" + data)
                    && hasMethod(vehicle, "getNthEntity", "(I)" + entity)
                    && hasMethod(vehicle, "getOrderedPassengers", "()Ljava/util/List;")
                    && hasMethod(vehicle, "getAmmoSupplier", "()" + entity)
                    && gun.fields.stream().anyMatch(f -> f.name.equals("stack") && f.desc.equals("Lnet/minecraft/world/item/ItemStack;"));
            if (!ready) {
                LogUtils.getLogger().warn("FMP Superb Warfare adapter disabled: native ammo/seat signatures changed");
            }
            sbwReady = ready;
            return sbwReady;
        } catch (ClassNotFoundException | IOException absent) {
            sbwReady = false;
            return false;
        }
    }

    static synchronized boolean inspectGadgets() {
        if (gadgetsReady != null) {
            return gadgetsReady;
        }
        try {
            gadgetsReady = hasGadgetsPorts(
                    getClassNode("com.direwolf20.buildinggadgets2.util.BuildingUtils"),
                    getClassNode("com.direwolf20.buildinggadgets2.common.events.ServerTickHandler"));
            LogUtils.getLogger().info("FMP Building Gadgets sources and placement guard shapeReady={}", gadgetsReady);
        } catch (ClassNotFoundException absent) {
            gadgetsReady = false;
        } catch (IOException | RuntimeException unreadable) {
            gadgetsReady = false;
            LogUtils.getLogger().warn("FMP Building Gadgets adapter disabled: cannot inspect native ports", unreadable);
        }
        return gadgetsReady;
    }

    static synchronized boolean inspectEffortless() {
        if (effortlessReady != null) {
            return effortlessReady;
        }
        try {
            effortlessReady = hasEffortlessPorts(
                    getClassNode("neoforge.nl.requios.effortlessbuilding.utilities.InventoryHelper"),
                    getClassNode("neoforge.nl.requios.effortlessbuilding.network.PacketHandler"));
            LogUtils.getLogger().info("FMP Effortless material ports and batch guard shapeReady={}", effortlessReady);
        } catch (ClassNotFoundException absent) {
            effortlessReady = false;
        } catch (IOException | RuntimeException unreadable) {
            effortlessReady = false;
            LogUtils.getLogger().warn("FMP Effortless material ports disabled: cannot inspect native API", unreadable);
        }
        return effortlessReady;
    }

    static boolean hasEffortlessPorts(ClassNode sources, ClassNode build) {
        String root = "neoforge/nl/requios/effortlessbuilding/";
        String args = "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;";
        String place = "(L" + root + "network/PlaceBuildModePacket;Lnet/minecraft/server/level/ServerPlayer;)V";
        if (sources.methods.stream().noneMatch(m -> m.name.equals("findTotalItemsInInventory") && m.desc.equals("(" + args + ")I"))
                || calls(sources, "consumeItems", "(" + args + "I)I", "net/minecraft/world/item/ItemStack", "shrink", "(I)V") != 2) {
            return false;
        }
        boolean complete = calls(build, "handlePlaceBuildMode", place, sources.name, "findTotalItemsInInventory", "(" + args + ")I") == 2
                && calls(build, "handlePlaceBuildMode", place, sources.name, "consumeItems", "(" + args + "I)I") == 2
                && calls(build, "handlePlaceBuildMode", place, sources.name, "giveOrDropItems", "(" + args + "I)V") == 3
                && calls(build, "handlePlaceBuildMode", place, root + "buildmode/BuildSettings", "canPlaceAt",
                        "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;L" + root + "buildmode/BuildSettings$ReplaceMode;Lnet/minecraft/world/item/ItemStack;)Z") == 4
                && calls(build, "handlePlaceBuildMode", place, "net/minecraft/server/level/ServerLevel", "setBlock",
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z") == 3
                && calls(build, "handlePlaceBuildMode", place, root + "utilities/UndoManager", "recordOperation",
                        "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;Ljava/util/Map;)V") == 1;
        if (!complete) {
            return false;
        }
        var sequence = new ArrayList<String>();
        var body = build.methods.stream()
                .filter(m -> m.name.equals("handlePlaceBuildMode") && m.desc.equals(place))
                .findFirst()
                .orElseThrow();
        for (var instruction : body.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && (call.owner.equals(sources.name) && Set.of("findTotalItemsInInventory", "giveOrDropItems", "consumeItems").contains(call.name)
                            || call.owner.equals(root + "buildmode/BuildSettings") && call.name.equals("canPlaceAt")
                            || call.owner.equals("net/minecraft/server/level/ServerLevel") && call.name.equals("setBlock")
                            || call.owner.equals(root + "utilities/UndoManager") && call.name.equals("recordOperation"))) {
                sequence.add(call.name);
            }
        }
        // Reject revisions that move settlement/history ahead of the native writes, even with identical signatures.
        return sequence.equals(List.of(
                "canPlaceAt",
                "findTotalItemsInInventory",
                "canPlaceAt",
                "giveOrDropItems",
                "setBlock",
                "consumeItems",
                "findTotalItemsInInventory",
                "canPlaceAt",
                "giveOrDropItems",
                "setBlock",
                "consumeItems",
                "canPlaceAt",
                "giveOrDropItems",
                "setBlock",
                "recordOperation"));
    }

    static boolean hasGadgetsPorts(ClassNode sources, ClassNode build) {
        String player = "Lnet/minecraft/world/entity/player/Player;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String remove = "(" + player + "Ljava/util/List;ZLnet/minecraft/core/GlobalPos;Lnet/minecraft/core/Direction;)Z";
        String batch = "(Lcom/direwolf20/buildinggadgets2/common/events/ServerBuildList;" + player + ")V";
        if (calls(sources, "removeStacksFromInventory", remove, sources.name, "checkInventoryForItems",
                        "(Lnet/minecraft/world/entity/player/Inventory;Ljava/util/List;Z)V") != 1
                || sources.methods.stream().noneMatch(m -> m.name.equals("countItemStacks") && m.desc.equals("(" + player + stack + ")I"))) {
            return false;
        }
        for (String method : List.of("build", "exchange")) {
            if (calls(build, method, batch, sources.name, "removeStacksFromInventory", remove) != 2
                    || calls(build, method, batch, "net/minecraft/world/level/Level", "setBlockAndUpdate",
                            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z") != 1) {
                return false;
            }
            var body = build.methods.stream()
                    .filter(m -> m.name.equals(method) && m.desc.equals(batch))
                    .findFirst()
                    .orElseThrow();
            int phase = 0;
            for (var instruction : body.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(sources.name) && call.name.equals("removeStacksFromInventory") && call.desc.equals(remove)) {
                        if (phase != 0 && phase != 2) {
                            return false;
                        }
                        phase++;
                    } else if (call.owner.equals("net/minecraft/world/level/Level") && call.name.equals("setBlockAndUpdate")) {
                        if (phase != 1) {
                            return false;
                        }
                        phase++;
                    } else if ((call.owner.equals(sources.name) && call.name.equals("giveItemToPlayer"))
                            || (call.owner.equals("com/direwolf20/buildinggadgets2/common/blockentities/RenderBlockBE")
                                    && call.name.equals("setRenderData"))) {
                        if (phase != 3) {
                            return false;
                        }
                    }
                }
            }
            if (phase != 3) {
                return false;
            }
        }
        return true;
    }

    static boolean inspectTaczAnimation(String targetClassName) {
        if (!inspectTaczClient()) {
            return false;
        }
        try {
            var node = getClassNode(targetClassName);
            return hasMethod(node, "hasAmmoToConsume", "()Z")
                    && node.fields.stream().anyMatch(f -> f.name.equals("currentGunItem")
                            && f.desc.equals("Lnet/minecraft/world/item/ItemStack;"));
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static synchronized boolean inspectTacz() {
        if (taczReady != null) {
            return taczReady;
        }
        try {
            var gun = getClassNode("com.tacz.guns.api.item.gun.AbstractGunItem");
            var script = getClassNode("com.tacz.guns.item.ModernKineticGunScriptAPI");
            var shoot = getClassNode("com.tacz.guns.entity.shooter.LivingEntityShoot");
            taczReady = hasTaczPorts(gun, script, shoot);
            LogUtils.getLogger().info("FMP TaCZ optional source ports shapeReady={}", taczReady);
        } catch (ClassNotFoundException absent) {
            taczReady = false;
        } catch (IOException | RuntimeException unreadable) {
            taczReady = false;
            LogUtils.getLogger().warn("FMP TaCZ source ports disabled: cannot inspect bytecode", unreadable);
        }
        return taczReady;
    }

    static synchronized boolean inspectTaczClient() {
        if (taczClientReady != null) {
            return taczClientReady;
        }
        try {
            var hud = getClassNode("com.tacz.guns.client.gui.overlay.GunHudOverlay");
            var shoot = getClassNode("com.tacz.guns.client.gameplay.LocalPlayerShoot");
            ClassNode count = null;
            try {
                count = getClassNode("com.tacz.guns.client.gameplay.ClientAmmoCount");
            } catch (ClassNotFoundException legacy) {
                // Unofficial 1.21.1 port (1.1.8-hotfix) has no shared ClientAmmoCount reader.
            }
            taczSharedCount = count != null;
            taczClientReady = inspectTacz()
                    && (taczSharedCount
                            ? hasTaczCountPorts(hud, shoot, count, getClassNode("com.tacz.guns.client.gameplay.LocalPlayerDataHolder"))
                            : hasTaczClientPorts(hud, shoot));
            LogUtils.getLogger().info("FMP TaCZ client quantity ports shapeReady={} sharedCount={}", taczClientReady, taczSharedCount);
        } catch (ClassNotFoundException absent) {
            taczClientReady = false;
        } catch (IOException | RuntimeException unreadable) {
            taczClientReady = false;
            LogUtils.getLogger().warn("FMP TaCZ client quantity ports disabled: cannot inspect bytecode", unreadable);
        }
        return taczClientReady;
    }

    static boolean hasTaczClientPorts(ClassNode hud, ClassNode shoot) {
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String data = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;";
        String gun = "Lcom/tacz/guns/api/item/IGun;";
        String display = "Lcom/tacz/guns/client/resource/GunDisplayInstance;";
        String inventoryCount = "(" + stack + "Lnet/minecraft/world/entity/player/Inventory;)V";
        return calls(hud, "handleCacheCount", "(Lnet/minecraft/client/player/LocalPlayer;" + stack + data + gun + "Z)V",
                        "net/minecraft/client/player/LocalPlayer", "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "net/minecraft/world/entity/player/Inventory", "getItem", "(I)" + stack) == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "net/minecraft/world/entity/player/Inventory", "getContainerSize", "()I") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "com/tacz/guns/api/item/IAmmo", "isAmmoOfGun", "(" + stack + stack + ")Z") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "com/tacz/guns/api/item/IAmmoBox", "getAmmoCount", "(" + stack + ")I") == 1
                && hasTaczHudFormat(hud)
                && calls(shoot, "doShoot", "(" + display + gun + stack + data + "JF)V",
                        "com/tacz/guns/api/item/IGun", "getCurrentAmmoCount", "(" + stack + ")I") == 1;
    }

    static boolean hasTaczCountPorts(ClassNode hud, ClassNode shoot, ClassNode count, ClassNode holder) {
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String player = "Lnet/minecraft/client/player/LocalPlayer;";
        String gun = "Lcom/tacz/guns/api/item/IGun;";
        String data = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;";
        String display = "Lcom/tacz/guns/client/resource/GunDisplayInstance;";
        String read = "(" + player + stack + gun + ")I";
        String counter = "com/tacz/guns/client/gameplay/ClientAmmoCount";
        return hasTaczHudFormat(hud)
                && calls(hud, "handleCacheCount", "(" + player + stack + data + gun + ")V", counter, "getCached", read) == 1
                && calls(shoot, "doShoot", "(" + display + gun + stack + data + "JF)V", counter, "get", read) == 1
                && calls(count, "getCached", read, "com/tacz/guns/client/gameplay/LocalPlayerDataHolder", "getCachedAmmoCount", "(" + stack + gun + ")I") == 1
                && calls(holder, "getCachedAmmoCount", "(" + stack + gun + ")I", counter, "get", read) == 1
                && calls(count, "get", read, "net/minecraft/client/player/LocalPlayer", "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;") == 1
                && calls(count, "get", read, "net/minecraft/world/entity/player/Inventory", "getItem", "(I)" + stack) == 1
                && calls(count, "get", read, "net/minecraft/world/entity/player/Inventory", "getContainerSize", "()I") == 1
                && calls(count, "get", read, "com/tacz/guns/api/item/IAmmo", "isAmmoOfGun", "(" + stack + stack + ")Z") == 1
                && calls(count, "get", read, "com/tacz/guns/api/item/IAmmoBox", "isAmmoBoxOfGun", "(" + stack + stack + ")Z") == 1
                && calls(count, "get", read, "com/tacz/guns/api/item/IAmmoBox", "getAmmoCount", "(" + stack + ")I") == 1;
    }

    private static boolean hasTaczHudFormat(ClassNode hud) {
        String render = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V";
        return calls(hud, "render", render, "java/text/DecimalFormat", "format", "(J)Ljava/lang/String;") == 2
                && calls(hud, "render", render, "java/text/DecimalFormat", "format", "(D)Ljava/lang/String;") == 1
                && hud.fields.stream().anyMatch(f -> f.name.equals("CURRENT_AMMO_FORMAT") && f.desc.equals("Ljava/text/DecimalFormat;"));
    }

    static boolean hasTaczPorts(ClassNode gun, ClassNode script, ClassNode shoot) {
        String actor = "Lnet/minecraft/world/entity/LivingEntity;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String extract = "(Lnet/neoforged/neoforge/items/IItemHandler;" + stack + "I)I";
        return taczCapability(gun, "canReload", "(" + actor + stack + ")Z")
                && taczCapability(gun, "hasInventoryAmmo", "(" + actor + stack + "Z)Z")
                && taczCapability(script, "hasAmmoToConsume", "()Z")
                && taczCapability(script, "consumeAmmoFromPlayer", "(I)I")
                && taczCapability(shoot, "consumeAmmoFromPlayer", "(I" + stack + "Z)V")
                && script.fields.stream().anyMatch(f -> f.name.equals("itemStack") && f.desc.equals(stack))
                && calls(gun, "findAndExtractInventoryAmmo", extract, "net/neoforged/neoforge/items/IItemHandler", "getStackInSlot", "(I)" + stack) == 1
                && calls(gun, "findAndExtractInventoryAmmo", extract, "net/neoforged/neoforge/items/IItemHandler", "extractItem", "(IIZ)" + stack) == 1
                && calls(gun, "findAndExtractInventoryAmmo", extract, "com/tacz/guns/api/item/IAmmo", "isAmmoOfGun", "(" + stack + stack + ")Z") == 1;
    }

    private static boolean taczCapability(ClassNode target, String method, String descriptor) {
        return calls(target, method, descriptor, "net/minecraft/world/entity/LivingEntity", "getCapability",
                "(Lnet/neoforged/neoforge/capabilities/EntityCapability;Ljava/lang/Object;)Ljava/lang/Object;") == 1;
    }
}
