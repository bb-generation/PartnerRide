package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampsTest {

    // ---------------------------------------------------------------- reconstruction

    @Test
    fun `reconstructs a timestamp close to the reference`() {
        val reference = 1_000_000_000_000L
        val partnerTime = reference - 1_500L
        val reconstructed = Timestamps.reconstruct((partnerTime % 65536L).toInt(), reference)
        assertEquals(partnerTime, reconstructed)
    }

    @Test
    fun `reconstructs a partner fix slightly newer than the reference`() {
        val reference = 1_000_000_000_000L
        val partnerTime = reference + 900L
        val reconstructed = Timestamps.reconstruct((partnerTime % 65536L).toInt(), reference)
        assertEquals(partnerTime, reconstructed)
    }

    @Test
    fun `handles wraparound below the reference`() {
        // reference just after a 65536 boundary, partner fix just before it
        val reference = 65536L * 1000 + 100
        val partnerTime = reference - 200 // mod value is near 65535 while reference mod is 100
        val reconstructed = Timestamps.reconstruct((partnerTime % 65536L).toInt(), reference)
        assertEquals(partnerTime, reconstructed)
    }

    @Test
    fun `handles wraparound above the reference`() {
        // reference just before a 65536 boundary, partner fix just after it
        val reference = 65536L * 1000 - 100
        val partnerTime = reference + 200
        val reconstructed = Timestamps.reconstruct((partnerTime % 65536L).toInt(), reference)
        assertEquals(partnerTime, reconstructed)
    }

    @Test
    fun `reconstructed value is always congruent mod 65536`() {
        for (timeMod in intArrayOf(0, 1, 32767, 32768, 65535)) {
            val reconstructed = Timestamps.reconstruct(timeMod, 987_654_321_012L)
            assertEquals(timeMod.toLong(), reconstructed.mod(65536L))
        }
    }

    // ---------------------------------------------------------------- replay guard

    @Test
    fun `first packet is always accepted`() {
        assertTrue(ReplayGuard().acceptIfNewer(1234))
    }

    @Test
    fun `newer timestamps are accepted, older and equal rejected`() {
        val guard = ReplayGuard()
        assertTrue(guard.acceptIfNewer(1000))
        assertTrue(guard.acceptIfNewer(2000))
        assertFalse(guard.acceptIfNewer(2000)) // replay
        assertFalse(guard.acceptIfNewer(1500)) // stale
        assertTrue(guard.acceptIfNewer(2001))
    }

    @Test
    fun `wraparound counts as newer`() {
        val guard = ReplayGuard()
        assertTrue(guard.acceptIfNewer(65500))
        assertTrue(guard.acceptIfNewer(10)) // 65500 -> 10 wraps forward by 46 ms
        assertFalse(guard.acceptIfNewer(65400)) // now behind
    }

    @Test
    fun `reset forgets history`() {
        val guard = ReplayGuard()
        assertTrue(guard.acceptIfNewer(2000))
        assertFalse(guard.acceptIfNewer(1000))
        guard.reset()
        assertTrue(guard.acceptIfNewer(1000))
    }
}
