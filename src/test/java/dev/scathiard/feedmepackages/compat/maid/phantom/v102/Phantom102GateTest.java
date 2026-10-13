package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-b unit coverage for the two pure pieces: the read-only shape verdict (with its one stable log line) and FMP's
 * own task markers, where "missing" and "corrupt" must never be confused. Minecraft types stay out of this file on
 * purpose; the reflection probe itself is exercised in the game run.
 */
class Phantom102GateTest {

    private static Map<String, String> exact() { return new HashMap<>(Phantom102Shape.members()); }

    @Test void theShapeTableCarriesTheMeasured1_0_2Members() {
        Map<String, String> members = Phantom102Shape.members();
        assertTrue(members.size() >= 20, "the gate must cover the whole measured surface");
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lcom/yision/phantom/logistics/courier/AirCourierTarget;Lnet/minecraft/world/item/ItemStack;)Z",
                members.get("AirCourierDispatchService#canReceivePackageTarget"));
        assertEquals("(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)Z",
                members.get("AirCourierHelper#canReceiveDelivery"));
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/server/level/ServerPlayer;",
                members.get("AirCourierHelper#findTargetPlayer@3"));
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;)Z",
                members.get("AirCourierDispatchService#canReceiveCarrierTarget"));
        assertEquals("Z", members.get("field AirCourierTask#returningUndeliveredPackage"));
        assertEquals("I", members.get("field AirCourierTask#destinationUnavailableTicks"));
        assertEquals("Lnet/minecraft/server/level/ServerPlayer;", members.get("field AirCourierTask$FlightTarget#player"));
    }

    @Test void anExactArtifactEnablesTheGate() {
        String verdict = Phantom102Shape.verdict(true, Phantom102Shape.VERSION, Phantom102Shape.SHA256, exact());
        assertEquals("enabled", verdict);
        assertEquals("FMP phantom102 gate: enabled", Phantom102Shape.logLine(verdict));
    }

    @Test void everyMismatchDisablesTheGateWithAStableReason() {
        assertEquals("disabled reason=mod-not-loaded", Phantom102Shape.verdict(false, Phantom102Shape.VERSION, Phantom102Shape.SHA256, exact()));
        assertEquals("disabled reason=version-mismatch", Phantom102Shape.verdict(true, "1.0.3", Phantom102Shape.SHA256, exact()));
        assertEquals("disabled reason=sha256-mismatch", Phantom102Shape.verdict(true, Phantom102Shape.VERSION, "deadbeef", exact()));
        Map<String, String> missing = exact();
        missing.remove("AirCourierTask#doFinishDeliveryAt");
        assertEquals("disabled reason=missing-member AirCourierTask#doFinishDeliveryAt",
                Phantom102Shape.verdict(true, Phantom102Shape.VERSION, Phantom102Shape.SHA256, missing));
        Map<String, String> changed = exact();
        changed.put("AirCourierTask$FlightTarget#playerTarget", "(Lnet/minecraft/server/level/ServerPlayer;)Z");
        assertEquals("disabled reason=descriptor-mismatch AirCourierTask$FlightTarget#playerTarget",
                Phantom102Shape.verdict(true, Phantom102Shape.VERSION, Phantom102Shape.SHA256, changed));
        assertEquals("FMP phantom102 gate: disabled reason=sha256-mismatch",
                Phantom102Shape.logLine(Phantom102Shape.verdict(true, Phantom102Shape.VERSION, "x", exact())));
    }

    @Test void aTaskWithoutAnyMarkerStaysAnOrdinaryPlayerTask() {
        var decoded = PhantomMaidTaskMarker.decode(null, "   ", null);
        assertEquals(PhantomMaidTaskMarker.State.NATIVE, decoded.state());
        assertFalse(PhantomMaidTaskMarker.ours(decoded));
        assertNull(decoded.taskId());
        assertNull(decoded.maidId());
    }

    @Test void aCompleteMarkerRoundTripsAndIsOutbound() {
        UUID task = UUID.randomUUID(), maid = UUID.randomUUID();
        String[] encoded = PhantomMaidTaskMarker.encode(task, maid, PhantomMaidTaskMarker.Direction.OUTBOUND);
        var decoded = PhantomMaidTaskMarker.decode(encoded[0], encoded[1], encoded[2]);
        assertEquals(PhantomMaidTaskMarker.State.MARKED, decoded.state());
        assertEquals(task, decoded.taskId());
        assertEquals(maid, decoded.maidId());
        assertTrue(decoded.outbound());
        assertTrue(PhantomMaidTaskMarker.ours(decoded));
    }

    @Test void partialOrUnparsableMarkersAreCorruptAndNeverPlayerTasks() {
        UUID task = UUID.randomUUID();
        var partial = PhantomMaidTaskMarker.decode(task.toString(), null, "OUTBOUND");
        assertEquals(PhantomMaidTaskMarker.State.CORRUPT, partial.state());
        assertFalse(partial.marked(), "a corrupt marker must not read as a marked task");
        assertTrue(PhantomMaidTaskMarker.ours(partial), "a corrupt marker is still ours, never a player task");
        assertEquals(PhantomMaidTaskMarker.State.CORRUPT,
                PhantomMaidTaskMarker.decode("not-a-uuid", UUID.randomUUID().toString(), "OUTBOUND").state());
        assertEquals(PhantomMaidTaskMarker.State.CORRUPT,
                PhantomMaidTaskMarker.decode(task.toString(), UUID.randomUUID().toString(), "SIDEWAYS").state());
        assertNotNull(PhantomMaidTaskMarker.decode(null, null, "RETURN").state());
    }
}
