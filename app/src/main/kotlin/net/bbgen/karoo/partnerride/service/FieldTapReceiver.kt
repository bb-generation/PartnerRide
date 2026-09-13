package net.bbgen.karoo.partnerride.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.data.FieldTapAction
import net.bbgen.karoo.partnerride.data.fieldTapAction
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.data.updateSettings

/**
 * Handles a tap on the ride data field: leaves demo mode, or starts/stops the link
 * ([fieldTapAction] decides which).
 *
 * This is the main way the link gets started, and it has to be: the tap's broadcast is sent by
 * the Karoo app, which is on screen, so a service started from here is allowed GPS on Android 11+
 * where a background start is not (see [ServiceController]).
 *
 * The field is RemoteViews inflated in *Karoo's* process, so the tap cannot call back into us
 * directly — the only channel is a [PendingIntent], and [pendingIntent] builds the one the data
 * field attaches. Karoo sends it, but it is dispatched under our identity, which is why the
 * receiver can stay unexported.
 */
class FieldTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIELD_TAP) return
        val app = context.applicationContext
        // Reading (and for demo mode, writing) the settings is async: hold the broadcast open.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // goAsync() gives ~10 s before the system calls it an ANR, so time-box the
                // DataStore access rather than risk never reaching finish().
                withTimeout(TAP_TIMEOUT_MS) {
                    val demoMode = app.streamSettings().first().demoMode
                    val running = GapRepository.state.value.serviceRunning
                    when (fieldTapAction(demoMode, running)) {
                        FieldTapAction.LEAVE_DEMO_MODE -> app.updateSettings { it.copy(demoMode = false) }
                        FieldTapAction.START_LINK -> ServiceController.start(app)
                        FieldTapAction.STOP_LINK -> ServiceController.stop(app)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Field tap failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PartnerRide"
        private const val TAP_TIMEOUT_MS = 8_000L
        private const val ACTION_FIELD_TAP = "net.bbgen.karoo.partnerride.FIELD_TAP"
        private const val REQUEST_CODE = 1

        /**
         * The intent the data field fires on tap. Explicit (this receiver, our package) so an
         * unexported receiver can still be reached, and immutable because Karoo has no business
         * filling anything in — FLAG_IMMUTABLE exists since API 23, below Karoo 2's API 26.
         */
        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, FieldTapReceiver::class.java)
                .setAction(ACTION_FIELD_TAP)
            return PendingIntent.getBroadcast(
                context.applicationContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
