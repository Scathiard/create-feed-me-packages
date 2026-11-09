package dev.scathiard.feedmepackages.mixin;

import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.calls;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.getClassNode;
import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.hasMethod;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Bytecode shape gates for optional recipe viewers, crafting tweak tools, and backpack/storage
 * crafting upgrades:
 * <ul>
 *   <li>Crafting Tweaks ({@code craftingtweaks})</li>
 *   <li>Just Enough Items ({@code jei})</li>
 *   <li>EMI ({@code emi})</li>
 *   <li>Create: Storage ({@code fxntstorage})</li>
 *   <li>Sophisticated Core / Backpacks / Storage ({@code sophisticatedcore}, {@code sophisticatedbackpacks}, {@code sophisticatedstorage})</li>
 *   <li>Quark screen overlay exemptions</li>
 * </ul>
 */
final class MixinCraftingShapes {
    static final List<String> CRAFTING_TWEAK_MESSAGES = List.of(
            "RotateMessage",
            "BalanceMessage",
            "ClearMessage",
            "TransferStackMessage",
            "RefillLastCraftedMessage",
            "CompressMessage",
            "CraftStackMessage");

    private static Boolean craftingTweaksReady;
    private static Boolean sophisticatedReady;
    private static volatile boolean fxntTransferReady;
    private static volatile boolean emiTransferReady;
    private static final Set<String> EMI_PREVIEWS = ConcurrentHashMap.newKeySet();
    private static final Set<String> FXNT_EMI_ORIGINS = ConcurrentHashMap.newKeySet();
    private static final Set<String> FXNT_PREVIEW_FAILURES = ConcurrentHashMap.newKeySet();

    private MixinCraftingShapes() {}

    static boolean craftingTweaksCompatible() {
        return Boolean.TRUE.equals(craftingTweaksReady);
    }

    static boolean craftingTweaksSafe() {
        return !ModList.get().isLoaded("craftingtweaks") || craftingTweaksCompatible();
    }

    static boolean sophisticatedCompatible() {
        return Boolean.TRUE.equals(sophisticatedReady);
    }

    static boolean fxntEmiOriginCompatible() {
        return FXNT_EMI_ORIGINS.size() == 4;
    }

    static boolean emiTransferCompatible() {
        return emiTransferReady;
    }

    static boolean emiPreviewCompatible() {
        return EMI_PREVIEWS.size() == 3;
    }

    static boolean fxntTransferCompatible() {
        return fxntTransferReady;
    }

    static Set<String> fxntPreviewFailures() {
        return Set.copyOf(FXNT_PREVIEW_FAILURES);
    }

    static void recordFxntEmiOrigin(String targetClassName) {
        FXNT_EMI_ORIGINS.add(targetClassName);
    }

    static synchronized boolean inspectCraftingTweaks() {
        if (craftingTweaksReady != null) {
            return craftingTweaksReady;
        }
        try {
            var nodes = new HashMap<String, ClassNode>();
            for (var name : CRAFTING_TWEAK_MESSAGES) {
                nodes.put(name, getClassNode("net.blay09.mods.craftingtweaks.network." + name));
            }
            craftingTweaksReady = craftingTweaksShape(nodes);
            return craftingTweaksReady;
        } catch (ClassNotFoundException | IOException absent) {
            craftingTweaksReady = false;
            return false;
        }
    }

