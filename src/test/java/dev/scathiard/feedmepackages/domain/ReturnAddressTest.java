package dev.scathiard.feedmepackages.domain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReturnAddressTest {
    @Test void allExistingValidUnicodeFitsTheWire() {
        for(var value:java.util.List.of("", "仓".repeat(128), "a".repeat(128), "\uD83D\uDE80".repeat(64), "  仓库 😀  ")) {
            assertTrue(ReturnAddress.valid(value));
            assertTrue(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=ReturnAddress.MAX_BYTES);
        }
    }
    @Test void overlengthAndUnpairedSurrogatesCannotTurnIntoDifferentAddresses() {
        for(var value:java.util.List.of("a".repeat(129), "\uD800", "\uDC00", "\uD800x", "仓".repeat(127)+"\uD83D\uDE80"))
            assertFalse(ReturnAddress.valid(value));
    }

    // R-016: a paste must follow exactly the typing contract.
    @Test void pasteKeepsChineseAndEmojiVerbatim() {
        assertEquals("  仓库 😀  ", ReturnAddress.append("", "  仓库 😀  "));
        assertEquals("仓库A😀", ReturnAddress.append("仓库", "A😀"));
    }
    @Test void pasteDropsControlCharactersLikeTheKeyboard() {
        assertEquals("仓库😀", ReturnAddress.append("", "仓\t库\n😀\r"));
        assertEquals("ab", ReturnAddress.append("a", "b"));
    }
    @Test void pasteStopsAtTheSameLengthCeiling() {
        String pasted = ReturnAddress.append("", "仓".repeat(200));
        assertEquals(ReturnAddress.MAX_UNITS, pasted.length());
        assertTrue(ReturnAddress.valid(pasted));
        // A two-unit emoji that would cross the ceiling is refused whole, never split into half a character.
        String kept = ReturnAddress.append("", "仓".repeat(127) + "\uD83D\uDE00");
        assertEquals(127, kept.length());
        assertTrue(ReturnAddress.valid(kept));
    }
    @Test void pasteNeverProducesAnInvalidAddress() {
        for (var pasted : java.util.List.of("", "\uD800", "a\uDC00b", "仓".repeat(300), "\uD83D", "\uDE00仓")) {
            assertTrue(ReturnAddress.valid(ReturnAddress.append("仓".repeat(100), pasted)),
                    "paste produced an invalid address: " + pasted);
        }
        assertEquals("", ReturnAddress.append(null, null));
    }
}
