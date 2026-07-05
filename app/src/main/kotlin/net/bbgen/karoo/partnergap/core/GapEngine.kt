package net.bbgen.karoo.partnergap.core

import kotlin.math.abs

data class GapResult(
    /** Rolling average (last [GapEngine.smoothingWindow] values) of the signed gap. Positive = partner ahead. */
    val smoothedGapMeters: Double,
    val rawGapMeters: Double,
    val partnerAhead: Boolean,
)

/**
 * The core gap computation: timestamp-matched, haversine, signed by own heading.
 *
 * Pure logic, no Android dependencies. The service feeds it own GPS fixes and decoded partner
 * packets; it recomputes on every received packet (never on a timer).
 */
class GapEngine(
    private val fixBuffer: FixBuffer = FixBuffer(),
    private val smoothingWindow: Int = 3,
    /** No accepted packet for this long -> forget replay state so recovery is never blocked. */
    private val replayResetMs: Long = 60_000L,
    /** Minimum distance between two own fixes for a heading to count as reliable. */
    private val headingMinDistanceM: Double = 2.0,
) {
    private val replayGuard = ReplayGuard()
    private val recentGaps = ArrayDeque<Double>()
    private var lastSignAhead = true
    private var lastAcceptElapsedMs = Long.MIN_VALUE

    fun onOwnFix(fix: GpsFix) {
        fixBuffer.add(fix)
    }

    fun latestOwnFix(): GpsFix? = fixBuffer.latest()

    /**
     * Processes one received partner packet. [nowElapsedMs] is a monotonic clock
     * (SystemClock.elapsedRealtime), used only for the replay-guard reset.
     *
     * Returns null when the packet is a replay/stale duplicate or no own fix exists yet.
     */
    fun onPartnerPacket(packet: PartnerPacket, nowElapsedMs: Long): GapResult? {
        val latestOwn = fixBuffer.latest() ?: return null

        if (lastAcceptElapsedMs != Long.MIN_VALUE && nowElapsedMs - lastAcceptElapsedMs > replayResetMs) {
            replayGuard.reset()
        }
        if (!replayGuard.acceptIfNewer(packet.timeMod)) return null
        lastAcceptElapsedMs = nowElapsedMs

        // Reconstruct the partner's full GPS timestamp relative to our newest fix, then compare
        // against the own fix closest to that moment — not against the current position.
        val partnerTimeMs = Timestamps.reconstruct(packet.timeMod, latestOwn.timeMs)
        val matchedOwn = fixBuffer.closestTo(partnerTimeMs) ?: return null

        val distance = Geo.haversineMeters(matchedOwn.latDeg, matchedOwn.lonDeg, packet.latDeg, packet.lonDeg)

        val heading = ownHeadingDeg()
        val ahead = if (heading != null && distance > 0.0) {
            // Sign = projection of the vector to the partner onto our own heading.
            val bearingToPartner =
                Geo.initialBearingDeg(matchedOwn.latDeg, matchedOwn.lonDeg, packet.latDeg, packet.lonDeg)
            abs(Geo.angleDiffDeg(bearingToPartner, heading)) < 90.0
        } else {
            lastSignAhead // heading unreliable (standing still): keep the last stable sign
        }
        lastSignAhead = ahead

        val signed = if (ahead) distance else -distance
        recentGaps.addLast(signed)
        while (recentGaps.size > smoothingWindow) recentGaps.removeFirst()

        return GapResult(
            smoothedGapMeters = recentGaps.average(),
            rawGapMeters = distance,
            partnerAhead = ahead,
        )
    }

    /** Own heading from recent own fixes: newest fix vs the most recent fix >= 2 m away. */
    private fun ownHeadingDeg(): Double? {
        val fixes = fixBuffer.snapshot()
        val newest = fixes.lastOrNull() ?: return null
        for (i in fixes.size - 2 downTo 0) {
            val older = fixes[i]
            if (Geo.haversineMeters(older.latDeg, older.lonDeg, newest.latDeg, newest.lonDeg) >= headingMinDistanceM) {
                return Geo.initialBearingDeg(older.latDeg, older.lonDeg, newest.latDeg, newest.lonDeg)
            }
        }
        return null
    }
}
