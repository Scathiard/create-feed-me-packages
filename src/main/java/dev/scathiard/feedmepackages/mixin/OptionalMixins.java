package dev.scathiard.feedmepackages.mixin;

import static dev.scathiard.feedmepackages.mixin.MixinBytecodeHelper.getClassNode;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin bootstrap plugin that inspects optional third-party mod bytecode before applying any
 * optional Mixin.
 *
 * <p><b>Design Principle (Fail-Closed Bytecode Gate):</b>
 * <ul>
 *   <li>All optional mod classes are inspected via raw ASM {@link ClassNode} structures without
 *       loading game or third-party classes during Mixin bootstrap.</li>
 *   <li>If an optional mod is absent or its method/field/instruction signatures differ from the
 *       verified versions, {@link #shouldApplyMixin(String, String)} returns {@code false} so the
 *       Mixin is not applied at all—preventing {@code MixinApplyError} crashes and leaving native
 *       mod behavior untouched.</li>
 *   <li>Actual shape inspection logic is organized by domain in {@link MixinTransportShapes},
 *       {@link MixinCraftingShapes}, and {@link MixinConsumptionShapes}.</li>
 * </ul>
 */
public final class OptionalMixins implements IMixinConfigPlugin {
    public static final List<String> CRAFTING_TWEAK_MESSAGES = MixinCraftingShapes.CRAFTING_TWEAK_MESSAGES;

    // -------------------------------------------------------------------------
    // Crafting & Recipe Viewer Compatibility Queries
    // -------------------------------------------------------------------------

    public static boolean craftingTweaksCompatible() {
        return MixinCraftingShapes.craftingTweaksCompatible();
    }

    /** A missing optional editor is harmless; a present editor without accounting must see only real items. */
    public static boolean craftingTweaksSafe() {
        return MixinCraftingShapes.craftingTweaksSafe();
    }

    public static boolean craftingTweaksShape(Map<String, ClassNode> nodes) {
        return MixinCraftingShapes.craftingTweaksShape(nodes);
    }

    public static boolean sophisticatedCompatible() {
        return MixinCraftingShapes.sophisticatedCompatible();
    }

    public static boolean fxntEmiOriginCompatible() {
        return MixinCraftingShapes.fxntEmiOriginCompatible();
    }

    public static boolean emiTransferCompatible() {
        return MixinCraftingShapes.emiTransferCompatible();
    }

    public static boolean emiPreviewCompatible() {
        return MixinCraftingShapes.emiPreviewCompatible();
    }

    public static boolean fxntTransferCompatible() {
        return MixinCraftingShapes.fxntTransferCompatible();
    }

    public static Set<String> fxntPreviewFailures() {
        return MixinCraftingShapes.fxntPreviewFailures();
    }

    /** The legacy handler-order bridge conflicts with the 0.2.3+ compatibility boundary. */
    public static boolean emiCompatible() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Logistics & Courier Transport Compatibility Queries
    // -------------------------------------------------------------------------

    public static boolean biotechCompatible() {
        return MixinTransportShapes.biotechCompatible();
    }

    public static String biotechRoot() {
        return MixinTransportShapes.biotechRoot;
    }

    public static boolean phantom100FlightCompatible() { return MixinTransportShapes.phantom100FlightCompatible(); }

    public static boolean factoryCompatible() {
        return MixinTransportShapes.factoryCompatible();
    }

    public static boolean mobileStorageCompatible() {
        return MixinTransportShapes.mobileStorageCompatible();
    }

    public static boolean maidPaperCompatible() {
        return MixinTransportShapes.maidPaperCompatible();
    }

    public static boolean maidBeeCompatible() {
        return MixinTransportShapes.maidBeeCompatible();
    }

    public static boolean phantom102Compatible() {
        return MixinTransportShapes.phantom102Compatible();
    }

    public static boolean phantom102FlightCompatible() {
        return MixinTransportShapes.phantom102FlightCompatible();
    }

    public static boolean phantom102BeltCompatible() {
        return MixinTransportShapes.phantom102BeltCompatible();
    }

    public static boolean phantom102PortReturnCompatible() {
        return MixinTransportShapes.phantom102PortReturnCompatible();
    }

    public static boolean phantom103FlightCompatible() {
        return MixinTransportShapes.phantom103FlightCompatible();
    }

    public static boolean phantom103PortCompatible() {
        return MixinTransportShapes.phantom103PortCompatible();
    }

    public static boolean phantom103BeltCompatible() {
        return MixinTransportShapes.phantom103BeltCompatible();
    }

    static boolean hasStorageHook(ClassNode target) {
        return MixinTransportShapes.hasStorageHook(target);
    }

    // -------------------------------------------------------------------------
    // Material & Ammo Consumption Compatibility Queries
    // -------------------------------------------------------------------------

    public static boolean sbwCompatible() {
        return MixinConsumptionShapes.sbwCompatible();
    }

    public static boolean effortlessCompatible() {
        return MixinConsumptionShapes.effortlessCompatible();
    }

    public static boolean gadgetsCompatible() {
        return MixinConsumptionShapes.gadgetsCompatible();
    }

    public static boolean taczCompatible() {
        return MixinConsumptionShapes.taczCompatible();
    }

    public static boolean taczClientCompatible() {
        return MixinConsumptionShapes.taczClientCompatible();
    }

    public static boolean hasEffortlessPorts(ClassNode sources, ClassNode build) {
        return MixinConsumptionShapes.hasEffortlessPorts(sources, build);
    }

    public static boolean hasGadgetsPorts(ClassNode sources, ClassNode build) {
        return MixinConsumptionShapes.hasGadgetsPorts(sources, build);
    }

    public static boolean hasTaczClientPorts(ClassNode hud, ClassNode shoot) {
        return MixinConsumptionShapes.hasTaczClientPorts(hud, shoot);
    }

    /** Official TaCZ 1.2+: both display caching and firing must reach the same native inventory reader. */
    public static boolean hasTaczCountPorts(ClassNode hud, ClassNode shoot, ClassNode count, ClassNode holder) {
        return MixinConsumptionShapes.hasTaczCountPorts(hud, shoot, count, holder);
    }

    /** All TaCZ ports are checked together: a query-only partial installation must not advertise unspendable ammo. */
    public static boolean hasTaczPorts(ClassNode gun, ClassNode script, ClassNode shoot) {
        return MixinConsumptionShapes.hasTaczPorts(gun, script, shoot);
    }

    // -------------------------------------------------------------------------
    // IMixinConfigPlugin Implementation
    // -------------------------------------------------------------------------

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 1. Transport & Courier Mixins
        if (mixinClassName.endsWith("PaperLegacyAddressMixin")) {
            return MixinTransportShapes.inspectLegacyPaper(targetClassName);
        }
        if (mixinClassName.contains(".Biotech")) {
            return MixinTransportShapes.inspectBiotech() && targetClassName.startsWith(MixinTransportShapes.biotechRoot);
        }
        if (mixinClassName.endsWith("Phantom100FlightMixin")) {
            return MixinTransportShapes.inspectPhantom100Flight(targetClassName);
        }
        if (mixinClassName.contains(".Phantom103") && !MixinTransportShapes.isPhantom103Loaded()) {
            return false;
        }
        if (mixinClassName.endsWith("Phantom103PortReturnMixin") || mixinClassName.endsWith("Phantom103CarrierBeltMixin")) {
            return MixinTransportShapes.inspectPhantom103PortOrBelt(targetClassName, mixinClassName);
        }
        if (mixinClassName.endsWith("Phantom103FlightMixin")) {
            return MixinTransportShapes.inspectPhantom103Flight(targetClassName);
        }
        if (mixinClassName.endsWith("Phantom102SettlementMixin")) {
            return MixinTransportShapes.inspectPhantom102Settlement(targetClassName);
        }
        if (mixinClassName.endsWith("Phantom102FlightMixin")) {
            return MixinTransportShapes.inspectPhantom102Flight(targetClassName);
        }
        if (mixinClassName.endsWith("Phantom102LaunchMixin")) {
            return MixinTransportShapes.inspectPhantom102Launch(targetClassName);
        }
        if (mixinClassName.endsWith("Phantom102PortReturnMixin")) {
            return MixinTransportShapes.inspectPhantom102PortReturn(targetClassName);
        }
        if (mixinClassName.endsWith("Phantom102BeltMixin")) {
            return MixinTransportShapes.inspectPhantom102Belt(targetClassName);
        }
        if (mixinClassName.endsWith("MaidPaperMixin")) {
            return MixinTransportShapes.inspectMaidTransport(false);
        }
        if (mixinClassName.endsWith("MaidBeeTargetMixin")
                || mixinClassName.endsWith("MaidBeePortMixin")
                || mixinClassName.endsWith("MaidBeeDeliveryMixin")) {
            return MixinTransportShapes.inspectMaidTransport(true);
        }
        if (mixinClassName.endsWith("FactoryOrderStampMixin")) {
            return MixinTransportShapes.inspectFactoryOrderStamp(targetClassName);
        }

        // 2. Crafting & Recipe Viewer Mixins
        if (mixinClassName.endsWith("CraftingTweaksEditMixin")) {
            return MixinCraftingShapes.inspectCraftingTweaks();
        }
        if (mixinClassName.endsWith("JeiServerEditMixin") || mixinClassName.endsWith("JeiServerResultEditMixin")) {
            return MixinCraftingShapes.inspectJeiServerEdit(targetClassName, mixinClassName);
        }
        if (mixinClassName.endsWith("JeiSourcesMixin") || mixinClassName.endsWith("JeiContextSourcesMixin")) {
            return MixinCraftingShapes.inspectJeiSources(targetClassName, mixinClassName);
        }
        if (mixinClassName.endsWith("QuarkScreenAllowMixin")) {
            return MixinCraftingShapes.inspectQuarkScreenAllow(targetClassName);
        }
        if (mixinClassName.endsWith("SophisticatedRefillMixin") || mixinClassName.endsWith("SophisticatedSourcesMixin")) {
            return MixinCraftingShapes.inspectSophisticated();
        }
        if (mixinClassName.endsWith("SophisticatedEmiMixin")) {
            return MixinCraftingShapes.inspectSophisticatedEmi(targetClassName);
        }
        if (mixinClassName.endsWith("SophisticatedJeiMixin")) {
            return MixinCraftingShapes.inspectSophisticatedJei(targetClassName);
        }

        // 3. Maid, Building Tool & Weapon Consumption Mixins
        if (mixinClassName.endsWith("SbwAmmoSourcesMixin") || mixinClassName.endsWith("SbwAmmoCountMixin")) {
            return MixinConsumptionShapes.inspectSbw();
        }
        if (mixinClassName.endsWith("MaidMealSourcesMixin")
                || mixinClassName.endsWith("MaidBowSourcesMixin")
                || mixinClassName.endsWith("MaidProjectileSourcesMixin")
                || mixinClassName.endsWith("MaidCrossbowSourcesMixin")) {
            return MaidConsumptionPorts.inspect(targetClassName, mixinClassName);
        }
        if (mixinClassName.endsWith("MaidPickupMixin")) {
            return MixinConsumptionShapes.inspectMaidPickup(targetClassName);
        }
        if (mixinClassName.endsWith("EffortlessSourcesMixin") || mixinClassName.endsWith("EffortlessBuildMixin")) {
            return MixinConsumptionShapes.inspectEffortless();
        }
        if (mixinClassName.endsWith("GadgetSourcesMixin") || mixinClassName.endsWith("GadgetBuildMixin")) {
            return MixinConsumptionShapes.inspectGadgets();
        }
        if (mixinClassName.endsWith("TaczAnimationSourcesMixin")) {
            return MixinConsumptionShapes.inspectTaczAnimation(targetClassName);
        }
        if (mixinClassName.endsWith("TaczHudSourcesMixin")) {
            return MixinConsumptionShapes.inspectTaczClient();
        }
        if (mixinClassName.endsWith("TaczLegacyHudSourcesMixin") || mixinClassName.endsWith("TaczClientShootMixin")) {
            return MixinConsumptionShapes.inspectTaczClient() && !MixinConsumptionShapes.taczSharedCount();
        }
        if (mixinClassName.endsWith("TaczAmmoCountMixin")) {
            return MixinConsumptionShapes.inspectTaczClient() && MixinConsumptionShapes.taczSharedCount();
        }
        if (mixinClassName.endsWith("TaczGunSourcesMixin")
                || mixinClassName.endsWith("TaczScriptSourcesMixin")
                || mixinClassName.endsWith("TaczShootSourcesMixin")) {
            return MixinConsumptionShapes.inspectTacz();
        }

        // 4. Mobile Packages, Package Couriers, Create: Storage (FXNT) & EMI Port Mixins
        boolean receive = mixinClassName.endsWith("MobileReceiveMixin");
        boolean storage = mixinClassName.endsWith("MobileStorageMixin");
        boolean paper = mixinClassName.endsWith("PaperStorageMixin");
        boolean fxnt = mixinClassName.endsWith("FxntTransferMixin");
        boolean fxntJei = mixinClassName.endsWith("FxntJeiMixin");
        boolean fxntEmi = mixinClassName.endsWith("FxntEmiOriginMixin");
        boolean emi = mixinClassName.endsWith("EmiPacketSourcesMixin")
                || mixinClassName.endsWith("EmiStandardSourcesMixin")
                || mixinClassName.endsWith("EmiRecipeSourcesMixin")
                || mixinClassName.endsWith("EmiInventorySourcesMixin");
        if (mixinClassName.endsWith("EmiHandlersMixin")) {
            return false;
        }
        if (!receive && !storage && !paper && !fxnt && !fxntJei && !fxntEmi && !emi) {
            return true;
        }
        try {
            ClassNode target = getClassNode(targetClassName);
            boolean compatible = receive
                    ? target.methods.stream().anyMatch(method -> method.name.equals("sendPackageToPlayer")
                            && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z"))
                    : storage ? hasStorageHook(target)
                    : paper ? MixinTransportShapes.hasPaperStorageHooks(target)
                    : fxnt ? MixinCraftingShapes.hasFxntTransfer(target)
                    : fxntJei ? MixinCraftingShapes.hasFxntPreview(target)
                    : fxntEmi ? MixinCraftingShapes.hasOriginSend(target, "craft", "(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Z")
                    : MixinCraftingShapes.hasEmiPort(target, mixinClassName);
            if (storage) {
                MixinTransportShapes.setMobileStorageReady(compatible);
            }
            if (fxntEmi && compatible) {
                MixinCraftingShapes.recordFxntEmiOrigin(targetClassName);
            }
            if (!compatible) {
                LogUtils.getLogger().warn("FMP optional adapter {} disabled: incompatible method shape", mixinClassName);
            }
            MixinCraftingShapes.recordFxnt(targetClassName, fxnt, fxntJei, compatible);
            MixinCraftingShapes.recordEmi(mixinClassName, emi, compatible);
            return compatible;
        } catch (ClassNotFoundException absent) {
            MixinCraftingShapes.recordFxnt(targetClassName, fxnt, fxntJei, false);
            MixinCraftingShapes.recordEmi(mixinClassName, emi, false);
            return false;
        } catch (IOException unreadable) {
            MixinCraftingShapes.recordFxnt(targetClassName, fxnt, fxntJei, false);
            MixinCraftingShapes.recordEmi(mixinClassName, emi, false);
            LogUtils.getLogger().warn("FMP optional service port disabled: cannot inspect {}", targetClassName, unreadable);
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
