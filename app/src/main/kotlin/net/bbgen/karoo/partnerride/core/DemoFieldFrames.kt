package net.bbgen.karoo.partnerride.core

/**
 * Demo mode's frame list: every display state the data field can render, in a fixed order, one
 * every [FRAME_MS].
 *
 * The field truncates silently — it is a single-line Text at a fixed font size, so a string
 * too wide for the ride-page slot just becomes "…" — and most of these states need two devices,
 * a lost signal or a revoked permission to reach. Cycling them makes the truncating one
 * reproducible on a single device, in the actual page slot, in seconds.
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
     * Ordered so the fresh gaps (widest strings at full font size) come first and the word labels
     * last. Covers every branch of [FieldState.build] except the page-editor preview, which is not
     * part of the live path; `DemoFieldFramesTest` fails if a branch loses its frame.
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

    /** Which frame a monotonic timestamp falls in; wraps forever. */
    fun frameIndex(elapsedMs: Long): Int = ((elapsedMs / FRAME_MS) % frames.size).toInt()

    /** What the field shows at [elapsedMs] while demo mode is on. */
    fun displayAt(elapsedMs: Long): FieldDisplay =
        FieldState.build(frames[frameIndex(elapsedMs)], NOW)

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
