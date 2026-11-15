package dev.scathiard.feedmepackages.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R-018 content pin: the acceptance run needs a client, but the shape of what was shipped can be checked headless. This
 * keeps the three items, both language files, the five-round sequence, the five-by-five crafting and the expansion
 * smithing recipe from silently drifting while the in-game verification is still pending.
 */
class ExpansionContentTest {
    private static final Path ROOT = Path.of("src/main/resources");
    private static final Path DATA = ROOT.resolve("data/create_feed_me_packages/recipe");
    private static final Path ASSETS = ROOT.resolve("assets/create_feed_me_packages");

    private static String text(Path path) throws Exception {
        assertTrue(Files.exists(path), "missing: " + path);
        return Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void theThreeItemsExistWithModelsTexturesAndNames() throws Exception {
        for (String item : new String[]{"expansion_frame", "incomplete_expansion_frame", "expansion_link"}) {
            assertTrue(Files.size(ASSETS.resolve("models/item/" + item + ".json")) > 0, "model " + item);
            assertTrue(Files.size(ASSETS.resolve("textures/item/" + item + ".png")) > 0, "placeholder texture " + item);
            for (String locale : new String[]{"en_us", "zh_cn"}) {
                var lang = text(ASSETS.resolve("lang/" + locale + ".json"));
                assertTrue(lang.contains("item.create_feed_me_packages." + item + "\""),
                        item + " has no name in " + locale);
            }
        }
        String registry = text(Path.of("src/main/java/dev/scathiard/feedmepackages/registry/FmpRegistries.java"));
        for (String item : new String[]{"expansion_frame", "incomplete_expansion_frame", "expansion_link"})
            assertTrue(registry.contains("\"" + item + "\""), "item not registered: " + item);
    }

    @Test void theSequenceIsFiveRoundsOfSixStepsAndStartsWithAChain() throws Exception {
        var frame = text(DATA.resolve("sequenced_assembly/expansion_frame.json"));
        assertTrue(frame.contains("\"loops\": 5"), "the specification asks for five rounds");
        assertEquals(6, frame.split("\"type\": \"create:", -1).length - 2, "six installation steps");
        assertTrue(frame.contains("\"minecraft:chain\""), "the new sequence starts by installing a chain");
        assertTrue(frame.contains("\"create_feed_me_packages:supply_chain_pendant\""), "a pendant is installed");
        assertTrue(frame.contains("\"create:precision_mechanism\""), "a precision mechanism is installed");
        // Weights, exactly how the existing link recipe expresses its four-to-one outcome (not probabilities).
        assertTrue(frame.contains("\"chance\": 4.0") && frame.contains("\"chance\": 1.0"),
                "the four-to-one outcome is a weighted four and one, like the existing link recipe");
        assertTrue(frame.contains("\"item\": \"create_feed_me_packages:link_frame_2\""),
                "it starts from link frame 1 (link_frame_2) as the specification says");
        // The old link sequence must stay untouched: it still starts with a press.
        String old = text(DATA.resolve("sequenced_assembly/upgrade_link_2.json"));
        assertTrue(old.contains("\"type\": \"create:pressing\""), "the existing link sequence must still press first");
    }

    @Test void theCraftingIsFiveByFiveWithFourFramesAndADragonHead() throws Exception {
        String link = text(DATA.resolve("mechanical_crafting/expansion_link.json"));
        assertTrue(link.contains("\"create:mechanical_crafting\""));
        for (String row : new String[]{"\"  N  \"", "\" FGF \"", "\"BPDLB\"", "\" FSF \"", "\"  V  \""})
            assertTrue(link.contains(row), "row missing: " + row);
        assertTrue(link.contains("\"minecraft:dragon_head\""), "the dragon head is the centre of row three");
        assertTrue(link.contains("\"create_feed_me_packages:expansion_link\""), "the result is one expansion link");
        assertTrue(link.contains("\"create_feed_me_packages:expansion_frame\""),
                "the expansion frame must be one of the crafting keys");
        long frameSlots = (" FGF ".replace(" ", "") + " FSF ".replace(" ", "")).chars().filter(c -> c == 'F').count();
        assertEquals(4, frameSlots, "the frame occupies the four slots next to the centre");
    }

    @Test void theExpansionSmithingUsesTheSentinelAndTheLink() throws Exception {
        String smithing = text(DATA.resolve("smithing/pendant_expansion.json"));
        assertTrue(smithing.contains("\"target_level\": 0"), "0 is the documented expansion sentinel");
        assertTrue(smithing.contains("\"create_feed_me_packages:expansion_link\""), "it consumes an expansion link");
        assertTrue(smithing.contains("\"item\": \"create_feed_me_packages:assembly_template\""), "the plan is the template");
        String growth = text(Path.of("src/main/java/dev/scathiard/feedmepackages/growth/GrowthService.java"));
        assertTrue(growth.contains("recipe.expansion()") && growth.contains("prepareExpansion"),
                "the commit path must route the sentinel to the ledger expansion");
        String recipe = text(Path.of("src/main/java/dev/scathiard/feedmepackages/growth/PendantSmithingRecipe.java"));
        assertTrue(recipe.contains("targetLevel != 0"), "the recipe must accept the sentinel");
    }
}
