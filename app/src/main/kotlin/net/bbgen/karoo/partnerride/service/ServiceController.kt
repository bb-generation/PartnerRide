package net.bbgen.karoo.partnerride.service

import android.content.Context
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.data.PartnerRideSettings

/**
 * Single place that maps settings -> link service state. Called from every path that can want
 * the link up — the extension service (Karoo OS binding us), [BootReceiver], the app opening,
 * the data field's startView, and the settings UI — so no single trigger is load-bearing.
 */
object ServiceController {
    fun sync(context: Context, settings: PartnerRideSettings) {
        val missing = PartnerLinkService.missingPermissions(context)
        when {
            !settings.enabled -> {
                PartnerLinkService.stop(context)
                // Disabled on purpose: the data field should say OFF, not NO PERM.
                GapRepository.update { it.copy(missingPermissions = emptyList()) }
            }
            missing.isEmpty() -> PartnerLinkService.start(context)
            else -> {
                PartnerLinkService.stop(context)
                // The service never comes up to report the problem itself, so record it here:
                // the data field shows NO PERM off this state.
                GapRepository.update { it.copy(missingPermissions = missing) }
            }
        }
    }
}
