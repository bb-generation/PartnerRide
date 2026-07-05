package net.bbgen.karoo.partnergap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneTrackerTest {

    @Test
    fun `plain zones without boundary jitter`() {
        val tracker = ZoneTracker()
        assertEquals(GapZone.GREEN, tracker.update(5.0))
        assertEquals(GapZone.YELLOW, tracker.update(30.0))
        assertEquals(GapZone.RED, tracker.update(80.0))
        assertEquals(GapZone.GREEN, tracker.update(5.0))
    }

    @Test
    fun `hysteresis at the green-yellow boundary`() {
        val tracker = ZoneTracker()
        assertEquals(GapZone.GREEN, tracker.update(15.5)) // above 15 but below 16: stays green
        assertEquals(GapZone.YELLOW, tracker.update(16.5)) // green -> yellow above 16
        assertEquals(GapZone.YELLOW, tracker.update(14.5)) // below 15 but above 14: stays yellow
        assertEquals(GapZone.GREEN, tracker.update(13.5)) // yellow -> green below 14
    }

    @Test
    fun `hysteresis at the yellow-red boundary`() {
        val tracker = ZoneTracker()
        tracker.update(30.0) // yellow
        assertEquals(GapZone.YELLOW, tracker.update(50.5)) // stays yellow until 51
        assertEquals(GapZone.RED, tracker.update(51.5))
        assertEquals(GapZone.RED, tracker.update(49.5)) // stays red until below 49
        assertEquals(GapZone.YELLOW, tracker.update(48.5))
    }

    @Test
    fun `signal-loss values far over the boundary jump straight to red`() {
        val tracker = ZoneTracker()
        assertEquals(GapZone.RED, tracker.update(200.0))
    }

    @Test
    fun `display rounding is 1 m up to 50 m and 5 m above`() {
        assertEquals(42, roundGapForDisplay(42.4))
        assertEquals(43, roundGapForDisplay(42.5))
        assertEquals(50, roundGapForDisplay(49.9))
        assertEquals(180, roundGapForDisplay(181.9))
        assertEquals(185, roundGapForDisplay(183.0))
        // Sign does not affect the displayed magnitude.
        assertEquals(42, roundGapForDisplay(-42.4))
    }
}
