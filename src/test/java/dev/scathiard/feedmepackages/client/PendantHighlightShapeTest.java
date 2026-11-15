package dev.scathiard.feedmepackages.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R-020: the pendant highlight must reuse Create 6.0.10's own measured parameters and must be hooked only where the
 * native handler reads the held network, so that a stock link, an unbound pendant, the off hand and every curios slot
 * keep their native behaviour. Both files are read as text on purpose: this pins the shape the feature was built from,
 * not a rendering result (the visual check belongs to a client run).
 */
class PendantHighlightShapeTest {
    private static String read(String path) throws Exception {
        var file = java.nio.file.Path.of(path);
        assertTrue(java.nio.file.Files.exists(file), "run the tests from the project directory: " + path);
        return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void theHighlightReusesCreatesMeasuredParameters() throws Exception {
        String helper = read("src/main/java/dev/scathiard/feedmepackages/client/PendantNetworkHighlight.java");
        for (String measured : new String[]{"-0.0078125d", "0.03125f", "7376301", "9481677", "64.0d",
                "getAllPresent", "showAABB", "disableLineNormals", "canInteractWithBlock"}) {
            assertTrue(helper.contains(measured), "the native parameter is missing: " + measured);
        }
        assertTrue(helper.contains("getMainHandItem()"), "only the main hand may trigger the pendant highlight");
        assertFalse(helper.contains("getOffhandItem"), "the off hand must not trigger it");
    }

    @Test void theNativeTickIsTakenOverOnlyForABoundPendant() throws Exception {
        String mixin = read("src/main/java/dev/scathiard/feedmepackages/mixin/PendantNetworkHighlightMixin.java");
        assertTrue(mixin.contains(
                "\"com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedClientHandler\""));
        assertTrue(mixin.contains("method = \"tick\""), "the hook must be the handler's own tick");
        assertTrue(mixin.contains("previouslyHeldFrequency"),
                "the factory-panel path reads that field natively, so the takeover must seed it");
        assertTrue(mixin.contains("if (frequency == null) return;"),
                "no bound pendant in the main hand means the native path stays in charge");
        String config = read("src/main/resources/create_feed_me_packages.mixins.json");
        assertTrue(config.contains("\"PendantNetworkHighlightMixin\""), "the mixin must be registered");
        String gate = read("src/main/java/dev/scathiard/feedmepackages/mixin/MixinTransportShapes.java");
        assertTrue(gate.contains("inspectPendantHighlight") && gate.contains("FMLLoader.getDist().isClient()"),
                "a dedicated server must never apply the client-only highlight");
    }
}
