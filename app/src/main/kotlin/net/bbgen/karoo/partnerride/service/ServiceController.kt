package net.bbgen.karoo.partnerride.service

import android.content.Context
import net.bbgen.karoo.partnerride.core.GapRepository

/**
 * Starts and stops the link service — and only on a user action: a tap on the data field, or the
 * settings screen's switch. Nothing starts it by itself: not boot, not Karoo OS binding the
 * extension, not the data field appearing.
 *
 * That is what makes the link work at all on Android 11+ (Karoo 3). A location foreground service
 * started while the app is in the background gets no GPS: LocationManager accepts the
 * registration and silently never delivers, so the field sat on NO GPS and nothing was broadcast
 * to the partner. Those automatic starts used to be exactly how the link came up after every
 * power-on. A field tap is sent by the Karoo app, which is on screen, and the settings screen is
 * ours, so a service started from either one gets GPS. See TECHNICAL.md §8.
 */
object ServiceController {
    fun start(context: Context) {
        if (recordMissingPermissions(context).isEmpty()) PartnerLinkService.start(context)
    }

    fun stop(context: Context) {
        PartnerLinkService.stop(context)
    }

    /**
     * Publishes the missing runtime permissions, so the field shows NO PERM *before* the rider
     * taps — a start could not succeed anyway — instead of a TAP TO START that does nothing.
     */
    fun recordMissingPermissions(context: Context): List<String> {
        val missing = PartnerLinkService.missingPermissions(context)
        GapRepository.update { it.copy(missingPermissions = missing) }
        return missing
    }
}
