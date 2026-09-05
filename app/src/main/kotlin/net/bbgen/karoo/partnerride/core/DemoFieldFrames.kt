package net.bbgen.karoo.partnerride.core

/**
 * Demo mode's frame list: every display state the data field can render, in a fixed order, one
 * every [FRAME_MS].
 *
 * Most of these states need two devices, a lost signal or a revoked permission to reach, so
 * cycling them is the only way to see each one rendered on a single device, in the actual page
 * slot, in seconds. That is how the truncation was found — a string too wide for its slot became
 * "150 …" with nothing said about it — and how a fix for it gets confirmed.
 *
 * Frames are synthetic [PartnerRideState] values pushed through the real [FieldState.build] rather
 * than a parallel list of hardcoded strings: what demo mode shows is then by construction what the
 * field shows, and it cannot drift when the state machine changes.
 */
object DemoFieldFrames {
    /** How long each frame stays up. A multiple of the field's 1 s update tick. */
    const val FRAME_MS = 2_000L

    /**
     * The synthetic "now" every frame is built against. Arbitrary — only the differences from the
     * per-frame timestamps matter — but far enough from 0 that a frame can sit in the past.
     */
    const val NOW = 1_000_000L

    /**
     * Ordered so the fresh gaps come first and the word labels last. Covers every branch of
     * [FieldState.build] except the page-editor preview, which is not part of the live path;
     * `DemoFieldFramesTest` fails if a branch loses its frame.
     */
    val frames: List<PartnerRideState> = listOf(
        gap(5.0, ahead = true, zone = GapZone.GREEN),
        gap(5.0, ahead = false, zone = GapZone.GREEN),
        gap(10.0, ahead = true, zone = GapZone.GREEN),
        gap(10.0, ahead = false, zone = GapZone.GREEN),
        gap(50.0, ahead = true, zone = GapZone.YELLOW),
        gap(50.0, ahead = false, zone = GapZone.YELLOW),
        gap(150.0, ahead = true, zone = GapZone.RED),
        gap(150.0, ahead = false, zone = GapZone.RED),
        // Stale: the shortest age the format can show is 5 s, not 1 s — below FRESH_MS the fresh
        // branch wins, so "~150 m · 1 s" is unreachable on a real ride.
        gap(150.0, ahead = true, zone = GapZone.RED, packetAgeMs = FieldState.FRESH_MS + 1),
        gap(150.0, ahead = true, zone = GapZone.RED, packetAgeMs = 30_000L),
        // The longest this format ever gets: past SIGNAL_LOST_MS the age is replaced by NO SIGNAL.
        gap(150.0, ahead = true, zone = GapZone.RED, packetAgeMs = FieldState.SIGNAL_LOST_MS),
        gap(150.0, ahead = true, zone = GapZone.RED, packetAgeMs = FieldState.SIGNAL_LOST_MS + 1),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(
            lastPacketElapsedMs = null,
            smoothedGapMeters = null,
        ),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(lastOwnFixElapsedMs = null),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(bluetoothReady = false),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(coupleCodeValid = false),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(
            missingPermissions = listOf("android.permission.BLUETOOTH_SCAN"),
        ),
        gap(150.0, ahead = true, zone = GapZone.RED).copy(serviceRunning = false),
    )

    /** Frames in one loop: the display states, plus the leading report frame. */
    val cycleLength: Int get() = frames.size + 1

    /** Which frame of the cycle a monotonic timestamp falls in; 0 is the report frame. Wraps forever. */
    fun frameIndex(elapsedMs: Long): Int = ((elapsedMs / FRAME_MS) % cycleLength).toInt()

    /**
     * What the field shows at [elapsedMs] while demo mode is on.
     *
     * [report] is the text of the leading frame: the `ViewConfig` Karoo handed the slot, which
     * only the view layer can see. It leads the cycle because it is the frame you go looking for
     * — it says both what geometry the field was given and, by existing at all, which build is
     * installed.
     */
    fun displayAt(elapsedMs: Long, report: String): FieldDisplay {
        val index = frameIndex(elapsedMs)
        return if (index == 0) {
            FieldDisplay(report, FieldBackground.GRAY)
        } else {
            FieldState.build(frames[index - 1], NOW)
        }
    }

    /** A healthy link with a partner [meters] away; the sign of the gap carries [ahead]. */
    private fun gap(
        meters: Double,
        ahead: Boolean,
        zone: GapZone,
        packetAgeMs: Long = 0L,
    ) = PartnerRideState(
        serviceRunning = true,
        bluetoothReady = true,
        advertising = true,
        scanning = true,
        coupleCodeValid = true,
        lastOwnFixElapsedMs = NOW,
        lastPacketElapsedMs = NOW - packetAgeMs,
        smoothedGapMeters = if (ahead) meters else -meters,
        partnerAhead = ahead,
        zone = zone,
    )
}
