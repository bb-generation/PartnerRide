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
    /**
     * No accepted packet for this long -> forget replay state so recovery is never blocked.
     * Must stay below the replay guard's half-window — see [DEFAULT_REPLAY_RESET_MS].
     */
    private val replayResetMs: Long = DEFAULT_REPLAY_RESET_MS,
    /** Minimum distance between two own fixes for a heading to count as reliable. */
    private val headingMinDistanceM: Double = 2.0,
    /** Never dead-reckon a position further than this; older fixes use the matching fallback. */
    private val maxExtrapolationMs: Long = 3_000L,
) {
    private val replayGuard = ReplayGuard()
    private val recentGaps = ArrayDeque<Double>()
    private var lastSignAhead = true
    private var lastAcceptElapsedMs = Long.MIN_VALUE

    init {
        // 0 would leave recentGaps empty, making average() NaN and roundGapForDisplay throw.
        require(smoothingWindow >= 1) { "smoothingWindow must be >= 1, was $smoothingWindow" }
        // The reset must fire *before* the guard goes blind, never after: past the half-window
        // the mod-65536 comparison can no longer tell "newer" from "older" and every packet is
        // rejected until the reset finally lands.
        require(replayResetMs < REPLAY_AMBIGUITY_MS) {
            "replayResetMs ($replayResetMs) must stay below the replay guard's " +
                "$REPLAY_AMBIGUITY_MS ms half-window"
        }
    }

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
            // The partner has been gone long enough that pre-dropout values say nothing about
            // the gap now; averaging across the outage would drag the first reading back.
            recentGaps.clear()
        }
        if (!replayGuard.acceptIfNewer(packet.timeMod)) return null
        lastAcceptElapsedMs = nowElapsedMs

        // Reconstruct the partner's full GPS timestamp relative to our newest fix; fixes from
        // different times are never compared without alignment (extrapolation or matching).
        val partnerTimeMs = Timestamps.reconstruct(packet.timeMod, latestOwn.timeMs)
        val (ownPos, partnerPos, ownFix) = alignPositions(packet, latestOwn, partnerTimeMs)

        val distance = Geo.haversineMeters(ownPos.latDeg, ownPos.lonDeg, partnerPos.latDeg, partnerPos.lonDeg)

        // Heading must come from the same fix [ownPos] was derived from. On the matching fallback
        // that is an older fix, and pairing its position with the *current* heading flips the sign
        // through a corner: the bearing to the partner is measured from where we were, and
        // compared against where we are now pointing.
        val heading = ownFix.bearingDeg ?: ownHeadingDeg(ownFix)
        val ahead = if (heading != null && distance > 0.0) {
            // Sign = projection of the vector to the partner onto our own heading.
            val bearingToPartner =
                Geo.initialBearingDeg(ownPos.latDeg, ownPos.lonDeg, partnerPos.latDeg, partnerPos.lonDeg)
            abs(Geo.angleDiffDeg(bearingToPartner, heading)) < 90.0
        } else {
            lastSignAhead // heading unreliable (standing still): keep the last stable sign
        }
        lastSignAhead = ahead

        // Smooth the *magnitude* and apply the current sign, never the signed value: averaging
        // signed gaps makes +30 / -30 / +30 read as 10 m, so a real 30 m separation would show
        // green and skip the drop-off alert — and the sign oscillates exactly when riding side
        // by side, which is when that matters.
        recentGaps.addLast(distance)
        while (recentGaps.size > smoothingWindow) recentGaps.removeFirst()
        val smoothedMagnitude = recentGaps.average()

        return GapResult(
            smoothedGapMeters = if (ahead) smoothedMagnitude else -smoothedMagnitude,
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
    ): Alignment {
        val evalTimeMs = maxOf(latestOwn.timeMs, partnerTimeMs)
        val partnerDtMs = evalTimeMs - partnerTimeMs
        val ownDtMs = evalTimeMs - latestOwn.timeMs

        val canDeadReckon = packet.speedMps != null && packet.headingDeg != null &&
            partnerDtMs <= maxExtrapolationMs && ownDtMs <= maxExtrapolationMs

        if (!canDeadReckon) {
            // closestTo cannot return null here: onPartnerPacket already established a latest fix.
            val matchedOwn = fixBuffer.closestTo(partnerTimeMs) ?: latestOwn
            return Alignment(
                ownPos = LatLon(matchedOwn.latDeg, matchedOwn.lonDeg),
                partnerPos = LatLon(packet.latDeg, packet.lonDeg),
                ownFix = matchedOwn,
            )
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
        return Alignment(ownPos = ownPos, partnerPos = partnerPos, ownFix = latestOwn)
    }

    /**
     * Own heading *at* [fix]: [fix] vs the most recent earlier own fix >= 2 m away. Anchored on
     * the passed fix rather than the newest one so it stays consistent with the position the
     * bearing to the partner was measured from.
     */
    private fun ownHeadingDeg(fix: GpsFix): Double? {
        val fixes = fixBuffer.snapshot()
        val index = fixes.indexOfLast { it.timeMs <= fix.timeMs }
        if (index < 0) return null
        for (i in index - 1 downTo 0) {
            val older = fixes[i]
            if (Geo.haversineMeters(older.latDeg, older.lonDeg, fix.latDeg, fix.lonDeg) >= headingMinDistanceM) {
                return Geo.initialBearingDeg(older.latDeg, older.lonDeg, fix.latDeg, fix.lonDeg)
            }
        }
        return null
    }

    /** Both positions brought to a common evaluation time, plus the own fix [ownPos] came from. */
    private data class Alignment(
        val ownPos: LatLon,
        val partnerPos: LatLon,
        val ownFix: GpsFix,
    )

    companion object {
        /**
         * Beyond this, a partner [PartnerPacket.timeMod] is genuinely ambiguous: the packet only
         * carries the low 16 bits of the GPS fix time ([PacketCodec.TIME_MOD] = 65.536 s), so
         * "40 s newer" and "25.5 s older" are the same value and [ReplayGuard] resolves both as
         * a replay.
         */
        const val REPLAY_AMBIGUITY_MS = PacketCodec.TIME_MOD / 2

        /**
         * Deliberately below [REPLAY_AMBIGUITY_MS]. A partner out of range for longer than the
         * half-window comes back with a timeMod the guard reads as *older*, and — because the
         * reset clock only advances on an accepted packet — every packet is then dropped until
         * the reset lands. Resetting first closes that window at every dropout length.
         */
        const val DEFAULT_REPLAY_RESET_MS = 30_000L
    }
}