    static boolean craftingTweaksShape(Map<String, ClassNode> nodes) {
        for (var name : CRAFTING_TWEAK_MESSAGES) {
            String owner = "net/blay09/mods/craftingtweaks/network/" + name;
            var node = nodes.get(name);
            if (node == null || !owner.equals(node.name)) {
                return false;
            }
            var handlers = node.methods.stream().filter(m -> m.name.equals("handle")).toList();
            // WrapMethod selects by name: a new overload is also an unsupported shape.
            if (handlers.size() != 1) {
                return false;
            }
            var method = handlers.getFirst();
            if (!method.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;L" + owner + ";)V")
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return false;
            }
        }
        return true;
    }

    static boolean inspectJeiServerEdit(String targetClassName, String mixinClassName) {
        try {
            var node = getClassNode(targetClassName);
            String arguments = "(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Ljava/util/List;Ljava/util/List;ZZ)";
            boolean modern = hasMethod(node, "setItemsWithResult", arguments + "Z");
            // New JEI's legacy void method delegates to the boolean boundary: never wrap both.
            return mixinClassName.endsWith("JeiServerResultEditMixin")
                    ? modern
                    : !modern && hasMethod(node, "setItems", arguments + "V");
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static boolean inspectJeiSources(String targetClassName, String mixinClassName) {
        boolean context = mixinClassName.endsWith("JeiContextSourcesMixin");
        String name = context ? "transferRecipeInternal" : "transferRecipe";
        String descriptor = "(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ"
                + (context ? "Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;" : "")
                + ")Lmezz/jei/api/recipe/transfer/IRecipeTransferError;";
        try {
            var node = getClassNode(targetClassName);
            return node.methods.stream()
                    .filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                    .anyMatch(m -> {
                        int calls = 0;
                        for (var insn : m.instructions) {
                            if (insn instanceof MethodInsnNode call
                                    && call.owner.equals("mezz/jei/library/transfer/BasicRecipeTransferHandler")
                                    && call.name.equals("getInventoryState")
                                    && call.desc.equals("(Ljava/util/Collection;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/recipe/transfer/IRecipeTransferInfo;)Lmezz/jei/library/transfer/BasicRecipeTransferHandler$InventoryState;")) {
                                calls++;
                            }
                        }
                        return calls == 1;
                    });
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static boolean inspectQuarkScreenAllow(String targetClassName) {
        try {
            return getClassNode(targetClassName).methods.stream()
                    .anyMatch(m -> m.name.equals("isScreenAllowed")
                            && m.desc.equals("(Ljava/lang/Object;)Z")
                            && (m.access & Opcodes.ACC_STATIC) != 0);
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static synchronized boolean inspectSophisticated() {
        if (sophisticatedReady != null) {
            return sophisticatedReady;
        }
        try {
            getClassNode("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase");
            String root = "net.p3pp3rf1y.sophisticatedcore.";
            var wrapper = getClassNode(root + "upgrades.crafting.CraftingUpgradeWrapper");
            var transfer = getClassNode(root + "compat.recipeviewers.common.CraftingContainerRecipeTransferHandlerServer");
            sophisticatedReady = hasMethod(wrapper, "extractFromStorageOrPlayer", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z")
                    && (hasMethod(transfer, "setItemsWithStacks", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeType;Ljava/util/List;Ljava/util/List;Ljava/util/List;Z)V")
                            || hasMethod(transfer, "setItemsWithStacks", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeType;Ljava/util/List;Ljava/util/List;Ljava/util/List;Z)Z"))
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/inventory/Slot;")
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Ljava/lang/Iterable;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/inventory/Slot;")
                    && transfer.methods.stream().filter(m -> m.name.startsWith("lambda$clearAndPutItemsIntoGrid$")).count() == 1
                    && transfer.methods.stream().anyMatch(m -> Set.of("lambda$clearAndPutItemsIntoGrid$0", "lambda$clearAndPutItemsIntoGrid$2").contains(m.name)
                            && m.desc.equals("(Lnet/minecraft/resources/ResourceLocation;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerBase;)V")
                            && calls(transfer, m.name, m.desc, root.replace('.', '/') + "common/gui/ICraftingContainer", "setRecipeUsed", "(Lnet/minecraft/resources/ResourceLocation;)V") == 1)
                    && transfer.methods.stream().anyMatch(m -> m.name.equals("putIntoInventory")
                            && Arrays.stream(m.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                                    && call.owner.equals("net/minecraft/world/entity/player/Inventory")
                                    && call.name.equals("add")))
                    && hasSophisticatedTransferPorts(transfer);
        } catch (ClassNotFoundException absent) {
            sophisticatedReady = false;
        } catch (IOException | RuntimeException unreadable) {
            sophisticatedReady = false;
            LogUtils.getLogger().warn("FMP Sophisticated adapter disabled: cannot inspect native ports", unreadable);
        }
        return sophisticatedReady;
    }

    private static boolean hasSophisticatedTransferPorts(ClassNode transfer) {
        String storage = "Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;";
        String menu = "Lnet/minecraft/world/inventory/AbstractContainerMenu;";
        String player = "Lnet/minecraft/world/entity/player/Player;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String slot = "Lnet/minecraft/world/inventory/Slot;";
        String list = "Ljava/util/List;";
        String map = "Ljava/util/Map;";
        String id = "Lnet/minecraft/resources/ResourceLocation;";
        String recipeType = "Lnet/minecraft/world/item/crafting/RecipeType;";
        String storageOwner = storage.substring(1, storage.length() - 1);
        String menuOwner = menu.substring(1, menu.length() - 1);
        return calls(transfer, "getSlotWithStack", "(" + storage + "Ljava/lang/Iterable;" + stack + ")" + slot, storageOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "addStack", "(" + storage + "Ljava/util/Collection;" + stack + ")I", storageOwner, "getSlot", "(I)" + slot) == 2
                && calls(transfer, "clearAndPutItemsIntoGrid", "(" + player + id + recipeType + list + menu + map + ")" + list, menuOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "putItemIntoGrid", "(" + menu + map + list + "I)V", menuOwner, "getSlot", "(I)" + slot) == 1
                && calls(transfer, "putIntoInventory", "(" + player + list + storage + list + ")V", "net/minecraft/world/entity/player/Inventory", "add", "(" + stack + ")Z") == 1
                && (calls(transfer, "setItems", "(" + storage + player + id + recipeType + map + list + list + "Z)V", storageOwner, "broadcastChanges", "()V")
                        + calls(transfer, "setItems", "(" + storage + player + id + recipeType + map + list + list + "Z)Z", storageOwner, "broadcastChanges", "()V")) == 1;
    }

    static boolean inspectSophisticatedEmi(String targetClassName) {
        if (!inspectSophisticated()) {
            return false;
        }
        try {
            getClassNode("dev.emi.emi.api.recipe.handler.EmiCraftContext");
            var type = getClassNode(targetClassName);
            String arguments = "(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Z";
            return hasMethod(type, "canCraft", arguments)
                    && hasMethod(type, "craft", arguments)
                    && hasMethod(type, "getInventory", "(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;")
                    && type.fields.stream().anyMatch(f -> f.name.equals("recipeType")
                            && f.desc.equals("Lnet/minecraft/world/item/crafting/RecipeType;"));
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static boolean inspectSophisticatedJei(String targetClassName) {
        if (!inspectSophisticated()) {
            return false;
        }
        try {
            getClassNode("mezz.jei.api.recipe.transfer.IRecipeTransferError");
            return hasMethod(getClassNode(targetClassName), "transferRecipe",
                    "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;");
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    static void recordFxnt(String target, boolean transfer, boolean preview, boolean ready) {
        if (transfer) {
            fxntTransferReady = ready;
        }
        if (preview) {
            if (ready) {
                FXNT_PREVIEW_FAILURES.remove(target);
            } else {
                FXNT_PREVIEW_FAILURES.add(target);
            }
        }
    }

    static void recordEmi(String mixin, boolean relevant, boolean ready) {
        if (!relevant) {
            return;
        }
        if (mixin.endsWith("EmiPacketSourcesMixin")) {
            emiTransferReady = ready;
        } else if (ready) {
            EMI_PREVIEWS.add(mixin);
        } else {
            EMI_PREVIEWS.remove(mixin);
        }
    }

    static boolean hasEmiPort(ClassNode target, String mixin) {
        if (mixin.endsWith("EmiPacketSourcesMixin")) {
            String collect = "(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;Ljava/util/List;Ljava/util/List;Lnet/minecraft/world/item/ItemStack;)I";
            return calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "mayPickup", "(Lnet/minecraft/world/entity/player/Player;)Z") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "getItem", "()Lnet/minecraft/world/item/ItemStack;") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "setByPlayer", "(Lnet/minecraft/world/item/ItemStack;)V") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/inventory/Slot", "onTake", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V") == 1
                    && calls(target, "grabMatching", collect, "net/minecraft/world/item/ItemStack", "setCount", "(I)V") == 2
                    && calls(target, "apply", "(Lnet/minecraft/world/entity/player/Player;)V", "dev/emi/emi/network/FillRecipeC2SPacket", "grabMatching", collect) == 1;
        }
        String name;
        String descriptor;
        if (mixin.endsWith("EmiStandardSourcesMixin")) {
            name = "getInventory";
            descriptor = "(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;";
        } else if (mixin.endsWith("EmiRecipeSourcesMixin")) {
            name = "getStacks";
            descriptor = "(Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;Ldev/emi/emi/api/recipe/EmiRecipe;Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;I)Ljava/util/List;";
            String grid = "(Ldev/emi/emi/api/recipe/EmiRecipe;Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;";
            if (calls(target, name, descriptor, "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getCraftingSlots", grid) != 1
                    || calls(target, "batchesAlreadyPresent", "(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/StandardRecipeHandler;Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)I",
                            "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getCraftingSlots", grid) != 1) {
                return false;
            }
        } else {
            name = "<init>";
            descriptor = "(Lnet/minecraft/world/entity/player/Player;)V";
        }
        return calls(target, name, descriptor, "dev/emi/emi/api/recipe/handler/StandardRecipeHandler", "getInputSources",
                "(Lnet/minecraft/world/inventory/AbstractContainerMenu;)Ljava/util/List;") == 1;
    }

    static boolean hasFxntTransfer(ClassNode target) {
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

    static boolean hasFxntPreview(ClassNode target) {
        String menu = target.name.endsWith("JEIBackpackCraftingTransferHandler")
                ? "net/fxnt/fxntstorage/backpack/client/menu/BackpackMenu"
                : target.name.endsWith("JEIStonecuttingTransferHandler")
                        ? "net/minecraft/world/inventory/StonecutterMenu"
                        : "net/minecraft/world/inventory/CraftingMenu";
        String descriptor = "(L" + menu + ";Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;";
        return calls(target, "transferRecipe", descriptor, "net/minecraft/world/entity/player/Player", "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;") == 1
                && hasOriginSend(target, "transferRecipe", descriptor);
    }

    static boolean hasOriginSend(ClassNode target, String method, String descriptor) {
        return calls(target, method, descriptor, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer",
                "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V") == 1;
    }
}
