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
    private static Boolean craftingTweaksReady;
    public static final List<String> CRAFTING_TWEAK_MESSAGES=List.of("RotateMessage","BalanceMessage","ClearMessage","TransferStackMessage","RefillLastCraftedMessage","CompressMessage","CraftStackMessage");
    public static boolean craftingTweaksCompatible(){return Boolean.TRUE.equals(craftingTweaksReady);}
    /** A missing optional editor is harmless; a present editor without accounting must see only real items. */
    public static boolean craftingTweaksSafe(){return !net.neoforged.fml.ModList.get().isLoaded("craftingtweaks") || craftingTweaksCompatible();}
    private static synchronized boolean inspectCraftingTweaks() {
        if(craftingTweaksReady!=null)return craftingTweaksReady;
        try {
            var nodes=new java.util.HashMap<String,ClassNode>();
            for(var name:CRAFTING_TWEAK_MESSAGES)nodes.put(name,MixinService.getService().getBytecodeProvider().getClassNode("net.blay09.mods.craftingtweaks.network."+name));
            return craftingTweaksReady=craftingTweaksShape(nodes);
        } catch(ClassNotFoundException | IOException absent){return craftingTweaksReady=false;}
    }
    public static boolean craftingTweaksShape(java.util.Map<String,ClassNode> nodes) {
        for(var name:CRAFTING_TWEAK_MESSAGES) {
            String owner="net/blay09/mods/craftingtweaks/network/"+name;
            var node=nodes.get(name);if(node==null || !owner.equals(node.name))return false;
            var handlers=node.methods.stream().filter(m->m.name.equals("handle")).toList();
            // WrapMethod selects by name: a new overload is also an unsupported shape.
            if(handlers.size()!=1)return false;
            var method=handlers.getFirst();
            if(!method.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;L"+owner+";)V")
                    || (method.access & org.objectweb.asm.Opcodes.ACC_STATIC)==0
                    || (method.access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT|org.objectweb.asm.Opcodes.ACC_NATIVE))!=0)return false;
        }
        return true;
    }
    private static Boolean sbwReady;
    public static boolean sbwCompatible() { return Boolean.TRUE.equals(sbwReady); }
    private static synchronized boolean inspectSbw() {
        if (sbwReady != null) return sbwReady;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            String prefix = "com.atsuishio.superbwarfare.data.gun.";
            String data = "Lcom/atsuishio/superbwarfare/data/gun/GunData;", consumer = "Lcom/atsuishio/superbwarfare/data/gun/AmmoConsumer;";
            String entity = "Lnet/minecraft/world/entity/Entity;", cap = "Lnet/neoforged/neoforge/capabilities/EntityCapability;";
            var gun = provider.getClassNode(prefix + "GunData");
            boolean ready = hasMethod(gun, "countBackupAmmo", "("+entity+")I")
                    && gun.fields.stream().anyMatch(f -> f.name.equals("cachedBackupAmmo") && f.desc.equals("I"));
            for (String type : List.of("ItemAmmoStrategy", "PlayerAmmoStrategy")) {
                var strategy = provider.getClassNode(prefix + "ammo_consumer_strategy." + type);
                ready &= calls(strategy, "consume", "("+data+consumer+entity+"I)I", "net/minecraft/world/entity/Entity", "getCapability", "("+cap+")Ljava/lang/Object;") == 1
                        && calls(strategy, "count", "("+data+consumer+entity+")I", "net/minecraft/world/entity/Entity", "getCapability", "("+cap+")Ljava/lang/Object;") == 1;
            }
            var vehicle=provider.getClassNode("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
            ready &= hasMethod(vehicle,"getGunData","(I)"+data) && hasMethod(vehicle,"getNthEntity","(I)"+entity)
                    && hasMethod(vehicle,"getOrderedPassengers","()Ljava/util/List;") && hasMethod(vehicle,"getAmmoSupplier","()"+entity)
                    && gun.fields.stream().anyMatch(f->f.name.equals("stack") && f.desc.equals("Lnet/minecraft/world/item/ItemStack;"));
            if(!ready) LogUtils.getLogger().warn("FMP Superb Warfare adapter disabled: native ammo/seat signatures changed");
            return sbwReady = ready;
        } catch (ClassNotFoundException | IOException absent) { return sbwReady = false; }
    }
    private static boolean factoryReady;
    private static Boolean sophisticatedReady;
    public static boolean sophisticatedCompatible() { return Boolean.TRUE.equals(sophisticatedReady); }
    private static Boolean maidPaperReady, maidBeeReady, mobileStorageReady;
    public static boolean mobileStorageCompatible() { return Boolean.TRUE.equals(mobileStorageReady); }
    public static boolean maidPaperCompatible() { return Boolean.TRUE.equals(maidPaperReady); }
    public static boolean maidBeeCompatible() { return Boolean.TRUE.equals(maidBeeReady); }
    /**
     * Create Phantom 1.0.2 settlement boundary. Decided from bytecode at bootstrap (like the maid gates above), so
     * a shape mismatch means the Mixin is <b>not applied at all</b> - never "applied with require=0". Stays false
     * until the phantom target is actually probed, i.e. fails closed.
     */
    private static Boolean phantom102Ready;
    private static Boolean phantom103FlightReady;
    public static boolean phantom103FlightCompatible() { return Boolean.TRUE.equals(phantom103FlightReady); }
    private static Boolean phantom103PortReady, phantom103BeltReady;
    public static boolean phantom103PortCompatible() { return Boolean.TRUE.equals(phantom103PortReady); }
    public static boolean phantom103BeltCompatible() { return Boolean.TRUE.equals(phantom103BeltReady); }
    public static boolean phantom102Compatible() { return Boolean.TRUE.equals(phantom102Ready); }
    private static Boolean phantom102FlightReady;
    public static boolean phantom102FlightCompatible() { return Boolean.TRUE.equals(phantom102FlightReady); }
    private static Boolean phantom102BeltReady;
    public static boolean phantom102BeltCompatible() { return Boolean.TRUE.equals(phantom102BeltReady); }
    private static Boolean phantom102PortReturnReady;
    public static boolean phantom102PortReturnCompatible() { return Boolean.TRUE.equals(phantom102PortReturnReady); }
    public static boolean factoryCompatible() { return factoryReady; }
    private static Boolean effortlessReady;
    public static boolean effortlessCompatible() { return Boolean.TRUE.equals(effortlessReady); }
    private static Boolean gadgetsReady;
    public static boolean gadgetsCompatible() { return Boolean.TRUE.equals(gadgetsReady); }
    private static Boolean taczReady;
    private static Boolean taczClientReady;
    private static boolean taczSharedCount;
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
    private static Boolean biotechReady;
    public static boolean biotechCompatible() { return Boolean.TRUE.equals(biotechReady); }
    private static synchronized boolean inspectBiotech() {
        if (biotechReady != null) return biotechReady;
        biotechReady = false;
        if (net.neoforged.fml.loading.LoadingModList.get().getMods().stream().noneMatch(m ->
                m.getModId().equals("create_biotech") && m.getVersion().toString().equals("1.3.1"))) return false;
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            String root = "com.nobodiiiii.createbiotech.content.allay.logistics.courier.";
            var task = provider.getClassNode(root + "AllayCourierTask");
            var dispatch = provider.getClassNode(root + "AllayCourierDispatchService");
            var helper = provider.getClassNode(root + "AllayCourierHelper");
            String server = "Lnet/minecraft/server/MinecraftServer;", player = "Lnet/minecraft/server/level/ServerPlayer;";
            String level = "Lnet/minecraft/server/level/ServerLevel;", vec = "Lnet/minecraft/world/phys/Vec3;";
            String port = "Lcom/nobodiiiii/createbiotech/content/allay/block/allayport/AllayPortBlockEntity;";
            String registry = "Lnet/minecraft/core/HolderLookup$Provider;", tag = "Lnet/minecraft/nbt/CompoundTag;";
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
                    && List.of("canReceiveDelivery", "deliverPackage", "deliverPackageOnly").stream().allMatch(name ->
                        hasMethod(helper, name, "(" + player + "Lnet/minecraft/world/item/ItemStack;)Z"))
                    && task.methods.stream().filter(m -> m.name.equals("doFinishDeliveryAt")).anyMatch(m ->
                        java.util.Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                            && call.owner.equals(owner + "AllayCourierDeliveryService") && call.name.equals("finishDelivery")).count() == 1);
        } catch (ClassNotFoundException | IOException | RuntimeException failed) {
            LogUtils.getLogger().warn("FMP Biotech ports unavailable", failed);
        }
        return biotechReady;
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".Biotech")) return inspectBiotech();
        if (mixinClassName.contains(".Phantom103") && net.neoforged.fml.loading.LoadingModList.get().getMods().stream()
                .noneMatch(mod -> mod.getModId().equals("createphantom") && mod.getVersion().toString().equals("1.0.3"))) return false;
        if (mixinClassName.endsWith("Phantom103PortReturnMixin") || mixinClassName.endsWith("Phantom103CarrierBeltMixin")) {
            try {
                var node=MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                if(mixinClassName.endsWith("Phantom103PortReturnMixin")) {
                    return phantom103PortReady=hasMethod(node,"tick","()V") && calls(node,"tick","()V",
                            "com/yision/phantom/logistics/courier/AirCourierDeliveryService","targetState",
                            "(Lnet/minecraft/server/MinecraftServer;Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService$TargetState;")==1;
                }
                return phantom103BeltReady=hasMethod(node,"launchReturningCarrierToPlayer",
                        "(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;)Z");
            } catch(ClassNotFoundException | IOException absent){return false;}
        }
        if (mixinClassName.endsWith("Phantom103FlightMixin")) {
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                String server = "Lnet/minecraft/server/MinecraftServer;";
                String task = "Lcom/yision/phantom/logistics/courier/AirCourierTask";
                phantom103FlightReady = targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierTask")
                        && hasMethod(node,"prepareFlightTarget","("+server+")"+task+"$FlightTarget;")
                        && hasMethod(node,"prepareForDispatch","("+server+")V")
                        && hasMethod(node,"doFinishDelivery","("+server+"Lnet/minecraft/server/level/ServerLevel;)V")
                        && hasMethod(node,"startCarrierReturn","("+server+")V")
                        && hasMethod(node,"resolveFlightTarget","("+server+")"+task+"$FlightTarget;")
                        && hasMethod(node,"save","(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
                        && hasMethod(node,"load","(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)"+task+";");
                return phantom103FlightReady;
            } catch (ClassNotFoundException | IOException absent) { phantom103FlightReady=false;return false; }
        }
        if (mixinClassName.endsWith("CraftingTweaksEditMixin")) return inspectCraftingTweaks();
        if (mixinClassName.endsWith("SbwAmmoSourcesMixin") || mixinClassName.endsWith("SbwAmmoCountMixin")) return inspectSbw();
        if (mixinClassName.endsWith("Phantom102SettlementMixin")) {
            // Shape mismatch => this Mixin is not applied at all (no require=0, no behaviour change).
            phantom102Ready = phantom102SettlementTarget(targetClassName);
            return phantom102Ready;
        }
        if (mixinClassName.endsWith("Phantom102FlightMixin")) {
            // Same rule for the flight side: an unrecognised task shape is never injected into.
            phantom102FlightReady = phantom102FlightTarget(targetClassName);
            return phantom102FlightReady;
        }
        if (mixinClassName.endsWith("Phantom102LaunchMixin")) {
            // Same rule for the launch side: the resolver must be exactly as measured, or not applied at all.
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                return targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierDispatchService")
                        && hasMethod(node, "resolvePackageTarget",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;"
                          + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;"
                          + "Lnet/minecraft/core/BlockPos;)Lcom/yision/phantom/logistics/courier/AirCourierTarget;");
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("Phantom102PortReturnMixin")) {
            // The port's carrier departure. We answer it ourselves for a maid, so the exact method must exist with the
            // measured descriptor; anything else means not applied at all.
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                phantom102PortReturnReady = targetClassName.equals("com.yision.phantom.block.phantomport.PhantomPortReturnQueue")
                        && hasMethod(node, "tryQueueStoredReturnCarrierToPlayer", "(Ljava/util/UUID;)Z");
                return phantom102PortReturnReady;
            } catch (ClassNotFoundException | IOException unavailable) { phantom102PortReturnReady = false; return false; }
        }
        if (mixinClassName.endsWith("Phantom102BeltMixin")) {
            // The belt launch gate. We reproduce its happy path ourselves, so the whole shape it depends on must be
            // exactly as measured: the gate method, the 13-argument task factory it ultimately calls, and the launch
            // geometry helper. Any mismatch means not applied at all (no require=0, native behaviour untouched).
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
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                phantom102BeltReady = targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierBeltHooks")
                        && hasMethod(node, "launchPackageCourier", gate)
                        && calls(node, "launchPackageCourier", gate,
                                "com/yision/phantom/logistics/courier/AirCourierTask", "forPackageToPlayer", factory) >= 1
                        && calls(node, "launchPackageCourier", gate,
                                "com/yision/phantom/logistics/courier/AirCourierBeltHooks", "getLaunchGeometry", geometry) >= 1;
                return phantom102BeltReady;
            } catch (ClassNotFoundException | IOException unavailable) { phantom102BeltReady = false; return false; }
        }
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
        if(mixinClassName.endsWith("SophisticatedEmiMixin")) {
            if(!inspectSophisticated())return false;
            try {
                var provider=MixinService.getService().getBytecodeProvider();provider.getClassNode("dev.emi.emi.api.recipe.handler.EmiCraftContext");
                var type=provider.getClassNode(targetClassName);
                String arguments="(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Z";
                return hasMethod(type,"canCraft",arguments) && hasMethod(type,"craft",arguments)
                        && hasMethod(type,"getInventory","(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;")
                        && type.fields.stream().anyMatch(f->f.name.equals("recipeType") && f.desc.equals("Lnet/minecraft/world/item/crafting/RecipeType;"));
            } catch(ClassNotFoundException | IOException unavailable) {return false;}
        }
        if (mixinClassName.endsWith("SophisticatedJeiMixin")) {
            if (!inspectSophisticated()) return false;
            try {
                var provider = MixinService.getService().getBytecodeProvider();
                provider.getClassNode("mezz.jei.api.recipe.transfer.IRecipeTransferError");
                return hasMethod(provider.getClassNode(targetClassName), "transferRecipe",
                        "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;");
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("MaidMealSourcesMixin") || mixinClassName.endsWith("MaidBowSourcesMixin")
                || mixinClassName.endsWith("MaidProjectileSourcesMixin") || mixinClassName.endsWith("MaidCrossbowSourcesMixin"))
            return MaidConsumptionPorts.inspect(targetClassName, mixinClassName);
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
        if (mixinClassName.endsWith("TaczAnimationSourcesMixin")) {
            if (!inspectTaczClient()) return false;
            try {
                var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                return hasMethod(node, "hasAmmoToConsume", "()Z")
                        && node.fields.stream().anyMatch(f -> f.name.equals("currentGunItem") && f.desc.equals("Lnet/minecraft/world/item/ItemStack;"));
            } catch (ClassNotFoundException | IOException unavailable) { return false; }
        }
        if (mixinClassName.endsWith("TaczHudSourcesMixin")) return inspectTaczClient();
        if (mixinClassName.endsWith("TaczLegacyHudSourcesMixin") || mixinClassName.endsWith("TaczClientShootMixin"))
            return inspectTaczClient() && !taczSharedCount;
        if (mixinClassName.endsWith("TaczAmmoCountMixin")) return inspectTaczClient() && taczSharedCount;
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
            if (storage) mobileStorageReady = compatible;
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
            provider.getClassNode("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase");
            String root = "net.p3pp3rf1y.sophisticatedcore.";
            var wrapper = provider.getClassNode(root + "upgrades.crafting.CraftingUpgradeWrapper");
            var transfer = provider.getClassNode(root + "compat.recipeviewers.common.CraftingContainerRecipeTransferHandlerServer");
            sophisticatedReady = hasMethod(wrapper, "extractFromStorageOrPlayer", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z")
                    && (hasMethod(transfer, "setItemsWithStacks", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeType;Ljava/util/List;Ljava/util/List;Ljava/util/List;Z)V")
                        || hasMethod(transfer, "setItemsWithStacks", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeType;Ljava/util/List;Ljava/util/List;Ljava/util/List;Z)Z"))
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/inventory/Slot;")
                    && hasMethod(transfer, "getSlotWithStack", "(Lnet/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase;Ljava/lang/Iterable;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/inventory/Slot;")
                    && transfer.methods.stream().filter(m -> m.name.startsWith("lambda$clearAndPutItemsIntoGrid$")).count() == 1
                    && transfer.methods.stream().anyMatch(m -> Set.of("lambda$clearAndPutItemsIntoGrid$0", "lambda$clearAndPutItemsIntoGrid$2").contains(m.name)
                        && m.desc.equals("(Lnet/minecraft/resources/ResourceLocation;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerBase;)V")
                        && calls(transfer, m.name, m.desc, root.replace('.', '/') + "common/gui/ICraftingContainer", "setRecipeUsed", "(Lnet/minecraft/resources/ResourceLocation;)V") == 1)
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
                && (calls(transfer, "setItems", "(" + storage + player + id + recipeType + map + list + list + "Z)V", storageOwner, "broadcastChanges", "()V")
                    + calls(transfer, "setItems", "(" + storage + player + id + recipeType + map + list + list + "Z)Z", storageOwner, "broadcastChanges", "()V")) == 1;
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
                ready &= hasStorageHook(robo) && hasMethod(robo, "updateTarget", "()V")
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
            var hud = provider.getClassNode("com.tacz.guns.client.gui.overlay.GunHudOverlay");
            var shoot = provider.getClassNode("com.tacz.guns.client.gameplay.LocalPlayerShoot");
            ClassNode count = null;
            try { count = provider.getClassNode("com.tacz.guns.client.gameplay.ClientAmmoCount"); }
            catch (ClassNotFoundException legacy) { /* The unofficial port has no shared reader. */ }
            taczSharedCount = count != null;
            taczClientReady = inspectTacz() && (taczSharedCount
                    ? hasTaczCountPorts(hud, shoot, count, provider.getClassNode("com.tacz.guns.client.gameplay.LocalPlayerDataHolder"))
                    : hasTaczClientPorts(hud, shoot));
            LogUtils.getLogger().info("FMP TaCZ client quantity ports shapeReady={} sharedCount={}", taczClientReady, taczSharedCount);
        } catch (ClassNotFoundException absent) { taczClientReady = false; }
        catch (IOException | RuntimeException unreadable) {
            taczClientReady = false; LogUtils.getLogger().warn("FMP TaCZ client quantity ports disabled: cannot inspect bytecode", unreadable);
        }
        return taczClientReady;
    }
    public static boolean hasTaczClientPorts(ClassNode hud, ClassNode shoot) {
        String stack = "Lnet/minecraft/world/item/ItemStack;", data = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;";
        String gun = "Lcom/tacz/guns/api/item/IGun;", display = "Lcom/tacz/guns/client/resource/GunDisplayInstance;";
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
    /** Official 1.2: both display caching and firing must reach the same native inventory reader. */
    public static boolean hasTaczCountPorts(ClassNode hud, ClassNode shoot, ClassNode count, ClassNode holder) {
        String stack = "Lnet/minecraft/world/item/ItemStack;", player = "Lnet/minecraft/client/player/LocalPlayer;";
        String gun = "Lcom/tacz/guns/api/item/IGun;", data = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;";
        String display = "Lcom/tacz/guns/client/resource/GunDisplayInstance;", read = "(" + player + stack + gun + ")I";
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
    static boolean hasStorageHook(ClassNode target) {
        String read = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lde/theidler/create_mobile_packages/robo/VirtualRobo;";
        if (!hasMethod(target, "serializeNBT", "()Lnet/minecraft/nbt/CompoundTag;")
                || !hasMethod(target, "setRemoved", "(Lnet/minecraft/server/level/ServerLevel;)V")
                || !hasCall(target, "deserializeNBT", read, "parse")) return false;
        for (var method : target.methods) if (method.name.equals("deserializeNBT") && method.desc.equals(read)) {
            if ((method.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) return false;
            int itemCodecs = 0, otherCodecs = 0;
            for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.desc.equals("Lcom/mojang/serialization/Codec;")) {
                if (field.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC && field.owner.equals("net/minecraft/world/item/ItemStack")
                        && field.name.equals("CODEC")) itemCodecs++; else otherCodecs++;
            }
            return itemCodecs == 1 && otherCodecs == 0;
        }
        return false;
    }
    private static boolean hasPaperStorageHooks(ClassNode target) {
        String arguments = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)";
        return hasCall(target, "<init>", arguments + "V", "parse")
                && hasCall(target, "save", arguments + "Lnet/minecraft/nbt/CompoundTag;", "encodeStart");
    }
    /**
     * Create Phantom 1.0.2 settlement boundary (T2-b step 2). Measured shape only: the injection point
     * {@code canReceivePackageTarget} must exist with its exact descriptor and must still resolve real players
     * through {@code PlayerList}. Any mismatch returns false, so the Mixin is never applied - the player branch
     * stays byte-identical.
     */
    private static boolean phantom102SettlementTarget(String targetClassName) {
        if (!targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierDispatchService")) return false;
        String descriptor = "(Lnet/minecraft/server/level/ServerLevel;Lcom/yision/phantom/logistics/courier/AirCourierTarget;Lnet/minecraft/world/item/ItemStack;)Z";
        try {
            var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            return hasMethod(node, "canReceivePackageTarget", descriptor)
                    && calls(node, "canReceivePackageTarget", descriptor,
                            "net/minecraft/server/players/PlayerList", "getPlayer",
                            "(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;") >= 1;
        } catch (ClassNotFoundException | IOException unavailable) { return false; }
    }
    /**
     * Create Phantom 1.0.2 flight boundary (T4). Measured shape only: the four methods this feature injects into
     * must exist with their exact descriptors. Any mismatch returns false, so the Mixin is never applied and a
     * player task keeps flying exactly as the native code intends.
     */
    private static boolean phantom102FlightTarget(String targetClassName) {
        if (!targetClassName.equals("com.yision.phantom.logistics.courier.AirCourierTask")) return false;
        String flightTarget = "Lcom/yision/phantom/logistics/courier/AirCourierTask$FlightTarget;";
        try {
            var node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            return hasMethod(node, "resolveFlightTarget",
                        "(Lnet/minecraft/server/MinecraftServer;)" + flightTarget)
                    && hasMethod(node, "isDestinationAvailable", "(" + flightTarget + ")Z")
                    && hasMethod(node, "startCarrierReturn", "(Lnet/minecraft/server/MinecraftServer;)V")
                    && hasMethod(node, "resetForReturn", "(Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;)V")
                    && hasMethod(node, "tickLanding",
                        "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;" + flightTarget + ")V")
                    && hasMethod(node, "doFinishDeliveryAt",
                        "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;)V")
                    && hasMethod(node, "save", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
                    && hasMethod(node, "load", "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lcom/yision/phantom/logistics/courier/AirCourierTask;");
        } catch (ClassNotFoundException | IOException unavailable) { return false; }
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
