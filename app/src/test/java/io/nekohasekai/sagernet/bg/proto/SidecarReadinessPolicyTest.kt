package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarReadinessPolicyTest {

    @Test
    fun markerNamesAreOwnedByOneBoxGeneration() {
        assertNotEquals(
            olcrtcReadyMarkerFileName(1080, "owner-a"),
            olcrtcReadyMarkerFileName(1080, "owner-b"),
        )
    }

    @Test
    fun markerIsRequiredOnlyForMarkedSidecars() {
        assertTrue(readinessMarkerSatisfied(markerRequired = false, markerPresent = false))
        assertTrue(readinessMarkerSatisfied(markerRequired = true, markerPresent = true))
        assertFalse(readinessMarkerSatisfied(markerRequired = true, markerPresent = false))
    }

    @Test
    fun unrelatedPendingPortDoesNotMakeMixedChainFatal() {
        assertFalse(
            shouldFailSidecarReadiness(
                pendingPorts = setOf(20001),
                requiredPorts = setOf(20002),
                strict = false,
            ),
        )
    }

    @Test
    fun strictModeWithNoPendingPortIsNotFatal() {
        assertFalse(
            shouldFailSidecarReadiness(
                pendingPorts = emptySet(),
                requiredPorts = emptySet(),
                strict = true,
            ),
        )
    }

    @Test
    fun requiredOrStrictPendingPortIsFatal() {
        assertTrue(
            shouldFailSidecarReadiness(
                pendingPorts = setOf(20001, 20002),
                requiredPorts = setOf(20002),
                strict = false,
            ),
        )
        assertTrue(
            shouldFailSidecarReadiness(
                pendingPorts = setOf(20001),
                requiredPorts = emptySet(),
                strict = true,
            ),
        )
    }
}
