package dev.scathiard.feedmepackages.compat.jei;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED LINE (user decision 2026-09-17): this mod does not take part in JEI recipe transfer at all.
 *
 * <p>Two rounds of the same decision are pinned here: (1) hijacking JEI's "+" is forbidden - JEI is reached
 * through its official plugin API only, and (2) there must be no "do JEI's job for it" layer either - no
 * recipe transfer handler, no preview policy, no action-bar authoring on JEI's behalf. JEI marks missing
 * ingredients red and decides its own "+"; our cache is reached through our own logistics panel and the
 * vanilla recipe book. These assertions read the shipped resources and the compiled plugin, so either layer
 * coming back fails the build.
 */
// Ordinary 0.3 restores guarded native sources, not the removed handler takeover.
class JeiIntegrationIsApiOnlyTest {
    private static String resource(String path) {
        try (InputStream in = JeiIntegrationIsApiOnlyTest.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return null;
        }
    }
    private static String classBytes(String path) {
        try (InputStream in = JeiIntegrationIsApiOnlyTest.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (Exception failure) {
            return null;
        }
    }
    private static List<String> matches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    @Test void theModCarriesOnlyItsOwnMixinConfigs() {
        String toml = resource("/META-INF/neoforge.mods.toml");
        assertNotNull(toml, "neoforge.mods.toml must be on the classpath");
        assertEquals(List.of("create_feed_me_packages.mixins.json"),
                matches(toml, "config\\s*=\\s*\"([^\"]+)\""), "one guarded configuration; the superseded source wrapper must not also load");
        assertFalse(toml.contains("mezz.jei"), "no mixin config may point at JEI");
        assertFalse(toml.contains("fmp_jei_takeover"), "the takeover config was removed by user decision");
    }

    @Test void createStorageUsesOneGuardedNativeSourceAdapter() {
        String config = resource("/create_feed_me_packages.mixins.json");
        assertNotNull(config);
        assertTrue(config.contains("dev.scathiard.feedmepackages.mixin.OptionalMixins"));
        assertTrue(config.contains("FxntTransferMixin") && config.contains("FxntJeiMixin"));
        assertFalse(resource("/META-INF/neoforge.mods.toml").contains("fmp_fxntstorage_compat"),
                "Old eager wrapper and transactional native sources cannot be active together");
        for (String mixin : List.of("FxntTransferMixin", "FxntJeiMixin")) {
            String bytes = classBytes("/dev/scathiard/feedmepackages/mixin/" + mixin + ".class");
            assertNotNull(bytes);
            assertTrue(bytes.contains("Pseudo"));
            assertTrue(bytes.contains("net.fxnt.fxntstorage"));
        }
        assertNull(resource("/net/fxnt/fxntstorage/backpack/main/IBackpackContainer.class"));
    }
    @Test void theRemovedTakeoverLayerStaysGone() {
        assertNull(resource("/fmp_jei_takeover.mixins.json"), "the JEI takeover config must not ship");
        assertFalse(resource("/META-INF/neoforge.mods.toml").contains("fmp_jei_takeover"), "no takeover mixin config may be referenced");
        assertNull(resource("/dev/scathiard/feedmepackages/compat/jei/takeover/TakeoverRecipeTransferManagerMixin.class"), "no takeover mixin may be compiled");
    }

    @Test void theRemovedJeiHelperLayerStaysGone() {
        for (String gone : List.of("/dev/scathiard/feedmepackages/compat/jei/FmpRecipeTransfer.class",
                "/dev/scathiard/feedmepackages/compat/jei/PreviewPolicy.class",
                "/dev/scathiard/feedmepackages/client/ClientNotice.class",
                "/dev/scathiard/feedmepackages/client/NoticeText.class")) {
            assertNull(resource(gone), gone + " must stay gone");
        }
        String plugin = classBytes("/dev/scathiard/feedmepackages/compat/jei/FmpJeiPlugin.class");
        assertNotNull(plugin);
        assertFalse(plugin.contains("registerRecipeTransferHandlers"), "the plugin must not register transfer handlers");
        assertFalse(plugin.contains("mezz/jei/api/recipe/transfer"), "the plugin must not touch JEI's transfer API");
    }

    @Test void theSentencesWrittenForJeiStayGone() {
        for (String lang : List.of("/assets/create_feed_me_packages/lang/zh_cn.json", "/assets/create_feed_me_packages/lang/en_us.json")) {
            String text = resource(lang);
            assertNotNull(text, lang);
            assertFalse(text.contains("missing_entry"), lang);
            assertFalse(text.contains("missing_detail"), lang);
            assertFalse(text.contains("missing_scope"), lang);
            assertTrue(text.contains("result.panel_not_ready"), "the panel's own messages must stay: " + lang);
        }
    }

    @Test void theSeamIsTheVanillaInventoryAndNothingElse() {
        // F-6: the one seam is a subclass of the vanilla Inventory the call carries; the old handler seam is gone.
        assertNotNull(resource("/dev/scathiard/feedmepackages/compat/fxntstorage/CachePresentingInventory.class"), "the Inventory seam must ship");
        assertNull(resource("/dev/scathiard/feedmepackages/compat/fxntstorage/CachePresentingHandler.class"), "the old handler seam must be gone");
        assertNull(resource("/net/fxnt/fxntstorage/backpack/main/IBackpackContainer.class"), "no third-party classes may be shipped");
    }
    @Test void theResyncNeverRewritesAnotherContainer() {
        String resync = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntResync.class");
        assertNotNull(resync, "the resync helper must be compiled");
        assertFalse(resync.contains("setStackInSlot"), "the re-sync must never write any slot");
        assertFalse(resync.contains("curios"), "F-8 revert: the Curios branch must be gone from our code");
        assertFalse(resync.contains("CuriosApi"), "F-8 revert: CuriosApi must not be referenced");
        assertTrue(resync.contains("broadcastChanges"), "the menu broadcast must stay (F-7 behaviour)");
    }
    @Test void theContainerProbeIsGoneForGood() {
        // F-11: F-10's "align their cached container" is void - the user's run got WORSE with it.
        assertNull(classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntContainerProbe.class"),
                "the F-10 container probe must not exist any more");
    }
    @Test void ourCompatClassesNeverReloadOrRebuildTheirState() {
        // Gate (F-11): the only contact left with the other mod is pure reading. Their state is never reloaded.
        List<String> forbidden = List.of("loadItemsFromStack", "getOrCreateWornBackpack", "invalidateWornBackpack",
                "setStackInSlot", "getItemHandler", "backpack/inventory/BackpackContainer", "backpack/main/BackpackContainer");
        for (String name : List.of("CachePresentingInventory", "CacheSupply", "ServerCacheSupply", "ClientCacheSupply",
                "ReadOnlyCacheSupply", "CacheBorrow", "FxntContext", "FxntCompat", "FxntBackpack", "CompatLog",
                "PreviewCounts", "PreviewDiagnostic", "FxntResync", "mixin/TransferRecipePacketMixin",
                "mixin/JeiCraftingTransferHandlerMixin")) {
            String bytes = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/" + name + ".class");
            assertNotNull(bytes, "the compat class must be compiled: " + name);
            for (String symbol : forbidden) {
                assertFalse(bytes.contains(symbol), name + " must not touch their state through " + symbol);
            }
        }
    }
    @Test void theWornBackpackDiagnosticReadsTheItemsOwnComponent() {
        // Gate (F-11): the worn-backpack column is read from the item's own CONTAINER component, not from their object.
        String helper = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntBackpack.class");
        assertNotNull(helper, "the read-only helper must be compiled");
        assertTrue(helper.contains("ItemContainerContents"), "the contents must come from the item's own component");
        assertTrue(helper.contains("getEquippedBackpackStack"), "only their pure getter may name the worn stack");
        assertFalse(helper.contains("WORN_BACKPACK_CONTAINER"), "their attachment must not be read any more (F-11)");
    }
    @Test void ourOwnMixinConfigNeverTargetsJei() {
        String ours = resource("/create_feed_me_packages.mixins.json");
        assertNotNull(ours, "our mixin config must be on the classpath");
        assertEquals(List.of("dev.scathiard.feedmepackages.mixin"), matches(ours, "\"package\"\\s*:\\s*\"([^\"]+)\""));
        assertEquals(List.of("dev.scathiard.feedmepackages.mixin.OptionalMixins"), matches(ours, "\"plugin\"\\s*:\\s*\"([^\"]+)\""));
        assertFalse(ours.contains("mezz.jei"), "our production mixins must not target JEI");
        assertFalse(ours.toLowerCase(java.util.Locale.ROOT).contains("takeover"), "no takeover mixin may be listed");
    }
}
