package dev.scathiard.feedmepackages.compat.jei;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Current 0.3 contract: guarded native material sources, no global transfer-handler takeover or external state rebuild. */
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

    @Test void retainedLegacySeamDoesNotShipForeignClasses() {
        // F-6: the one seam is a subclass of the vanilla Inventory the call carries; the old handler seam is gone.
        // Legacy classes may be retired separately; their existence is not a current feature contract.
        assertNull(resource("/dev/scathiard/feedmepackages/compat/fxntstorage/CachePresentingHandler.class"), "the old handler seam must be gone");
        assertNull(resource("/net/fxnt/fxntstorage/backpack/main/IBackpackContainer.class"), "no third-party classes may be shipped");
    }
    @Test void retainedLegacyResyncNeverRewritesAnotherContainer() {
        String resync = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntResync.class");
        if (getClass().getResource("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntResync.class") == null) return;
        assertNotNull(resync, "Present legacy helper must be readable");
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
        // Current native material ports may debit authorized slots, but never reload or rebuild foreign state.
        List<String> forbidden = List.of("loadItemsFromStack", "getOrCreateWornBackpack", "invalidateWornBackpack",
                "WORN_BACKPACK_CONTAINER");
        for (String name : List.of("FxntMaterials", "FxntTransferContext", "ClientCacheSupply", "ReadOnlyCacheSupply", "CompatLog")) {
            String bytes = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/" + name + ".class");
            assertNotNull(bytes, "the compat class must be compiled: " + name);
            for (String symbol : forbidden) {
                assertFalse(bytes.contains(symbol), name + " must not touch their state through " + symbol);
            }
        }
    }
    @Test void retainedLegacyDiagnosticReadsTheItemsOwnComponent() {
        // Gate (F-11): the worn-backpack column is read from the item's own CONTAINER component, not from their object.
        String helper = classBytes("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntBackpack.class");
        if (getClass().getResource("/dev/scathiard/feedmepackages/compat/fxntstorage/FxntBackpack.class") == null) return;
        assertNotNull(helper, "Present legacy diagnostic must be readable");
        assertTrue(helper.contains("ItemContainerContents"), "the contents must come from the item's own component");
        assertTrue(helper.contains("getEquippedBackpackStack"), "only their pure getter may name the worn stack");
        assertFalse(helper.contains("WORN_BACKPACK_CONTAINER"), "their attachment must not be read any more (F-11)");
    }
    @Test void activeJeiMixinsAreOnlyTheApprovedSourceAndAccountingPorts() throws Exception {
        String ours = resource("/create_feed_me_packages.mixins.json");
        assertNotNull(ours, "our mixin config must be on the classpath");
        assertEquals(List.of("dev.scathiard.feedmepackages.mixin"), matches(ours, "\"package\"\\s*:\\s*\"([^\"]+)\""));
        assertEquals(List.of("dev.scathiard.feedmepackages.mixin.OptionalMixins"), matches(ours, "\"plugin\"\\s*:\\s*\"([^\"]+)\""));
        var actual=new java.util.HashMap<String,java.util.Set<String>>();
        for(String side:List.of("mixins","client"))for(String array:matches(ours,"\""+side+"\"\\s*:\\s*\\[([^\\]]*)\\]"))for(String name:matches(array,"\"([^\"]+)\"")) {
            var node=new org.objectweb.asm.tree.ClassNode();
            try(var in=getClass().getResourceAsStream("/dev/scathiard/feedmepackages/mixin/"+name+".class")) {
                assertNotNull(in,name);new org.objectweb.asm.ClassReader(in).accept(node,org.objectweb.asm.ClassReader.SKIP_CODE);
            }
            var annotations=new ArrayList<org.objectweb.asm.tree.AnnotationNode>();
            if(node.invisibleAnnotations!=null)annotations.addAll(node.invisibleAnnotations);
            if(node.visibleAnnotations!=null)annotations.addAll(node.visibleAnnotations);
            for(var a:annotations)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))for(int i=0;i<a.values.size();i+=2) {
                if(!List.of("value","targets").contains(a.values.get(i)))continue;
                for(Object target:(List<?>)a.values.get(i+1)) {
                    String type=target instanceof org.objectweb.asm.Type t?t.getClassName():target.toString();
                    if(type.startsWith("mezz.jei."))actual.computeIfAbsent(name,n->new java.util.HashSet<>()).add(type);
                }
            }
        }
        assertEquals(java.util.Map.of(
            "JeiSourcesMixin",java.util.Set.of("mezz.jei.library.transfer.BasicRecipeTransferHandler"),
            "JeiContextSourcesMixin",java.util.Set.of("mezz.jei.library.transfer.BasicRecipeTransferHandler"),
            "JeiServerEditMixin",java.util.Set.of("mezz.jei.common.transfer.BasicRecipeTransferHandlerServer"),
            "JeiServerResultEditMixin",java.util.Set.of("mezz.jei.common.transfer.BasicRecipeTransferHandlerServer")),actual);
        assertFalse(ours.toLowerCase(java.util.Locale.ROOT).contains("takeover"), "no takeover mixin may be listed");
    }
}
