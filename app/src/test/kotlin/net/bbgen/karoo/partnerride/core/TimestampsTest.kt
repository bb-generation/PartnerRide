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

    @Test
    fun `the exact half-window resolves as past, agreeing with the replay guard`() {
        val reference = 1_000_000L
        val refMod = (reference % 65536L).toInt()

        // Exactly half a modulus away: the two candidates are equidistant, so the tie has to be
        // broken consistently. ReplayGuard calls a forward distance of 32768 "not newer", so
        // reconstruct must call it past.
        val halfAway = (refMod + 32768) % 65536
        assertEquals(reference - 32768L, Timestamps.reconstruct(halfAway, reference))
        assertFalse(ReplayGuard().also { it.acceptIfNewer(refMod) }.acceptIfNewer(halfAway))

        // One below the boundary is unambiguously newer, and both agree.
        val justInside = (refMod + 32767) % 65536
        assertEquals(reference + 32767L, Timestamps.reconstruct(justInside, reference))
        assertTrue(ReplayGuard().also { it.acceptIfNewer(refMod) }.acceptIfNewer(justInside))
    }

    @Test
    fun `reconstruct stays congruent at both ends of the offset range`() {
        val reference = 1_000_000L
        for (timeMod in intArrayOf(0, 1, 32_767, 32_768, 65_535)) {
            val full = Timestamps.reconstruct(timeMod, reference)
            assertEquals(timeMod.toLong(), full.mod(65536L))
            val offset = full - reference
            assertTrue("offset $offset out of range", offset in -32_768L..32_767L)
        }
    }

    private fun packet(timeMod: Int) = PartnerPacket(timeMod, 47.0, 15.0)

    @Test
    fun `chronological orders a batch by fix time`() {
        val sorted = Timestamps.chronological(listOf(packet(3000), packet(1000), packet(2000)))
        assertEquals(listOf(1000, 2000, 3000), sorted.map { it.timeMod })
    }

    @Test
    fun `chronological handles a batch straddling the mod-65536 wrap`() {
        // A ~2 s batch spanning the wrap: 65100, 65600 -> 64, 66100 -> 564.
        val sorted = Timestamps.chronological(listOf(packet(564), packet(65_100), packet(64)))
        assertEquals(listOf(65_100, 64, 564), sorted.map { it.timeMod })
    }

    @Test
    fun `chronological leaves trivial batches alone`() {
        assertEquals(emptyList<Int>(), Timestamps.chronological(emptyList()).map { it.timeMod })
        assertEquals(listOf(7), Timestamps.chronological(listOf(packet(7))).map { it.timeMod })
    }

    @Test
    fun `an ordered batch is accepted in full by the replay guard`() {
        val guard = ReplayGuard()
        val batch = Timestamps.chronological(listOf(packet(3000), packet(1000), packet(2000)))
        // Unsorted, the 3000 would be taken first and the rest rejected as replays.
        assertTrue(batch.all { guard.acceptIfNewer(it.timeMod) })
    }
}
