package net.bbgen.karoo.partnerride.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.bbgen.karoo.partnerride.data.afterFieldTap
import net.bbgen.karoo.partnerride.data.updateSettings

/**
 * Handles a tap on the ride data field: leaves demo mode, or toggles the link on/off
 * ([afterFieldTap] decides which).
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
        // Writing the settings is an async DataStore transaction: hold the broadcast open.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Same reasoning as BootReceiver: goAsync() gives ~10 s before the system calls
                // it an ANR, so time-box the write rather than risk never reaching finish().
                withTimeout(TAP_TIMEOUT_MS) {
                    // updateSettings returns the stored result, so the sync below acts on what
                    // was actually written rather than on a separately-collected snapshot.
                    val updated = app.updateSettings { it.afterFieldTap() }
                    ServiceController.sync(app, updated)
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
