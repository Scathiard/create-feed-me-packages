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

    @Test void theModCarriesOnlyItsOwnMixinConfigs() {
        String toml = resource("/META-INF/neoforge.mods.toml");
        assertNotNull(toml, "neoforge.mods.toml must be on the classpath");
        assertEquals(List.of("create_feed_me_packages.mixins.json", "fmp_fxntstorage_compat.mixins.json"),
                matches(toml, "config\\s*=\\s*\"([^\"]+)\""), "our own mixins plus the optional Create: Storage compat");
        assertFalse(toml.contains("mezz.jei"), "no mixin config may point at JEI");
        assertFalse(toml.contains("fmp_jei_takeover"), "the takeover config was removed by user decision");
    }

    @Test void theCreateStorageCompatIsSoftAndNeverTouchesJei() {
        String compat = resource("/fmp_fxntstorage_compat.mixins.json");
        assertNotNull(compat, "the Create: Storage compat config must ship");
        assertEquals(List.of("dev.scathiard.feedmepackages.compat.fxntstorage.mixin"),
                matches(compat, "\"package\"\\s*:\\s*\"([^\"]+)\""), "helpers must live outside the mixin package");
        assertTrue(compat.contains("\"required\": false"), "a soft config: with their mod absent it must not hard-fail");
        assertTrue(compat.contains("\"defaultRequire\": 0"), "every injector must be allowed to miss");
        assertFalse(compat.contains("mezz.jei"), "the compat layer must not touch JEI at all");
        // F-6: two mixins, three injection points (server: two Inventory arguments; client: one getInventory call).
        assertTrue(compat.contains("TransferRecipePacketMixin") && compat.contains("JeiCraftingTransferHandlerMixin"));
        assertFalse(compat.contains("ServerPayloadHandlerMixin"), "the 1.1.x shape is not supported and must not be claimed");
        for (String mixin : List.of("TransferRecipePacketMixin", "JeiCraftingTransferHandlerMixin")) {
            String bytes = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/mixin/" + mixin + ".class");
            assertNotNull(bytes, mixin + " must be compiled");
            assertTrue(bytes.contains("Pseudo"), mixin + " must be @Pseudo so an absent target is harmless");
            assertTrue(bytes.contains("net.fxnt.fxntstorage"), mixin + " must locate them BY NAME only");
        }
        // ...and nothing of theirs may be on our classpath at all (no compile-time dependency).
        assertNull(resource("/net/fxnt/fxntstorage/backpack/main/IBackpackContainer.class"), "no third-party classes may be shipped");
        // The old handler-based seam is gone, so the two seams cannot fight.
        assertNull(resource("/dev/scathiard/feedmepackages/compat/fxntstorage/CachePresentingHandler.class"), "the old ItemStackHandler seam must be gone");
        assertNull(resource("/dev/scathiard/feedmepackages/compat/fxntstorage/SideSelect.class"), "the dist-based side selection must be gone");
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
    @Test void theContainerProbeReloadsThroughTheirApiAndNeverRewritesSlots() {
        String probe = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntContainerProbe.class");
        assertNotNull(probe, "the container probe must be compiled");
        assertFalse(probe.contains("setStackInSlot"), "the probe must never write a slot (F-8 prohibition)");
        assertTrue(probe.contains("loadItemsFromStack"), "the refresh must use THEIR own reload method");
        assertTrue(probe.contains("fresh"), "the freshness evidence line must exist");
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
