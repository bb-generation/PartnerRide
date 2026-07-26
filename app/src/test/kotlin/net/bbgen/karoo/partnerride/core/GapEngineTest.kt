package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GapEngineTest {
    /** ~11.1 m of latitude. */
    private val latStep = 1e-4

    /** Arbitrary GPS epoch base for own fixes. */
    private val base = 1_000_000L

    private fun timeMod(timeMs: Long) = (timeMs % 65536L).toInt()

    /** Feeds fixes riding due north at ~11 m/s, one per second, for [seconds] seconds. */
    private fun rideNorth(engine: GapEngine, seconds: Int, startLat: Double = 47.0) {
        for (t in 0..seconds) {
            engine.onOwnFix(GpsFix(base + t * 1000L, startLat + t * latStep, 15.0))
        }
    }

    @Test
    fun `no own fix yet returns null`() {
        val engine = GapEngine()
        assertNull(engine.onPartnerPacket(PartnerPacket(100, 47.0, 15.0), nowElapsedMs = 0L))
    }

    @Test
    fun `partner ahead on own heading is positive with matching distance`() {
        val engine = GapEngine()
        rideNorth(engine, 4)
        val ownLatest = engine.latestOwnFix()!!

        // Partner ~111 m further north — directly on our heading.
        val partnerLat = ownLatest.latDeg + 10 * latStep
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(ownLatest.timeMs), partnerLat, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertTrue(result!!.partnerAhead)
        val expected = Geo.haversineMeters(ownLatest.latDeg, 15.0, partnerLat, 15.0)
        assertEquals(expected, result.rawGapMeters, 0.5)
        assertTrue(result.smoothedGapMeters > 0)
    }

    @Test
    fun `partner behind own heading is negative`() {
        val engine = GapEngine()
        rideNorth(engine, 4)
        val ownLatest = engine.latestOwnFix()!!

        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(ownLatest.timeMs), ownLatest.latDeg - 10 * latStep, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertFalse(result!!.partnerAhead)
        assertTrue(result.smoothedGapMeters < 0)
    }

    @Test
    fun `partner fix is matched against the own fix of the same GPS time, not the current position`() {
        val engine = GapEngine()
        rideNorth(engine, 4)

        // Partner packet stamped 3 s ago, positioned exactly where WE were 3 s ago.
        // Correct timestamp matching yields ~0 m even though we've since moved ~33 m.
        val pastOwnTime = base + 1000L
        val pastOwnLat = 47.0 + 1 * latStep
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(pastOwnTime), pastOwnLat, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.rawGapMeters, 0.1)
    }

    @Test
    fun `timestamp matching picks the nearest buffered fix`() {
        val engine = GapEngine()
        rideNorth(engine, 4)

        // Packet stamped 2.4 s after base: closest own fix is t=2 s. Partner sits exactly on
        // the t=2 fix, so a correct match gives ~0 m; matching t=3 would give ~11 m.
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(base + 2_400L), 47.0 + 2 * latStep, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.rawGapMeters, 1.0)
    }

    @Test
    fun `standing still keeps the last stable sign`() {
        val engine = GapEngine()
        rideNorth(engine, 3)
        val movingLatest = engine.latestOwnFix()!!

        // While moving north, partner behind: sign becomes negative.
        val first = engine.onPartnerPacket(
            PartnerPacket(timeMod(movingLatest.timeMs), movingLatest.latDeg - 10 * latStep, 15.0),
            nowElapsedMs = 1_000L,
        )
        assertFalse(first!!.partnerAhead)

        // Stand still long enough that no fix pair in the buffer is >= 2 m apart.
        val stopLat = movingLatest.latDeg
        for (t in 4..12) engine.onOwnFix(GpsFix(base + t * 1000L, stopLat, 15.0))
        val stillLatest = engine.latestOwnFix()!!

        // Partner is now geometrically "ahead", but our heading is unreliable: keep the sign.
        val second = engine.onPartnerPacket(
            PartnerPacket(timeMod(stillLatest.timeMs), stopLat + 10 * latStep, 15.0),
            nowElapsedMs = 2_000L,
        )
        assertNotNull(second)
        assertFalse(second!!.partnerAhead)
    }

    @Test
    fun `default smoothing window is 1 - effectively disabled`() {
        val engine = GapEngine()
        rideNorth(engine, 2)
        val own = engine.latestOwnFix()!!

        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(own.timeMs), own.latDeg + 10 * latStep, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(result!!.rawGapMeters, result.smoothedGapMeters, 1e-9)
    }

    @Test
    fun `smoothing averages the last three computed values`() {
        val engine = GapEngine(smoothingWindow = 3)
        rideNorth(engine, 4)
        val own = engine.latestOwnFix()!!

        val gaps = mutableListOf<GapResult>()
        for (i in 1..4) {
            engine.onOwnFix(GpsFix(own.timeMs + i * 1000L, own.latDeg + i * latStep, 15.0))
            val latest = engine.latestOwnFix()!!
            val result = engine.onPartnerPacket(
                // Partner ahead by (i * 10) meters-ish: distances differ per packet.
                PartnerPacket(timeMod(latest.timeMs), latest.latDeg + i * latStep, 15.0),
                nowElapsedMs = i * 1_000L,
            )
            gaps += result!!
        }

        val lastThreeRaw = gaps.takeLast(3).map { it.rawGapMeters }
        assertEquals(lastThreeRaw.average(), gaps.last().smoothedGapMeters, 0.5)
    }

    // ------------------------------------------------------------ dead reckoning (v2)

    @Test
    fun `partner position is dead-reckoned forward to the own fix time`() {
        val engine = GapEngine()
        // Own device standing at a known point with fresh fixes.
        for (t in 0..4) engine.onOwnFix(GpsFix(base + t * 1000L, 47.0, 15.0))
        val own = engine.latestOwnFix()!!

        // Partner fix is 2 s older than ours: ~111 m north, riding north at 10 m/s.
        // Dead reckoning must evaluate the partner 20 m further along, not at the raw fix.
        val partnerFixLat = 47.0 + 10 * latStep
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(own.timeMs - 2000L), partnerFixLat, 15.0, speedMps = 10.0, headingDeg = 0.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        val extrapolated = Geo.extrapolate(partnerFixLat, 15.0, 10.0, 0.0, 2.0)
        val expected = Geo.haversineMeters(47.0, 15.0, extrapolated.latDeg, extrapolated.lonDeg)
        assertEquals(expected, result!!.rawGapMeters, 0.5)
        // Sanity: clearly more than the un-extrapolated ~111 m.
        assertTrue(result.rawGapMeters > 125.0)
    }

    @Test
    fun `own position is dead-reckoned forward when the partner fix is newer`() {
        val engine = GapEngine()
        // Own fixes carry GPS speed/bearing: riding north at 10 m/s.
        for (t in 0..4) {
            engine.onOwnFix(GpsFix(base + t * 1000L, 47.0 + t * latStep, 15.0, speedMps = 10.0, bearingDeg = 0.0))
        }
        val own = engine.latestOwnFix()!!

        // Partner fix is 1 s newer, sitting exactly on our last fix position, not moving.
        // Correct alignment extrapolates US 10 m forward: the partner ends up ~10 m behind.
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(own.timeMs + 1000L), own.latDeg, own.lonDeg, speedMps = 0.0, headingDeg = 0.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(10.0, result!!.rawGapMeters, 0.5)
        assertFalse(result.partnerAhead)
    }

    @Test
    fun `extrapolation is capped at 3 s - older partner fixes fall back to timestamp matching`() {
        val engine = GapEngine()
        rideNorth(engine, 5)
        val own = engine.latestOwnFix()!!

        // Partner fix is 5 s old, positioned exactly where we were 5 s ago, with valid speed
        // and heading. Extrapolating 5 s would add ~50 m; the cap demands timestamp matching
        // instead, which yields ~0 m.
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(own.timeMs - 5000L), 47.0, 15.0, speedMps = 10.0, headingDeg = 0.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.rawGapMeters, 1.0)
    }

    @Test
    fun `sentinel speed or heading falls back to timestamp matching`() {
        val engine = GapEngine()
        rideNorth(engine, 5)
        val own = engine.latestOwnFix()!!

        // Partner fix 2 s old on our own track position of that moment, but WITHOUT
        // speed/heading. No extrapolation allowed: matching gives ~0 m (with extrapolation
        // it would be ~20 m).
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(own.timeMs - 2000L), 47.0 + 3 * latStep, 15.0, speedMps = null, headingDeg = null),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.rawGapMeters, 1.0)
    }

    @Test
    fun `replayed packets are dropped`() {
        val engine = GapEngine()
        rideNorth(engine, 4)
        val own = engine.latestOwnFix()!!
        val packet = PartnerPacket(timeMod(own.timeMs), own.latDeg + latStep, 15.0)

        assertNotNull(engine.onPartnerPacket(packet, nowElapsedMs = 1_000L))
        assertNull(engine.onPartnerPacket(packet, nowElapsedMs = 1_100L))
    }

    @Test
    fun `partner returning after a long absence is accepted again`() {
        val engine = GapEngine()
        rideNorth(engine, 4)
        val own = engine.latestOwnFix()!!

        assertNotNull(
            engine.onPartnerPacket(PartnerPacket(timeMod(own.timeMs), own.latDeg + latStep, 15.0), 1_000L),
        )
        // 10 minutes later the mod-65536 comparison is meaningless; an "older-looking" timeMod
        // must not block recovery.
        val staleLooking = timeMod(own.timeMs) - 1000
        assertNotNull(
            engine.onPartnerPacket(PartnerPacket(staleLooking, own.latDeg + latStep, 15.0), 601_000L),
        )
    }

    @Test
    fun `partner returning mid-window is not stuck behind the replay guard`() {
        val engine = GapEngine()
        rideNorth(engine, 4)
        val own = engine.latestOwnFix()!!

        assertNotNull(
            engine.onPartnerPacket(PartnerPacket(timeMod(own.timeMs), own.latDeg + latStep, 15.0), 1_000L),
        )
        // 40 s out of range: the partner's GPS clock advanced 40 s, which is past the guard's
        // 32.768 s half-window, so mod-65536 makes the packet look 25.5 s *old*. Recovery must
        // be immediate, not deferred to the replay reset.
        val returning = timeMod(own.timeMs + 40_000L)
        assertNotNull(
            engine.onPartnerPacket(PartnerPacket(returning, own.latDeg + latStep, 15.0), 41_000L),
        )
    }

    @Test
    fun `sign uses the heading of the matched fix, not the post-corner heading`() {
        val engine = GapEngine()
        // North for 3 s...
        for (t in 0..3) {
            engine.onOwnFix(GpsFix(base + t * 1000L, 47.0 + t * latStep, 15.0))
        }
        // ...then a hard turn onto ~135° (south-east); ~11 m per step either way.
        val cornerLat = 47.0 + 3 * latStep
        val lonStep = 1.47e-4
        for (t in 4..5) {
            engine.onOwnFix(GpsFix(base + t * 1000L, cornerLat - (t - 3) * latStep, 15.0 + (t - 3) * lonStep))
        }

        // Partner fix from t=2, due north of where we were then — i.e. ahead at that moment.
        // Sentinel speed/heading forces the timestamp-matching fallback.
        val result = engine.onPartnerPacket(
            PartnerPacket(timeMod(base + 2000L), 47.0 + 2 * latStep + 10 * latStep, 15.0),
            nowElapsedMs = 1_000L,
        )

        assertNotNull(result)
        // Measuring the bearing from the t=2 position but comparing it against the *current*
        // south-east heading reports the partner as behind.
        assertTrue(result!!.partnerAhead)
    }

    @Test
    fun `replay reset window must stay below the guard's ambiguity limit`() {
        // Guards the B1 regression: a reset slower than the half-window reopens the dead band.
        assertTrue(GapEngine.DEFAULT_REPLAY_RESET_MS < GapEngine.REPLAY_AMBIGUITY_MS)
        try {
            GapEngine(replayResetMs = 60_000L)
            fail("expected IllegalArgumentException for a reset past the ambiguity limit")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
