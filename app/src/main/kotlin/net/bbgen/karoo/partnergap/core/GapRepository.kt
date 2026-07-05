package net.bbgen.karoo.partnergap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the data field and the settings status line need. All *ElapsedMs values are
 * SystemClock.elapsedRealtime() timestamps (monotonic), compared against "now" by consumers.
 */
data class PartnerGapState(
    val serviceRunning: Boolean = false,
    val bluetoothReady: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    /** Human-readable problem shown on the settings screen, null when healthy. */
    val statusMessage: String? = null,
    val lastOwnFixElapsedMs: Long? = null,
    val lastPacketElapsedMs: Long? = null,
    /** Smoothed signed gap; positive = partner ahead. Null until the first packet. */
    val smoothedGapMeters: Double? = null,
    val partnerAhead: Boolean = true,
    val zone: GapZone = GapZone.RED,
)

/**
 * Single in-process state shared between the foreground service (writer) and the data field view
 * + settings screen (readers). The extension service and the link service run in the same app
 * process, so a StateFlow is all that's needed.
 */
object GapRepository {
    private val _state = MutableStateFlow(PartnerGapState())
    val state: StateFlow<PartnerGapState> = _state.asStateFlow()

    fun update(transform: (PartnerGapState) -> PartnerGapState) = _state.update(transform)

    /** Called when the link service stops: keep last gap values but mark everything inactive. */
    fun serviceStopped(message: String? = null) = update {
        it.copy(
            serviceRunning = false,
            advertising = false,
            scanning = false,
            statusMessage = message,
        )
    }
}
