package net.bbgen.karoo.partnergap.service

import android.content.Context
import net.bbgen.karoo.partnergap.data.PartnerGapSettings

/**
 * Single place that maps settings -> link service state, called from the extension service
 * (covers Karoo OS binding us after boot) and from the settings UI (covers toggling).
 */
object ServiceController {
    fun sync(context: Context, settings: PartnerGapSettings) {
        if (settings.enabled && PartnerLinkService.missingPermissions(context).isEmpty()) {
            PartnerLinkService.start(context)
        } else {
            PartnerLinkService.stop(context)
        }
    }
}
