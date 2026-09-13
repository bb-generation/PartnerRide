package net.bbgen.karoo.partnerride.core

/** Background of the data field; the view layer maps these to concrete colors. */
enum class FieldBackground { GREEN, YELLOW, RED, GRAY }

/**
 * What the field shows: a string and a background. Deliberately no font size — the view sizes the
 * text to the page slot it was given (TECHNICAL.md §7.2), which no state can know here.
 */
data class FieldDisplay(
    val text: String,
    val background: FieldBackground,
)

/**
 * The data field's display state machine: maps [PartnerRideState] plus a monotonic "now" to what
 * the field shows. Pure Kotlin so every transition is unit-testable on the JVM.
 *
 * When several things are wrong the first matching state wins, ordered so the label always names
 * the first problem the rider has to fix: permissions > running > Bluetooth > own GPS > partner
 * signal. Gray backgrounds mean "link not working, nobody is being dropped"; red is reserved for
 * the wide-gap zone and for losing a previously established partner signal mid-ride.
 */
object FieldState {
    /** Packet ages up to this show the live gap on the zone color. */
    const val FRESH_MS = 5_000L

    /** After [FRESH_MS] the last known value + age stays up; beyond this the signal counts as lost. */
    const val SIGNAL_LOST_MS = 60_000L

    /** An own fix older than this means we are no longer broadcasting a usable position. */
    const val OWN_FIX_STALE_MS = 10_000L

    fun build(state: PartnerRideState, nowElapsedMs: Long): FieldDisplay {
        if (state.missingPermissions.isNotEmpty()) return grayLabel("NO PERM")
        // Not an error: the link only ever starts from a user action (TECHNICAL.md §8), so after
        // every power-on this is what the field shows — and it says how to get out of it.
        if (!state.serviceRunning) return grayLabel("TAP TO START")
        if (!state.bluetoothReady) return grayLabel("NO BT")
        // Nothing is broadcast or matched without a full 6-digit code, so say so rather than
        // sitting on NO SIGNAL forever.
        if (!state.coupleCodeValid) return grayLabel("NO CODE")
        val ownFixAge = state.lastOwnFixElapsedMs?.let { nowElapsedMs - it }
        if (ownFixAge == null || ownFixAge > OWN_FIX_STALE_MS) return grayLabel("NO GPS")

        val packetAge = state.lastPacketElapsedMs?.let { nowElapsedMs - it }
        val gap = state.smoothedGapMeters
        if (packetAge == null || gap == null) {
            // No partner heard since the service started: calm gray, not alarm red.
            return grayLabel("NO SIGNAL")
        }
        if (packetAge > SIGNAL_LOST_MS) {
            // Had contact and lost it — mid-ride this usually means the gap blew past BLE range.
            return FieldDisplay("NO SIGNAL", FieldBackground.RED)
        }
        val meters = roundGapForDisplay(gap)
        if (packetAge <= FRESH_MS) {
            val arrow = if (state.partnerAhead) "▲" else "▼"
            val background = when (state.zone) {
                GapZone.GREEN -> FieldBackground.GREEN
                GapZone.YELLOW -> FieldBackground.YELLOW
                GapZone.RED -> FieldBackground.RED
            }
            return FieldDisplay("$meters m $arrow", background)
        }
        // Signal fading: last known value with its age, red until it counts as lost.
        return FieldDisplay("~$meters m · ${packetAge / 1000} s", FieldBackground.RED)
    }

    private fun grayLabel(text: String) = FieldDisplay(text, FieldBackground.GRAY)
}
