package dev.scathiard.feedmepackages.mixin;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Inspects bytecode without loading optional game classes during bootstrap. */
public final class OptionalMixins implements IMixinConfigPlugin {
    private static boolean factoryReady;
    private static Boolean sophisticatedReady;
    public static boolean sophisticatedCompatible() { return Boolean.TRUE.equals(sophisticatedReady); }
    private static Boolean maidPaperReady, maidBeeReady;
    public static boolean maidPaperCompatible() { return Boolean.TRUE.equals(maidPaperReady); }
    public static boolean maidBeeCompatible() { return Boolean.TRUE.equals(maidBeeReady); }
    public static boolean factoryCompatible() { return factoryReady; }
    private static Boolean effortlessReady;
    public static boolean effortlessCompatible() { return Boolean.TRUE.equals(effortlessReady); }
    private static Boolean gadgetsReady;
    public static boolean gadgetsCompatible() { return Boolean.TRUE.equals(gadgetsReady); }
    private static Boolean taczReady;
    private static Boolean taczClientReady;
    public static boolean taczCompatible() { return Boolean.TRUE.equals(taczReady); }
    public static boolean taczClientCompatible() { return Boolean.TRUE.equals(taczClientReady); }
    private static volatile boolean fxntTransferReady;
    private static volatile boolean emiTransferReady;
    private static final Set<String> EMI_PREVIEWS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> FXNT_EMI_ORIGINS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean fxntEmiOriginCompatible() { return FXNT_EMI_ORIGINS.size() == 4; }
    public static boolean emiTransferCompatible() { return emiTransferReady; }
    public static boolean emiPreviewCompatible() { return EMI_PREVIEWS.size() == 3; }
    private static final Set<String> FXNT_PREVIEW_FAILURES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean fxntTransferCompatible() { return fxntTransferReady; }
    public static Set<String> fxntPreviewFailures() { return Set.copyOf(FXNT_PREVIEW_FAILURES); }
    // The old handler-order bridge conflicts with the 0.2.3 compatibility boundary.
    public static boolean emiCompatible() { return false; }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("JeiServerEditMixin") || mixinClassName.endsWith("JeiServerResultEditMixin")) {
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                String arguments = "(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Ljava/util/List;Ljava/util/List;ZZ)";
                boolean modern = hasMethod(node, "setItemsWithResult", arguments + "Z");
                // New JEI's legacy void method delegates to the boolean boundary: never wrap both.
                return mixinClassName.endsWith("JeiServerResultEditMixin") ? modern
                        : !modern && hasMethod(node, "setItems", arguments + "V");
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("JeiSourcesMixin") || mixinClassName.endsWith("JeiContextSourcesMixin")) {
            boolean context = mixinClassName.endsWith("JeiContextSourcesMixin");
            String name = context ? "transferRecipeInternal" : "transferRecipe";
            String descriptor = "(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ"
                    + (context ? "Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;" : "")
                    + ")Lmezz/jei/api/recipe/transfer/IRecipeTransferError;";
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).anyMatch(m -> {
                    int calls = 0;
                    for (var insn : m.instructions) if (insn instanceof MethodInsnNode call
                            && call.owner.equals("mezz/jei/library/transfer/BasicRecipeTransferHandler") && call.name.equals("getInventoryState")
                            && call.desc.equals("(Ljava/util/Collection;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/recipe/transfer/IRecipeTransferInfo;)Lmezz/jei/library/transfer/BasicRecipeTransferHandler$InventoryState;")) calls++;
                    return calls == 1;
                });
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("QuarkScreenAllowMixin")) {
            try {
                return MixinService.getService().getBytecodeProvider().getClassNode(targetClassName).methods.stream()
                        .anyMatch(m -> m.name.equals("isScreenAllowed") && m.desc.equals("(Ljava/lang/Object;)Z")
                                && (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0);
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("SophisticatedRefillMixin") || mixinClassName.endsWith("SophisticatedSourcesMixin")) return inspectSophisticated();
        if (mixinClassName.endsWith("SophisticatedJeiMixin")) {
            if (!inspectSophisticated()) return false;
            try {
                var provider = MixinService.getService().getBytecodeProvider();
                provider.getClassNode("mezz.jei.api.recipe.transfer.IRecipeTransferError");
                return hasMethod(provider.getClassNode(targetClassName), "transferRecipe",
                        "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;");
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("MaidPickupMixin")) {
            try {
                return hasMethod(MixinService.getService().getBytecodeProvider().getClassNode(targetClassName),
                        "pickupItem", "(Lnet/minecraft/world/entity/item/ItemEntity;Z)Z");
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("MaidPaperMixin")) return inspectMaidTransport(false);
        if (mixinClassName.endsWith("MaidBeeTargetMixin") || mixinClassName.endsWith("MaidBeePortMixin")
                || mixinClassName.endsWith("MaidBeeDeliveryMixin")) return inspectMaidTransport(true);
        if (mixinClassName.endsWith("FactoryOrderStampMixin")) {
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                factoryReady = node.methods.stream().anyMatch(m -> m.name.equals("set")
                        && (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0
                        && m.desc.equals("(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/item/ItemStack;IIZIZLru/zznty/create_factory_abstractions/generic/support/GenericOrder;)V"));
                LogUtils.getLogger().info("FMP Factory order stamp shapeReady={}", factoryReady);
            } catch (ClassNotFoundException absent) { factoryReady = false; }
            catch (IOException unreadable) { factoryReady = false; LogUtils.getLogger().warn("FMP Factory order stamp disabled", unreadable); }
            return factoryReady;
        }
        if (mixinClassName.endsWith("EffortlessSourcesMixin") || mixinClassName.endsWith("EffortlessBuildMixin")) return inspectEffortless();
        if (mixinClassName.endsWith("GadgetSourcesMixin") || mixinClassName.endsWith("GadgetBuildMixin")) return inspectGadgets();
        if (mixinClassName.endsWith("TaczHudSourcesMixin") || mixinClassName.endsWith("TaczClientShootMixin")) return inspectTaczClient();
        if (mixinClassName.endsWith("TaczGunSourcesMixin") || mixinClassName.endsWith("TaczScriptSourcesMixin")
                || mixinClassName.endsWith("TaczShootSourcesMixin")) return inspectTacz();
        boolean receive = mixinClassName.endsWith("MobileReceiveMixin");
        boolean storage = mixinClassName.endsWith("MobileStorageMixin");
        boolean paper = mixinClassName.endsWith("PaperStorageMixin");
        boolean fxnt = mixinClassName.endsWith("FxntTransferMixin");
        boolean fxntJei = mixinClassName.endsWith("FxntJeiMixin");
        boolean fxntEmi = mixinClassName.endsWith("FxntEmiOriginMixin");
        boolean emi = mixinClassName.endsWith("EmiPacketSourcesMixin") || mixinClassName.endsWith("EmiStandardSourcesMixin")
                || mixinClassName.endsWith("EmiRecipeSourcesMixin") || mixinClassName.endsWith("EmiInventorySourcesMixin");
        if (mixinClassName.endsWith("EmiHandlersMixin")) return false;
        if (!receive && !storage && !paper && !fxnt && !fxntJei && !fxntEmi && !emi) return true;
        try {
            ClassNode target = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            boolean compatible = receive ? target.methods.stream().anyMatch(method -> method.name.equals("sendPackageToPlayer")
                    && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z"))
                    : storage ? hasStorageHook(target) : paper ? hasPaperStorageHooks(target)
                    : fxnt ? hasFxntTransfer(target) : fxntJei ? hasFxntPreview(target)
                    : fxntEmi ? hasOriginSend(target, "craft", "(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Z") : hasEmiPort(target, mixinClassName);
            if (fxntEmi && compatible) FXNT_EMI_ORIGINS.add(targetClassName);
            if (!compatible) LogUtils.getLogger().warn("FMP optional adapter {} disabled: incompatible method shape", mixinClassName);
            recordFxnt(targetClassName, fxnt, fxntJei, compatible);
            recordEmi(mixinClassName, emi, compatible);
            return compatible;
        } catch (ClassNotFoundException absent) {
            recordFxnt(targetClassName, fxnt, fxntJei, false);
            recordEmi(mixinClassName, emi, false);
            return false;
        } catch (IOException unreadable) {
            recordFxnt(targetClassName, fxnt, fxntJei, false);
            recordEmi(mixinClassName, emi, false);
            LogUtils.getLogger().warn("FMP optional service port disabled: cannot inspect {}", targetClassName, unreadable);
            return false;
        }
    }
    private static synchronized boolean inspectSophisticated() {
        if (sophisticatedReady != null) return sophisticatedReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            provider.getClassNode("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer");
            String root = "net.p3pp3rf1y.sophisticatedcore.";
            var wrapper = provider.getClassNode(root + "upgrades.crafting.CraftingUpgradeWrapper");
            var transfer = provider.getClassNode(root + "compat.recipeviewers.common.CraftingContainerRecipeTransferHandlerServer");
            sophisticatedReady = hasMethod(wrapper, "extractFromStorageOrPlayer", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z")
                    && hasMethod(transfer, "setItemsWithStacks", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeType;Ljava/util/List;Ljava/util/List;Ljava/util/List;Z)V")
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/inventory/Slot;")
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Ljava/lang/Iterable;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/inventory/Slot;")
                    && hasMethod(transfer, "lambda$clearAndPutItemsIntoGrid$0", "(Lnet/minecraft/resources/ResourceLocation;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerBase;)V")
                    && transfer.methods.stream().anyMatch(m -> m.name.equals("putIntoInventory") && java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/entity/player/Inventory") && call.name.equals("add")))
                    && hasSophisticatedTransferPorts(transfer);
        } catch (ClassNotFoundException absent) { sophisticatedReady = false; }
        catch (IOException | RuntimeException unreadable) {
            sophisticatedReady = false; LogUtils.getLogger().warn("FMP Sophisticated adapter disabled: cannot inspect native ports", unreadable);
        }
        return sophisticatedReady;
    }
    private static boolean hasSophisticatedTransferPorts(ClassNode transfer) {
        String storage = "Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;";
        String menu = "Lnet/minecraft/world/inventory/AbstractContainerMenu;", player = "Lnet/minecraft/world/entity/player/Player;";
        String stack = "Lnet/minecraft/world/item/ItemStack;", slot = "Lnet/minecraft/world/inventory/Slot;";
        String list = "Ljava/util/List;", map = "Ljava/util/Map;", id = "Lnet/minecraft/resources/ResourceLocation;", recipeType = "Lnet/minecraft/world/item/crafting/RecipeType;";
        String storageOwner = storage.substring(1, storage.length() - 1), menuOwner = menu.substring(1, menu.length() - 1);
        return calls(transfer, "getSlotWithStack", "(" + storage + "Ljava/lang/Iterable;" + stack + ")" + slot, storageOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "addStack", "(" + storage + "Ljava/util/Collection;" + stack + ")I", storageOwner, "getSlot", "(I)" + slot) == 2
                && calls(transfer, "clearAndPutItemsIntoGrid", "(" + player + id + recipeType + list + menu + map + ")" + list, menuOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "putItemIntoGrid", "(" + menu + map + list + "I)V", menuOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "putIntoInventory", "(" + player + list + storage + list + ")V", "net/minecraft/world/entity/player/Inventory", "add", "(" + stack + ")Z") == 1
                && calls(transfer, "setItems", "(" + storage + player + id + recipeType + map + list + list + "Z)V", storageOwner, "broadcastChanges", "()V") == 1;
    }
    private static synchronized boolean inspectMaidTransport(boolean bee) {
        Boolean prior = bee ? maidBeeReady : maidPaperReady;
        if (prior != null) return prior;
        boolean ready = false;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            var event = provider.getClassNode("com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent");
            ready = hasMethod(event, "getMaid", "()Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;");
            if (bee) {
                String root = "de.theidler.create_mobile_packages.";
                var robo = provider.getClassNode(root + "robo.VirtualRobo");
                var port = provider.getClassNode(root + "blocks.bee_port.BeePortBlockEntity");
                var behavior = provider.getClassNode(root + "entities.robo_entity.RoboBeeBehaviorController");
                ready &= hasMethod(robo, "updateTarget", "()V")
                        && hasMethod(port, "sendItem", "(Lnet/minecraft/world/item/ItemStack;I)V")
                        && hasMethod(port, "sendDrone", "(Lnet/minecraft/world/item/ItemStack;I)V")
                        && hasMethod(behavior, "handleDeliverPackage", "(Lde/theidler/create_mobile_packages/robo/VirtualRobo;)V")
                        && robo.fields.stream().anyMatch(f -> f.name.equals("target") && f.desc.equals("Lde/theidler/create_mobile_packages/robo/RoboTarget;"));
            } else {
                var plane = provider.getClassNode("com.kreidev.cmpackagecouriers.plane.CardboardPlane");
                ready &= hasMethod(plane, "tick", "(Lnet/minecraft/server/MinecraftServer;)V")
                        && hasMethod(plane, "onReachedTarget", "(Lnet/minecraft/server/MinecraftServer;)V");
            }
        } catch (ClassNotFoundException absent) { ready = false; }
        catch (IOException | RuntimeException unreadable) {
            ready = false; LogUtils.getLogger().warn("FMP direct maid transport disabled: native ports unavailable", unreadable);
        }
        if (bee) maidBeeReady = ready; else maidPaperReady = ready;
        return ready;
    }
    private static synchronized boolean inspectGadgets() {
        if (gadgetsReady != null) return gadgetsReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            gadgetsReady = hasGadgetsPorts(provider.getClassNode("com.direwolf20.buildinggadgets2.util.BuildingUtils"),
                    provider.getClassNode("com.direwolf20.buildinggadgets2.common.events.ServerTickHandler"));
            LogUtils.getLogger().info("FMP Building Gadgets sources and placement guard shapeReady={}", gadgetsReady);
        } catch (ClassNotFoundException absent) { gadgetsReady = false; }
        catch (IOException | RuntimeException unreadable) {
            gadgetsReady = false; LogUtils.getLogger().warn("FMP Building Gadgets adapter disabled: cannot inspect native ports", unreadable);
        }
        return gadgetsReady;
    }
    private static synchronized boolean inspectEffortless() {
        if (effortlessReady != null) return effortlessReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            effortlessReady = hasEffortlessPorts(provider.getClassNode("neoforge.nl.requios.effortlessbuilding.utilities.InventoryHelper"),
                    provider.getClassNode("neoforge.nl.requios.effortlessbuilding.network.PacketHandler"));
            LogUtils.getLogger().info("FMP Effortless material ports and batch guard shapeReady={}", effortlessReady);
        } catch (ClassNotFoundException absent) { effortlessReady = false; }
        catch (IOException | RuntimeException unreadable) {
            effortlessReady = false; LogUtils.getLogger().warn("FMP Effortless material ports disabled: cannot inspect native API", unreadable);
        }
        return effortlessReady;
    }
    public static boolean hasEffortlessPorts(ClassNode sources, ClassNode build) {
        String root="neoforge/nl/requios/effortlessbuilding/", args="Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;";
        String place="(L"+root+"network/PlaceBuildModePacket;Lnet/minecraft/server/level/ServerPlayer;)V";
        if(sources.methods.stream().noneMatch(m->m.name.equals("findTotalItemsInInventory") && m.desc.equals("("+args+")I"))
                || calls(sources,"consumeItems","("+args+"I)I","net/minecraft/world/item/ItemStack","shrink","(I)V")!=2) return false;
        boolean complete=calls(build,"handlePlaceBuildMode",place,sources.name,"findTotalItemsInInventory","("+args+")I")==2
                && calls(build,"handlePlaceBuildMode",place,sources.name,"consumeItems","("+args+"I)I")==2
                && calls(build,"handlePlaceBuildMode",place,sources.name,"giveOrDropItems","("+args+"I)V")==3
                && calls(build,"handlePlaceBuildMode",place,root+"buildmode/BuildSettings","canPlaceAt",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;L"+root+"buildmode/BuildSettings$ReplaceMode;Lnet/minecraft/world/item/ItemStack;)Z")==4
                && calls(build,"handlePlaceBuildMode",place,"net/minecraft/server/level/ServerLevel","setBlock",
                    "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")==3
                && calls(build,"handlePlaceBuildMode",place,root+"utilities/UndoManager","recordOperation",
                    "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;Ljava/util/Map;)V")==1;
        if(!complete)return false;
        var sequence=new java.util.ArrayList<String>();
        var body=build.methods.stream().filter(m->m.name.equals("handlePlaceBuildMode") && m.desc.equals(place)).findFirst().orElseThrow();
        for(var instruction:body.instructions)if(instruction instanceof MethodInsnNode call && (
                call.owner.equals(sources.name) && Set.of("findTotalItemsInInventory","giveOrDropItems","consumeItems").contains(call.name)
                || call.owner.equals(root+"buildmode/BuildSettings") && call.name.equals("canPlaceAt")
                || call.owner.equals("net/minecraft/server/level/ServerLevel") && call.name.equals("setBlock")
                || call.owner.equals(root+"utilities/UndoManager") && call.name.equals("recordOperation")))sequence.add(call.name);
        // Reject revisions that move settlement/history ahead of the native writes, even with identical signatures.
        return sequence.equals(List.of("canPlaceAt","findTotalItemsInInventory","canPlaceAt","giveOrDropItems","setBlock","consumeItems",
                "findTotalItemsInInventory","canPlaceAt","giveOrDropItems","setBlock","consumeItems","canPlaceAt","giveOrDropItems","setBlock","recordOperation"));
    }
    public static boolean hasGadgetsPorts(ClassNode sources, ClassNode build) {
        String player = "Lnet/minecraft/world/entity/player/Player;", stack = "Lnet/minecraft/world/item/ItemStack;";
        String remove = "(" + player + "Ljava/util/List;ZLnet/minecraft/core/GlobalPos;Lnet/minecraft/core/Direction;)Z";
        String batch = "(Lcom/direwolf20/buildinggadgets2/common/events/ServerBuildList;" + player + ")V";
        if (calls(sources, "removeStacksFromInventory", remove, sources.name, "checkInventoryForItems",
                "(Lnet/minecraft/world/entity/player/Inventory;Ljava/util/List;Z)V") != 1
                || sources.methods.stream().noneMatch(m -> m.name.equals("countItemStacks") && m.desc.equals("(" + player + stack + ")I"))) return false;
        for (String method : List.of("build", "exchange")) {
            if (calls(build, method, batch, sources.name, "removeStacksFromInventory", remove) != 2
                    || calls(build, method, batch, "net/minecraft/world/level/Level", "setBlockAndUpdate",
                    "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z") != 1) return false;
            var body = build.methods.stream().filter(m -> m.name.equals(method) && m.desc.equals(batch)).findFirst().orElseThrow();
            int phase = 0;
            for (var instruction : body.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(sources.name) && call.name.equals("removeStacksFromInventory") && call.desc.equals(remove)) {
                    if (phase != 0 && phase != 2) return false;
                    phase++;
                } else if (call.owner.equals("net/minecraft/world/level/Level") && call.name.equals("setBlockAndUpdate")) {
                    if (phase != 1) return false;
                    phase++;
                } else if ((call.owner.equals(sources.name) && call.name.equals("giveItemToPlayer"))
                        || (call.owner.equals("com/direwolf20/buildinggadgets2/common/blockentities/RenderBlockBE") && call.name.equals("setRenderData"))) {
                    if (phase != 3) return false;
                }
            }
            if (phase != 3) return false;
        }
        return true;
    }
    private static synchronized boolean inspectTacz() {
        if (taczReady != null) return taczReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            var gun = provider.getClassNode("com.tacz.guns.api.item.gun.AbstractGunItem");
            var script = provider.getClassNode("com.tacz.guns.item.ModernKineticGunScriptAPI");
            var shoot = provider.getClassNode("com.tacz.guns.entity.shooter.LivingEntityShoot");
            taczReady = hasTaczPorts(gun, script, shoot);
            LogUtils.getLogger().info("FMP TaCZ optional source ports shapeReady={}", taczReady);
        } catch (ClassNotFoundException absent) { taczReady = false; }
        catch (IOException | RuntimeException unreadable) {
            taczReady = false; LogUtils.getLogger().warn("FMP TaCZ source ports disabled: cannot inspect bytecode", unreadable);
        }
        return taczReady;
    }
    private static synchronized boolean inspectTaczClient() {
        if (taczClientReady != null) return taczClientReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            taczClientReady = inspectTacz() && hasTaczClientPorts(provider.getClassNode("com.tacz.guns.client.gui.overlay.GunHudOverlay"),
                    provider.getClassNode("com.tacz.guns.client.gameplay.LocalPlayerShoot"));
            LogUtils.getLogger().info("FMP TaCZ client quantity ports shapeReady={}", taczClientReady);
        } catch (ClassNotFoundException absent) { taczClientReady = false; }
        catch (IOException | RuntimeException unreadable) {
            taczClientReady = false; LogUtils.getLogger().warn("FMP TaCZ client quantity ports disabled: cannot inspect bytecode", unreadable);
        }
        return taczClientReady;
    }
    public static boolean hasTaczClientPorts(ClassNode hud, ClassNode shoot) {
        String stack = "Lnet/minecraft/world/item/ItemStack;", data = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;";
        String gun = "Lcom/tacz/guns/api/item/IGun;", display = "Lcom/tacz/guns/client/resource/GunDisplayInstance;";
        String render = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V";
        String inventoryCount = "(" + stack + "Lnet/minecraft/world/entity/player/Inventory;)V";
        return calls(hud, "handleCacheCount", "(Lnet/minecraft/client/player/LocalPlayer;" + stack + data + gun + "Z)V",
                    "net/minecraft/client/player/LocalPlayer", "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "net/minecraft/world/entity/player/Inventory", "getItem", "(I)" + stack) == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "net/minecraft/world/entity/player/Inventory", "getContainerSize", "()I") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "com/tacz/guns/api/item/IAmmo", "isAmmoOfGun", "(" + stack + stack + ")Z") == 1
                && calls(hud, "handleInventoryAmmo", inventoryCount, "com/tacz/guns/api/item/IAmmoBox", "getAmmoCount", "(" + stack + ")I") == 1
                && calls(hud, "render", render, "java/text/DecimalFormat", "format", "(J)Ljava/lang/String;") == 2
                && calls(hud, "render", render, "java/text/DecimalFormat", "format", "(D)Ljava/lang/String;") == 1
                && hud.fields.stream().anyMatch(f -> f.name.equals("CURRENT_AMMO_FORMAT") && f.desc.equals("Ljava/text/DecimalFormat;"))
                && calls(shoot, "doShoot", "(" + display + gun + stack + data + "JF)V",
                    "com/tacz/guns/api/item/IGun", "getCurrentAmmoCount", "(" + stack + ")I") == 1;
    }
    /** All ports are checked together: a query-only partial installation must not advertise unspendable ammo. */
    public static boolean hasTaczPorts(ClassNode gun, ClassNode script, ClassNode shoot) {
        String actor = "Lnet/minecraft/world/entity/LivingEntity;", stack = "Lnet/minecraft/world/item/ItemStack;";
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
    private static void recordFxnt(String target, boolean transfer, boolean preview, boolean ready) {
        if (transfer) fxntTransferReady = ready;
        if (preview) { if (ready) FXNT_PREVIEW_FAILURES.remove(target); else FXNT_PREVIEW_FAILURES.add(target); }
    }
    private static void recordEmi(String mixin, boolean relevant, boolean ready) {
        if (!relevant) return;
        if (mixin.endsWith("EmiPacketSourcesMixin")) emiTransferReady = ready;
        else if (ready) EMI_PREVIEWS.add(mixin); else EMI_PREVIEWS.remove(mixin);
    }
    private static boolean hasEmiPort(ClassNode target, String mixin) {
        if (mixin.endsWith("EmiPacketSourcesMixin")) {
            String collect = "(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Ljava/util/List;Ljava/util/List;Lnet/minecraft/world/item/ItemStack;)I";
            return calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "mayPickup", "(Lnet/minecraft/world/entity/player/Player;)Z") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "getItem", "()Lnet/minecraft/world/item/ItemStack;") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "setByPlayer", "(Lnet/minecraft/world/item/ItemStack;)V") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "onTake", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/item/ItemStack", "setCount", "(I)V") == 2
                    && calls(target, "apply", "(Lnet/minecraft/world/entity/player/Player;)V", "dev/emi/emi/network/FillRecipeC2SPacket", "grabMatching",
                        collect) == 1;
        }
        String name, descriptor;
        if (mixin.endsWith("EmiStandardSourcesMixin")) {
            name = "getInventory"; descriptor = "(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;";
        } else if (mixin.endsWith("EmiRecipeSourcesMixin")) {
            name = "getStacks"; descriptor = "(Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;Ldev/emi/emi/api/recipe/EmiRecipe;Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;I)Ljava/util/List;";
            String grid = "(Ldev/emi/emi/api/recipe/EmiRecipe;Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;";
            if (calls(target, name, descriptor, "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getCraftingSlots", grid) != 1
                    || calls(target, "batchesAlreadyPresent", "(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)I",
                        "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getCraftingSlots", grid) != 1) return false;
        } else { name = "<init>"; descriptor = "(Lnet/minecraft/world/entity/player/Player;)V"; }
        return calls(target, name, descriptor, "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getInputSources",
                "(Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;") == 1;
    }
    private static boolean hasMethod(ClassNode target, String name, String descriptor) {
        return target.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }
    private static boolean hasFxntTransfer(ClassNode target) {
        String collect = "(Lnet/minecraft/world/item/crafting/Ingredient;IILnet/minecraft/world/entity/player/Inventory;Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Ljava/util/List;)Z";
        return hasMethod(target, "getMaxCraftableItems", "(Ljava/util/List;Lnet/minecraft/world/entity/player/Inventory;Lnet/neoforged/neoforge/items/IItemHandler;)I")
                && calls(target, "handle", "(Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
                    "net/neoforged/neoforge/network/handling/IPayloadContext", "enqueueWork", "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;") == 1
                && hasMethod(target, "lambda$handle$1", "(Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V")
                && calls(target, "lambda$handle$1", "(Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
                    "net/fxnt/fxntstorage/backpack/inventory/IBackpackContainer", "setDataChanged", "()V") == 1
                && calls(target, "collectAndPlace", collect, "net/minecraft/world/inventory/Slot", "setByPlayer", "(Lnet/minecraft/world/item/ItemStack;)V") == 1
                && calls(target, "collectAndPlace", collect, "net/minecraft/world/entity/player/Inventory", "getItem", "(I)Lnet/minecraft/world/item/ItemStack;") == 1
                && calls(target, "collectAndPlace", collect, "net/neoforged/neoforge/items/IItemHandlerModifiable", "getStackInSlot", "(I)Lnet/minecraft/world/item/ItemStack;") == 1
                && calls(target, "collectAndPlace", collect, "net/neoforged/neoforge/items/IItemHandlerModifiable", "setStackInSlot", "(ILnet/minecraft/world/item/ItemStack;)V") == 1
                && calls(target, "collectAndPlace", collect, "net/minecraft/world/entity/player/Inventory", "setItem", "(ILnet/minecraft/world/item/ItemStack;)V") == 1;
    }
    private static boolean hasFxntPreview(ClassNode target) {
        String menu = target.name.endsWith("JEIBackpackCraftingTransferHandler") ? "net/fxnt/fxntstorage/backpack/client/menu/BackpackMenu"
                : target.name.endsWith("JEIStonecuttingTransferHandler") ? "net/minecraft/world/inventory/StonecutterMenu"
                : "net/minecraft/world/inventory/CraftingMenu";
        String descriptor = "(L" + menu + ";Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;";
        return calls(target, "transferRecipe", descriptor, "net/minecraft/world/entity/player/Player", "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;") == 1
                && hasOriginSend(target, "transferRecipe", descriptor);
    }
    private static boolean hasOriginSend(ClassNode target, String method, String descriptor) {
        return calls(target, method, descriptor, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer",
                "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V") == 1;
    }
    private static int calls(ClassNode target, String name, String descriptor, String owner, String callName, String callDescriptor) {
        int count = 0;
        for (var method : target.methods) if (method.name.equals(name) && method.desc.equals(descriptor))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(callName) && call.desc.equals(callDescriptor)) count++;
        return count;
    }
    private static boolean hasStorageHook(ClassNode target) {
        return hasCall(target, "deserializeNBT", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lde/theidler/create_mobile_packages/robo/VirtualRobo;", "parse");
    }
    private static boolean hasPaperStorageHooks(ClassNode target) {
        String arguments = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)";
        return hasCall(target, "<init>", arguments + "V", "parse")
                && hasCall(target, "save", arguments + "Lnet/minecraft/nbt/CompoundTag;", "encodeStart");
    }
    private static boolean hasCall(ClassNode target, String name, String descriptor, String invocation) {
        for (var method : target.methods) {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) continue;
            int matches = 0;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/serialization/Codec") && call.name.equals(invocation)
                    && call.desc.equals("(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")) matches++;
            return matches == 1;
        }
        return false;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
