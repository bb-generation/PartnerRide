package net.bbgen.karoo.partnerride.core

import kotlin.math.abs

/**
 * One own GPS fix. [timeMs] is Location.getTime() — GPS (satellite) time, never the device clock.
 * [speedMps]/[bearingDeg] come from Location.getSpeed()/getBearing() and are null when the fix
 * doesn't carry them (e.g. standing still).
 */
data class GpsFix(
    val timeMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
)

/**
 * Ring buffer of the device's own recent GPS fixes, kept for [windowMs] behind the newest fix.
 * A received partner fix is matched against the own fix closest in GPS time — never against the
 * current position.
 */
class FixBuffer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /** |Δt| beyond which the GPS clock is treated as having jumped rather than advanced. */
    private val maxJumpMs: Long = DEFAULT_MAX_JUMP_MS,
) {
    private val fixes = ArrayDeque<GpsFix>()

    @Synchronized
    fun add(fix: GpsFix) {
        val newest = fixes.lastOrNull()
        if (newest != null && abs(fix.timeMs - newest.timeMs) > maxJumpMs) {
            // A discontinuity, not motion. One Location.getTime() far in the future used to
            // evict the whole buffer and then reject every subsequent (correct) fix forever,
            // since they all compare <= against the poisoned value — and the service kept
            // reporting a fresh own fix, so the field showed a gap against a frozen position
            // instead of NO GPS. Restart the timeline from this fix: whichever of the two was
            // wrong, the next real fix re-establishes it.
            fixes.clear()
            fixes.addLast(fix)
            return
        }
        // GPS time must move forward; drop duplicates/out-of-order fixes.
        if (newest != null && fix.timeMs <= newest.timeMs) return
        fixes.addLast(fix)
        while (fixes.first().timeMs < fix.timeMs - windowMs) fixes.removeFirst()
    }

    @Synchronized
    fun latest(): GpsFix? = fixes.lastOrNull()

    @Synchronized
    fun closestTo(timeMs: Long): GpsFix? = fixes.minByOrNull { abs(it.timeMs - timeMs) }

    @Synchronized
    fun snapshot(): List<GpsFix> = fixes.toList()

    @Synchronized
    fun clear() = fixes.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L

        /**
         * Generous enough that a real GPS outage never trips it by accident — and harmless when
         * it does, since a gap that long would prune the buffer empty anyway.
         */
        const val DEFAULT_MAX_JUMP_MS = 60_000L
    }
}
