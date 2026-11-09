package dev.scathiard.feedmepackages;

import dev.scathiard.feedmepackages.domain.CacheCapacity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-authoritative configuration for Create: Feed Me Packages ({@code create_feed_me_packages-server.toml}).
 *
 * <p>Controls three categories:
 * <ol>
 *   <li>{@code [compatibility]}: Per-mod integration switches (default {@code true}).</li>
 *   <li>{@code [cache]}: Slot count ({@code 1..128}) and group capacity ({@code 1..268435}) for Levels 1–5.</li>
 *   <li>{@code [content]}: Item/fluid admission deny &amp; allow lists ({@code namespace:path} or {@code #namespace:tag}).</li>
 * </ol>
 */
public final class FmpConfig {
    private FmpConfig() {}

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.BooleanValue TACZ;
    public static final ModConfigSpec.BooleanValue WAND;
    public static final ModConfigSpec.BooleanValue GADGETS;
    public static final ModConfigSpec.BooleanValue EFFORTLESS;
    public static final ModConfigSpec.BooleanValue JEI;
    public static final ModConfigSpec.BooleanValue EMI;
    public static final ModConfigSpec.BooleanValue FXNT;
    public static final ModConfigSpec.BooleanValue SOPHISTICATED;
    public static final ModConfigSpec.BooleanValue MAID;
    public static final ModConfigSpec.BooleanValue FACTORY;
    public static final ModConfigSpec.BooleanValue SBW;

    private record Enabled(
            boolean tacz,
            boolean wand,
            boolean gadgets,
            boolean effortless,
            boolean jei,
            boolean emi,
            boolean fxnt,
            boolean sophisticated,
            boolean maid,
            boolean factory,
            boolean sbw) {}

    private static final Enabled DEFAULTS = new Enabled(
            true, true, true, true, true, true, true, true, true, true, true);
    private static volatile Enabled enabled = DEFAULTS;

    public static final List<ModConfigSpec.IntValue> SLOTS = new ArrayList<>();
    public static final List<ModConfigSpec.IntValue> GROUPS = new ArrayList<>();
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DENIED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED;

    public record Rules(List<CacheCapacity> levels, Set<String> denied, Set<String> allowed) {
        public Rules {
            levels = List.copyOf(levels);
            denied = Set.copyOf(denied);
            allowed = Set.copyOf(allowed);
            if (levels.size() != 5) {
                throw new IllegalArgumentException("Five levels required");
            }
        }

        public CacheCapacity capacity(int level) {
            return levels.get(level - 1);
        }
    }

    private static Rules defaults() {
        return new Rules(
                IntStream.rangeClosed(1, 5).mapToObj(CacheCapacity::defaults).toList(),
                Set.of(),
                Set.of());
    }

    private static volatile Rules rules = defaults();

    public static Rules rules() {
        return rules;
    }

    private static boolean contentId(Object value) {
        if (!(value instanceof String text) || text.length() > 256) {
            return false;
        }
        String location = text.startsWith("#") ? text.substring(1) : text;
        return ResourceLocation.tryParse(location) != null;
    }

    static {
        var b = new ModConfigSpec.Builder().push("compatibility");
        TACZ = b.comment("Supply cached loose ammunition to native TaCZ reload and inventory-fed shooting.")
                .define("tacz_ammo", true);
        WAND = b.comment("Supply the worn cache through Construction Wand KOTS native container API.")
                .define("construction_wand", true);
        GADGETS = b.comment("Supply cached blocks to Building Gadgets 2 with native placement payment verification.")
                .define("building_gadgets", true);
        EFFORTLESS = b.comment("Supply plain cached blocks to Effortless Building native inventory requests.")
                .define("effortless_building", true);
        JEI = b.comment("Supply cache materials to native JEI transfers.")
                .define("jei_crafting", true);
        EMI = b.comment("Supply cache materials to native EMI transfers.")
                .define("emi_crafting", true);
        FXNT = b.comment("Add cache sources to Create: Storage native recipe transfers.")
                .define("fxnt_materials", true);
        SOPHISTICATED = b.comment("Add cache sources to Sophisticated Backpacks and Storage crafting upgrades. Legacy setting name is retained.")
                .define("sophisticated_backpacks", true);
        MAID = b.comment("Allow ordinary pendant supplies and direct replenishment for tamed Touhou Little Maids.")
                .define("touhou_little_maid", true);
        FACTORY = b.comment("Use Factory Logistics native ordinary-item dispatch when it replaces Create logistics.")
                .define("factory_logistics", true);
        SBW = b.comment("Supply ordinary cached ammunition to native held Superb Warfare guns and the current vehicle weapon seat only.")
                .define("superb_warfare_ammo", true);

        b.pop().push("cache");
        for (int level = 1; level <= 5; level++) {
            var value = CacheCapacity.defaults(level);
            SLOTS.add(b.defineInRange("level_" + level + "_slots", value.slots(), 1, CacheCapacity.MAX_SLOTS));
            GROUPS.add(b.comment("Native item stacks or 8000 mB groups; bounded to avoid integer overflow for either kind.")
                    .defineInRange("level_" + level + "_groups", value.groups(), 1, CacheCapacity.MAX_GROUPS));
        }

        b.pop().push("content");
        DENIED = b.comment("Item/fluid IDs or #tags denying new storage and requests. Existing stock remains extractable.")
                .defineListAllowEmpty("denied_items", List.<String>of(), () -> "minecraft:dragon_egg", FmpConfig::contentId);
        ALLOWED = b.comment("Overrides configurable deny lists/tags only; never item identity, containment, or permission checks.")
                .defineListAllowEmpty("allowed_items", List.<String>of(), () -> "minecraft:dragon_egg", FmpConfig::contentId);
        b.pop();
        SERVER_SPEC = b.build();
    }

    public static boolean taczAmmoEnabled() {
        return enabled.tacz;
    }

    public static boolean constructionWandEnabled() {
        return enabled.wand;
    }

    public static boolean gadgetsEnabled() {
        return enabled.gadgets;
    }

    public static boolean effortlessEnabled() {
        return enabled.effortless;
    }

    public static boolean jeiCraftingEnabled() {
        return enabled.jei;
    }

    public static boolean emiCraftingEnabled() {
        return enabled.emi;
    }

    public static boolean fxntMaterialsEnabled() {
        return enabled.fxnt;
    }

    public static boolean sophisticatedEnabled() {
        return enabled.sophisticated;
    }

    public static boolean factoryLogisticsEnabled() {
        return enabled.factory;
    }

    public static boolean sbwEnabled() {
        return enabled.sbw;
    }

    public static boolean maidEnabled() {
        return enabled.maid;
    }

    public static void register(ModContainer container, IEventBus bus) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        bus.addListener((ModConfigEvent.Loading event) -> refresh(event.getConfig()));
        bus.addListener((ModConfigEvent.Reloading event) -> refresh(event.getConfig()));
        bus.addListener((ModConfigEvent.Unloading event) -> {
            if (event.getConfig().getSpec() == SERVER_SPEC) {
                enabled = DEFAULTS;
                rules = defaults();
            }
        });
    }

    private static void refresh(ModConfig config) {
        if (config.getSpec() == SERVER_SPEC) {
            refreshServer();
        }
    }

    public static void refreshServer() {
        if (!SERVER_SPEC.isLoaded()) {
            enabled = DEFAULTS;
            rules = defaults();
            return;
        }
        try {
            var levels = new ArrayList<CacheCapacity>();
            for (int i = 0; i < 5; i++) {
                levels.add(new CacheCapacity(SLOTS.get(i).get(), GROUPS.get(i).get()));
            }
            var next = new Rules(levels, new HashSet<>(DENIED.get()), new HashSet<>(ALLOWED.get()));
            enabled = new Enabled(
                    TACZ.get(),
                    WAND.get(),
                    GADGETS.get(),
                    EFFORTLESS.get(),
                    JEI.get(),
                    EMI.get(),
                    FXNT.get(),
                    SOPHISTICATED.get(),
                    MAID.get(),
                    FACTORY.get(),
                    SBW.get());
            rules = next;
        } catch (RuntimeException invalid) {
            enabled = DEFAULTS;
            rules = defaults();
            FeedMePackages.LOGGER.error("FMP configuration rejected; using complete defaults", invalid);
        }
    }
}
