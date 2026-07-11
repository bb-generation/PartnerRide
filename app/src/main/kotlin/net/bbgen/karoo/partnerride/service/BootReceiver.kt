package net.bbgen.karoo.partnerride.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
                ServiceController.sync(context.applicationContext, context.streamSettings().first())
            } finally {
                pending.finish()
            }
        }
    }
}
