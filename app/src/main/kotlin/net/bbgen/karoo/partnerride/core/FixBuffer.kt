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
class FixBuffer(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val fixes = ArrayDeque<GpsFix>()

    @Synchronized
    fun add(fix: GpsFix) {
        // GPS time must move forward; drop duplicates/out-of-order fixes.
        if (fixes.isNotEmpty() && fix.timeMs <= fixes.last().timeMs) return
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
    }
}
