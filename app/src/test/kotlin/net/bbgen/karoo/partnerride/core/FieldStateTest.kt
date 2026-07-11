package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldStateTest {

    private val now = 1_000_000L

    /** A fully healthy state with a fresh own fix and a fresh partner packet. */
    private fun healthy(
        packetAgeMs: Long = 1_000L,
        ownFixAgeMs: Long = 1_000L,
        gap: Double? = 12.0,
    ) = PartnerRideState(
        serviceRunning = true,
        bluetoothReady = true,
        advertising = true,
        scanning = true,
        lastOwnFixElapsedMs = now - ownFixAgeMs,
        lastPacketElapsedMs = now - packetAgeMs,
        smoothedGapMeters = gap,
        partnerAhead = true,
        zone = GapZone.GREEN,
    )

    @Test
    fun `service not running shows OFF on gray`() {
        val display = FieldState.build(PartnerRideState(), now)
        assertEquals("OFF", display.text)
        assertEquals(FieldBackground.GRAY, display.background)
    }

    @Test
    fun `missing permissions win over everything, even a stopped service`() {
        val state = PartnerRideState(serviceRunning = false, missingPermissions = listOf("x"))
        val display = FieldState.build(state, now)
        assertEquals("NO PERM", display.text)
        assertEquals(FieldBackground.GRAY, display.background)
    }

    @Test
    fun `bluetooth off shows NO BT`() {
        val state = healthy().copy(bluetoothReady = false)
        assertEquals("NO BT", FieldState.build(state, now).text)
    }

    @Test
    fun `no own fix yet shows NO GPS`() {
        val state = healthy().copy(lastOwnFixElapsedMs = null)
        val display = FieldState.build(state, now)
        assertEquals("NO GPS", display.text)
        assertEquals(FieldBackground.GRAY, display.background)
    }

    @Test
    fun `own fix staleness boundary is 10 s`() {
        val atLimit = healthy(ownFixAgeMs = FieldState.OWN_FIX_STALE_MS)
        assertEquals("12 m ▲", FieldState.build(atLimit, now).text)
        val overLimit = healthy(ownFixAgeMs = FieldState.OWN_FIX_STALE_MS + 1)
        assertEquals("NO GPS", FieldState.build(overLimit, now).text)
    }

    @Test
    fun `no partner packet since service start shows gray NO SIGNAL`() {
        val state = healthy().copy(lastPacketElapsedMs = null, smoothedGapMeters = null)
        val display = FieldState.build(state, now)
        assertEquals("NO SIGNAL", display.text)
        assertEquals(FieldBackground.GRAY, display.background)
    }

    @Test
    fun `fresh packet shows gap with arrow on the zone color`() {
        val display = FieldState.build(healthy(), now)
        assertEquals("12 m ▲", display.text)
        assertEquals(FieldBackground.GREEN, display.background)
        assertEquals(1f, display.fontScale)
    }

    @Test
    fun `partner behind flips the arrow`() {
        val state = healthy().copy(partnerAhead = false)
        assertEquals("12 m ▼", FieldState.build(state, now).text)
    }

    @Test
    fun `yellow and red zones map to their backgrounds`() {
        assertEquals(
            FieldBackground.YELLOW,
            FieldState.build(healthy(gap = 30.0).copy(zone = GapZone.YELLOW), now).background,
        )
        assertEquals(
            FieldBackground.RED,
            FieldState.build(healthy(gap = 80.0).copy(zone = GapZone.RED), now).background,
        )
    }

    @Test
    fun `packet older than 5 s shows last known value with age on red`() {
        val display = FieldState.build(healthy(packetAgeMs = 8_000L), now)
        assertEquals("~12 m · 8 s", display.text)
        assertEquals(FieldBackground.RED, display.background)
    }

    @Test
    fun `fresh boundary is exactly 5 s`() {
        assertEquals("12 m ▲", FieldState.build(healthy(packetAgeMs = FieldState.FRESH_MS), now).text)
        assertEquals(
            "~12 m · 5 s",
            FieldState.build(healthy(packetAgeMs = FieldState.FRESH_MS + 1), now).text,
        )
    }

    @Test
    fun `last known value stays up until 60 s, then red NO SIGNAL`() {
        val atLimit = FieldState.build(healthy(packetAgeMs = FieldState.SIGNAL_LOST_MS), now)
        assertEquals("~12 m · 60 s", atLimit.text)
        assertEquals(FieldBackground.RED, atLimit.background)

        val lost = FieldState.build(healthy(packetAgeMs = FieldState.SIGNAL_LOST_MS + 1), now)
        assertEquals("NO SIGNAL", lost.text)
        assertEquals(FieldBackground.RED, lost.background)
    }

    @Test
    fun `priority order surfaces the first problem to fix`() {
        // Everything broken at once: NO PERM > OFF > NO BT > NO GPS > NO SIGNAL.
        var state = PartnerRideState(
            serviceRunning = false,
            bluetoothReady = false,
            missingPermissions = listOf("x"),
        )
        assertEquals("NO PERM", FieldState.build(state, now).text)
        state = state.copy(missingPermissions = emptyList())
        assertEquals("OFF", FieldState.build(state, now).text)
        state = state.copy(serviceRunning = true)
        assertEquals("NO BT", FieldState.build(state, now).text)
        state = state.copy(bluetoothReady = true)
        assertEquals("NO GPS", FieldState.build(state, now).text)
        state = state.copy(lastOwnFixElapsedMs = now)
        assertEquals("NO SIGNAL", FieldState.build(state, now).text)
        assertEquals(FieldBackground.GRAY, FieldState.build(state, now).background)
    }
}
