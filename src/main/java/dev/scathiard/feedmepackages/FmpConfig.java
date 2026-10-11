package dev.scathiard.feedmepackages;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Optional adapter switches; never changes native mod behavior or declares a loader dependency. */
public final class FmpConfig {
    private FmpConfig() {}
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.BooleanValue TACZ, WAND, GADGETS, EFFORTLESS, JEI, EMI, FXNT, SOPHISTICATED, MAID, FACTORY;
    private record Enabled(boolean tacz, boolean wand, boolean gadgets, boolean effortless, boolean jei, boolean emi, boolean fxnt, boolean sophisticated, boolean maid, boolean factory) {}
    private static final Enabled DEFAULTS = new Enabled(true, true, true, true, true, true, true, true, true, true);
    private static volatile Enabled enabled = DEFAULTS;
    static {
        var b = new ModConfigSpec.Builder().push("compatibility");
        TACZ = b.comment("Supply cached loose ammunition to native TaCZ reload and inventory-fed shooting.").define("tacz_ammo", true);
        WAND = b.comment("Supply the worn cache through Construction Wand KOTS native container API.").define("construction_wand", true);
        GADGETS = b.comment("Supply cached blocks to Building Gadgets 2 with native placement payment verification.").define("building_gadgets", true);
        EFFORTLESS = b.comment("Supply plain cached blocks to Effortless Building native inventory requests.").define("effortless_building", true);
        JEI = b.comment("Supply cache materials to native JEI transfers.").define("jei_crafting", true);
        EMI = b.comment("Supply cache materials to native EMI transfers.").define("emi_crafting", true);
        FXNT = b.comment("Add cache sources to Create: Storage native recipe transfers.").define("fxnt_materials", true);
        SOPHISTICATED = b.comment("Add cache sources to Sophisticated Backpacks crafting upgrades.").define("sophisticated_backpacks", true);
        MAID = b.comment("Allow ordinary pendant supplies and direct replenishment for tamed Touhou Little Maids.").define("touhou_little_maid", true);
        FACTORY = b.comment("Use Factory Logistics native ordinary-item dispatch when it replaces Create logistics.").define("factory_logistics", true);
        b.pop(); SERVER_SPEC = b.build();
    }
    public static boolean taczAmmoEnabled() { return enabled.tacz; }
    public static boolean constructionWandEnabled() { return enabled.wand; }
    public static boolean gadgetsEnabled() { return enabled.gadgets; }
    public static boolean effortlessEnabled() { return enabled.effortless; }
    public static boolean jeiCraftingEnabled() { return enabled.jei; }
    public static boolean emiCraftingEnabled() { return enabled.emi; }
    public static boolean fxntMaterialsEnabled() { return enabled.fxnt; }
    public static boolean sophisticatedEnabled() { return enabled.sophisticated; }
    public static boolean factoryLogisticsEnabled() { return enabled.factory; }
    public static boolean maidEnabled() { return enabled.maid; }
    public static void register(ModContainer container, IEventBus bus) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        bus.addListener((ModConfigEvent.Loading event) -> refresh(event.getConfig()));
        bus.addListener((ModConfigEvent.Reloading event) -> refresh(event.getConfig()));
        bus.addListener((ModConfigEvent.Unloading event) -> { if (event.getConfig().getSpec() == SERVER_SPEC) enabled = DEFAULTS; });
    }
    private static void refresh(ModConfig config) { if (config.getSpec() == SERVER_SPEC) refreshServer(); }
    public static void refreshServer() {
        if (!SERVER_SPEC.isLoaded()) { enabled = DEFAULTS; return; }
        try { enabled = new Enabled(TACZ.get(), WAND.get(), GADGETS.get(), EFFORTLESS.get(), JEI.get(), EMI.get(), FXNT.get(), SOPHISTICATED.get(), MAID.get(), FACTORY.get()); }
        catch (RuntimeException invalid) { enabled = DEFAULTS; FeedMePackages.LOGGER.error("FMP configuration rejected; using complete defaults", invalid); }
    }
}
