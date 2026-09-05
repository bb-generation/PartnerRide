package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFieldFramesTest {

    /** What each frame must render, in cycle order. This table is the feature's specification. */
    private val expected = listOf(
        Pair("5 m ▲", FieldBackground.GREEN),
        Pair("5 m ▼", FieldBackground.GREEN),
        Pair("10 m ▲", FieldBackground.GREEN),
        Pair("10 m ▼", FieldBackground.GREEN),
        Pair("50 m ▲", FieldBackground.YELLOW),
        Pair("50 m ▼", FieldBackground.YELLOW),
        Pair("150 m ▲", FieldBackground.RED),
        Pair("150 m ▼", FieldBackground.RED),
        Pair("~150 m · 5 s", FieldBackground.RED),
        Pair("~150 m · 30 s", FieldBackground.RED),
        Pair("~150 m · 60 s", FieldBackground.RED),
        Pair("NO SIGNAL", FieldBackground.RED),
        Pair("NO SIGNAL", FieldBackground.GRAY),
        Pair("NO GPS", FieldBackground.GRAY),
        Pair("NO BT", FieldBackground.GRAY),
        Pair("NO CODE", FieldBackground.GRAY),
        Pair("NO PERM", FieldBackground.GRAY),
        Pair("OFF", FieldBackground.GRAY),
    )

    private fun rendered(state: PartnerRideState) = FieldState.build(state, DemoFieldFrames.NOW)

    @Test
    fun `every frame renders its expected text and background`() {
        assertEquals(expected.size, DemoFieldFrames.frames.size)
        DemoFieldFrames.frames.forEachIndexed { i, state ->
            val display = rendered(state)
            val (text, background) = expected[i]
            assertEquals("frame $i text", text, display.text)
            assertEquals("frame $i background", background, display.background)
        }
    }

    @Test
    fun `no frame repeats its neighbour`() {
        // distinctUntilChanged sits between the frame source and the field, so two identical
        // neighbours would silently merge into one 4 s frame instead of two 2 s ones.
        DemoFieldFrames.frames.map { rendered(it) }.zipWithNext { a, b ->
            assertTrue("adjacent frames render identically: $a", a != b)
        }
    }

    @Test
    fun `the frames cover every display state FieldState can produce`() {
        // A new branch in FieldState.build without a frame for it defeats the whole point of
        // demo mode, so pin the covered set here rather than trusting the list above to be kept
        // up to date by hand.
        val covered = DemoFieldFrames.frames.map { rendered(it) }.toSet()
        val everyState = listOf(
            PartnerRideState(missingPermissions = listOf("x")),
            PartnerRideState(serviceRunning = false),
            healthy().copy(bluetoothReady = false),
            healthy().copy(coupleCodeValid = false),
            healthy().copy(lastOwnFixElapsedMs = null),
            healthy().copy(lastPacketElapsedMs = null, smoothedGapMeters = null),
            healthy(packetAgeMs = FieldState.SIGNAL_LOST_MS + 1),
            healthy(),
            healthy(packetAgeMs = FieldState.FRESH_MS + 1),
        )
        everyState.forEach { state ->
            val display = rendered(state)
            val match = covered.any {
                it.text.matchesShape(display.text) && it.background == display.background
            }
            assertTrue("no demo frame covers $display", match)
        }
    }

    @Test
    fun `frameIndex advances once per FRAME_MS and wraps`() {
        assertEquals(0, DemoFieldFrames.frameIndex(0L))
        assertEquals(0, DemoFieldFrames.frameIndex(DemoFieldFrames.FRAME_MS - 1))
        assertEquals(1, DemoFieldFrames.frameIndex(DemoFieldFrames.FRAME_MS))
        val cycleMs = DemoFieldFrames.FRAME_MS * DemoFieldFrames.cycleLength
        assertEquals(0, DemoFieldFrames.frameIndex(cycleMs))
        assertEquals(1, DemoFieldFrames.frameIndex(cycleMs + DemoFieldFrames.FRAME_MS))
        assertEquals(
            DemoFieldFrames.cycleLength - 1,
            DemoFieldFrames.frameIndex(cycleMs - 1),
        )
    }

    @Test
    fun `the cycle leads with the report frame and then walks the frames in order`() {
        val lead = DemoFieldFrames.displayAt(0L, "60x15 480x120 t44")
        assertEquals("60x15 480x120 t44", lead.text)
        assertEquals(FieldBackground.GRAY, lead.background)
        DemoFieldFrames.frames.indices.forEach { i ->
            val display = DemoFieldFrames.displayAt((i + 1) * DemoFieldFrames.FRAME_MS, "report")
            assertEquals(expected[i].first, display.text)
        }
    }

    @Test
    fun `the report frame is one frame long, like every other`() {
        assertEquals("report", DemoFieldFrames.displayAt(DemoFieldFrames.FRAME_MS - 1, "report").text)
        assertEquals(expected[0].first, DemoFieldFrames.displayAt(DemoFieldFrames.FRAME_MS, "report").text)
    }

    @Test
    fun `the shortest stale age the field can show is 5 s, not 1 s`() {
        // Why frame 9 is "~150 m · 5 s": below FRESH_MS the fresh branch wins, so a one-second-old
        // packet renders as a live gap and "~150 m · 1 s" never appears on a real ride.
        val justStale = healthy(packetAgeMs = FieldState.FRESH_MS + 1)
        assertEquals("~12 m · 5 s", rendered(justStale).text)
        assertEquals("12 m ▲", rendered(healthy(packetAgeMs = 1_000L)).text)
    }

    /** Digits differ between the fixture gaps and the frames; only the shape has to match. */
    private fun String.matchesShape(other: String) =
        replace(Regex("\\d+"), "#") == other.replace(Regex("\\d+"), "#")

    private fun healthy(packetAgeMs: Long = 1_000L) = PartnerRideState(
        serviceRunning = true,
        bluetoothReady = true,
        lastOwnFixElapsedMs = DemoFieldFrames.NOW,
        lastPacketElapsedMs = DemoFieldFrames.NOW - packetAgeMs,
        smoothedGapMeters = 12.0,
        partnerAhead = true,
        zone = GapZone.GREEN,
    )
}
