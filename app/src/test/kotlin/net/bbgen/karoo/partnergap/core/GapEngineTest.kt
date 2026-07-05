package net.bbgen.karoo.partnergap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `smoothing averages the last three computed values`() {
        val engine = GapEngine()
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
        val engine = GapEngine(replayResetMs = 60_000L)
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
}
