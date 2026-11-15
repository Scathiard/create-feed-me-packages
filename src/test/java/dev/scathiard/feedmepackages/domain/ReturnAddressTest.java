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
}
