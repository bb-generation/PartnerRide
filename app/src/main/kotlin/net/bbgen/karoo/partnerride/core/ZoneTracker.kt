package net.bbgen.karoo.partnerride.core

import kotlin.math.abs
import kotlin.math.roundToInt

enum class GapZone { GREEN, YELLOW, RED }

/**
 * Distance -> background color with ~2 m of hysteresis around both thresholds so GPS jitter near
 * a boundary doesn't strobe the color: green->yellow at 16 m but yellow->green at 14 m, etc.
 */
class ZoneTracker(
    private val greenMaxM: Double = 15.0,
    private val yellowMaxM: Double = 50.0,
    private val hysteresisM: Double = 1.0,
) {
    var zone: GapZone = GapZone.GREEN
        private set

    fun update(absGapM: Double): GapZone {
        zone = when (zone) {
            GapZone.GREEN -> when {
                absGapM > yellowMaxM + hysteresisM -> GapZone.RED
                absGapM > greenMaxM + hysteresisM -> GapZone.YELLOW
                else -> GapZone.GREEN
            }
            GapZone.YELLOW -> when {
                absGapM > yellowMaxM + hysteresisM -> GapZone.RED
                absGapM < greenMaxM - hysteresisM -> GapZone.GREEN
                else -> GapZone.YELLOW
            }
            GapZone.RED -> when {
                absGapM < greenMaxM - hysteresisM -> GapZone.GREEN
                absGapM < yellowMaxM - hysteresisM -> GapZone.YELLOW
                else -> GapZone.RED
            }
        }
        return zone
    }
}

/** Display rounding: 1 m steps up to 50 m, 5 m steps above. */
fun roundGapForDisplay(meters: Double): Int {
    val m = abs(meters)
    return if (m <= 50.0) m.roundToInt() else ((m / 5.0).roundToInt() * 5)
}
