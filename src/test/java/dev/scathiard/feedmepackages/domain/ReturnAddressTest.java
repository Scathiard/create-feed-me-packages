package dev.scathiard.feedmepackages.domain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReturnAddressTest {
    @Test void pastedAndTypedTextUseOneUnicodeBoundary() {
        assertEquals("仓库 🚀", ReturnAddress.appendInput("仓", "库 \r\n🚀\t"));
        assertEquals("ab", ReturnAddress.appendInput("", "a\uD800b\uDC00"));
        assertEquals("仓".repeat(127), ReturnAddress.appendInput("仓".repeat(127), "🚀"));
        assertEquals("仓".repeat(127)+"A", ReturnAddress.appendInput("仓".repeat(127), "🚀A"));
        assertEquals(128, ReturnAddress.appendInput("仓".repeat(126), "🚀尾").length());
    }

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
}
