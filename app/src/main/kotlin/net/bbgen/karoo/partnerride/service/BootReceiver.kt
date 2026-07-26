package net.bbgen.karoo.partnerride.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.bbgen.karoo.partnerride.data.streamSettings

/**
 * Brings the link service up right after boot. The extension service does the same when Karoo OS
 * binds it, but that bind can happen late (or only once the data field is first shown) — this
 * receiver makes the enable setting effective without any UI interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // The settings live in a DataStore (async read): finish the broadcast via goAsync.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A BroadcastReceiver held open by goAsync() has roughly 10 s before the system
                // treats it as an ANR. If the DataStore read never completes, finish() would
                // never be reached; time-box it so the receiver always ends cleanly.
                withTimeout(SYNC_TIMEOUT_MS) {
                    ServiceController.sync(context.applicationContext, context.streamSettings().first())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Boot sync failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PartnerRide"
        const val SYNC_TIMEOUT_MS = 8_000L
    }
}
