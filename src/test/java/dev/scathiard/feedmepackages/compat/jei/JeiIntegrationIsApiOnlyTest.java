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

    @Test void theModCarriesExactlyOneMixinConfigAndItIsOurs() {
        String toml = resource("/META-INF/neoforge.mods.toml");
        assertNotNull(toml, "neoforge.mods.toml must be on the classpath");
        assertEquals(List.of("create_feed_me_packages.mixins.json"), matches(toml, "config\\s*=\\s*\"([^\"]+)\""),
                "exactly one [[mixins]] config, and it is our own");
        assertFalse(toml.contains("mezz.jei"), "no mixin config may point at JEI");
        assertFalse(toml.contains("fmp_jei_takeover"), "the takeover config was removed by user decision");
    }

    @Test void neitherTheTakeoverNorTheJeiHelperLayerShips() {
        assertNull(resource("/fmp_jei_takeover.mixins.json"), "the JEI takeover mixin config must not ship");
        for (String gone : List.of(
                "/dev/scathiard/feedmepackages/compat/jei/FmpRecipeTransfer.class",
                "/dev/scathiard/feedmepackages/compat/jei/PreviewPolicy.class",
                "/dev/scathiard/feedmepackages/client/ClientNotice.class",
                "/dev/scathiard/feedmepackages/client/NoticeText.class")) {
            assertNull(resource(gone), gone + " must not be compiled into this mod any more");
        }
    }

    @Test void thePluginRegistersNoRecipeTransferHandlerAndNeverTalksToJeiTransferApi() {
        String plugin = classBytes("/dev/scathiard/feedmepackages/compat/jei/FmpJeiPlugin.class");
        assertNotNull(plugin, "our JEI plugin must be on the classpath");
        assertFalse(plugin.contains("registerRecipeTransferHandlers"), "the plugin must not register transfer handlers");
        assertFalse(plugin.contains("craftingHandler"), "the plugin must not keep a crafting handler");
        assertFalse(plugin.contains("mezz/jei/api/recipe/transfer"), "the plugin must not touch JEI's transfer API");
        // What it may still do: our panel session/exclusions and showing our own smithing category.
        assertTrue(plugin.contains("recipeOverlay"), "the panel session integration must stay");
        assertTrue(plugin.contains("registerVanillaCategoryExtensions"), "showing our recipe must stay");
        assertTrue(plugin.contains("registerGuiHandlers"), "our exclusion area must stay");
    }

    @Test void theSentencesWrittenForJeiAreGone() {
        for (String lang : List.of("/assets/create_feed_me_packages/lang/zh_cn.json", "/assets/create_feed_me_packages/lang/en_us.json")) {
            String text = resource(lang);
            assertNotNull(text, lang);
            assertFalse(text.contains("missing_entry"), lang);
            assertFalse(text.contains("missing_detail"), lang);
            assertFalse(text.contains("missing_scope"), lang);
            assertTrue(text.contains("result.panel_not_ready"), "the panel's own messages must stay: " + lang);
        }
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
