package net.bbgen.karoo.partnerride.core

import kotlin.math.abs

data class GapResult(
    /** Rolling average (last [GapEngine.smoothingWindow] values) of the signed gap. Positive = partner ahead. */
    val smoothedGapMeters: Double,
    val rawGapMeters: Double,
    val partnerAhead: Boolean,
)

/**
 * The core gap computation: dead reckoning with timestamp alignment, haversine, signed by own
 * heading. Both positions are extrapolated to a common evaluation time (the newer of the two fix
 * timestamps); when the partner packet carries no speed/heading (sentinel) or a fix is too old to
 * extrapolate, it falls back to matching the partner fix against the own fix closest in GPS time.
 *
 * Pure logic, no Android dependencies. The service feeds it own GPS fixes and decoded partner
 * packets; it recomputes on every received packet (never on a timer).
 */
class GapEngine(
    private val fixBuffer: FixBuffer = FixBuffer(),
    // 1 = smoothing effectively disabled. Was 3, but duty-cycled scanning (battery saver) already
    // spaces accepted packets several seconds apart, and averaging N of those multiplies the
    // display lag by N. Left as a constructor param (not deleted) so it's a one-line revert.
    private val smoothingWindow: Int = 1,
    /** No accepted packet for this long -> forget replay state so recovery is never blocked. */
    private val replayResetMs: Long = 60_000L,
    /** Minimum distance between two own fixes for a heading to count as reliable. */
    private val headingMinDistanceM: Double = 2.0,
    /** Never dead-reckon a position further than this; older fixes use the matching fallback. */
    private val maxExtrapolationMs: Long = 3_000L,
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

        // Reconstruct the partner's full GPS timestamp relative to our newest fix; fixes from
        // different times are never compared without alignment (extrapolation or matching).
        val partnerTimeMs = Timestamps.reconstruct(packet.timeMod, latestOwn.timeMs)
        val (ownPos, partnerPos) = alignPositions(packet, latestOwn, partnerTimeMs) ?: return null

        val distance = Geo.haversineMeters(ownPos.latDeg, ownPos.lonDeg, partnerPos.latDeg, partnerPos.lonDeg)

        val heading = latestOwn.bearingDeg ?: ownHeadingDeg()
        val ahead = if (heading != null && distance > 0.0) {
            // Sign = projection of the vector to the partner onto our own heading.
            val bearingToPartner =
                Geo.initialBearingDeg(ownPos.latDeg, ownPos.lonDeg, partnerPos.latDeg, partnerPos.lonDeg)
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

    /**
     * Aligns both positions to a common evaluation time — the newer of (own latest fix, partner
     * fix) — by dead-reckoning the older one forward along its speed/heading.
     *
     * Falls back to plain timestamp matching (partner fix vs the own fix closest to its GPS
     * time, no extrapolation) when the partner sent the invalid sentinel for speed/heading or
     * when either fix would need more than [maxExtrapolationMs] of extrapolation — anything
     * older is the staleness rules' problem, not dead reckoning's.
     */
    private fun alignPositions(
        packet: PartnerPacket,
        latestOwn: GpsFix,
        partnerTimeMs: Long,
    ): Pair<LatLon, LatLon>? {
        val evalTimeMs = maxOf(latestOwn.timeMs, partnerTimeMs)
        val partnerDtMs = evalTimeMs - partnerTimeMs
        val ownDtMs = evalTimeMs - latestOwn.timeMs

        val canDeadReckon = packet.speedMps != null && packet.headingDeg != null &&
            partnerDtMs <= maxExtrapolationMs && ownDtMs <= maxExtrapolationMs

        if (!canDeadReckon) {
            val matchedOwn = fixBuffer.closestTo(partnerTimeMs) ?: return null
            return LatLon(matchedOwn.latDeg, matchedOwn.lonDeg) to LatLon(packet.latDeg, packet.lonDeg)
        }

        val partnerPos = Geo.extrapolate(
            packet.latDeg, packet.lonDeg, packet.speedMps!!, packet.headingDeg!!, partnerDtMs / 1000.0,
        )
        val ownPos = if (latestOwn.speedMps != null && latestOwn.bearingDeg != null) {
            Geo.extrapolate(
                latestOwn.latDeg, latestOwn.lonDeg, latestOwn.speedMps, latestOwn.bearingDeg, ownDtMs / 1000.0,
            )
        } else {
            // No own speed/heading (standing still): the position isn't going anywhere.
            LatLon(latestOwn.latDeg, latestOwn.lonDeg)
        }
        return ownPos to partnerPos
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
