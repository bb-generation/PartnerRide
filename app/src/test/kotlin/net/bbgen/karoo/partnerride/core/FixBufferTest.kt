package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixBufferTest {

    @Test
    fun `empty buffer returns null`() {
        assertNull(FixBuffer().closestTo(1000L))
        assertNull(FixBuffer().latest())
    }

    @Test
    fun `finds the fix closest to a timestamp`() {
        val buffer = FixBuffer()
        for (t in 0..5) buffer.add(GpsFix(t * 1000L, t.toDouble(), 0.0))

        assertEquals(3000L, buffer.closestTo(2_600L)!!.timeMs)
        assertEquals(2000L, buffer.closestTo(2_400L)!!.timeMs)
        assertEquals(0L, buffer.closestTo(-500L)!!.timeMs)
        assertEquals(5000L, buffer.closestTo(99_999L)!!.timeMs)
    }

    @Test
    fun `prunes fixes older than the window behind the newest fix`() {
        val buffer = FixBuffer(windowMs = 5_000L)
        for (t in 0..10) buffer.add(GpsFix(t * 1000L, 0.0, 0.0))
        // Window is [5000, 10000]; a fix at 1000 must be gone.
        assertEquals(5000L, buffer.closestTo(0L)!!.timeMs)
        assertEquals(10000L, buffer.latest()!!.timeMs)
    }

    @Test
    fun `out-of-order and duplicate fixes are ignored`() {
        val buffer = FixBuffer()
        buffer.add(GpsFix(2000L, 1.0, 0.0))
        buffer.add(GpsFix(1000L, 9.0, 9.0)) // older: dropped
        buffer.add(GpsFix(2000L, 9.0, 9.0)) // duplicate: dropped
        assertEquals(1, buffer.snapshot().size)
        assertEquals(1.0, buffer.latest()!!.latDeg, 0.0)
    }

    @Test
    fun `a far-future timestamp does not wedge the buffer forever`() {
        val buffer = FixBuffer()
        for (t in 0..3) buffer.add(GpsFix(t * 1000L, 47.0, 15.0))

        // One bogus Location.getTime() a day into the future.
        buffer.add(GpsFix(86_400_000L, 1.0, 1.0))
        assertEquals(86_400_000L, buffer.latest()!!.timeMs)
        assertEquals(1, buffer.snapshot().size)

        // The next normal fix must be accepted, not rejected forever for being "older".
        buffer.add(GpsFix(4000L, 47.5, 15.5))
        assertEquals(4000L, buffer.latest()!!.timeMs)
        assertEquals(1, buffer.snapshot().size)

        // ...and the timeline continues normally from there.
        buffer.add(GpsFix(5000L, 47.6, 15.6))
        assertEquals(5000L, buffer.latest()!!.timeMs)
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `a normal GPS gap below the jump limit keeps accumulating`() {
        val buffer = FixBuffer()
        buffer.add(GpsFix(0L, 47.0, 15.0))
        // 30 s outage: under the 60 s jump limit, so it is ordinary forward progress. The
        // window prune drops the stale fix, but the new one is kept.
        buffer.add(GpsFix(30_000L, 47.1, 15.1))
        assertEquals(30_000L, buffer.latest()!!.timeMs)
        assertEquals(1, buffer.snapshot().size)
    }
}
