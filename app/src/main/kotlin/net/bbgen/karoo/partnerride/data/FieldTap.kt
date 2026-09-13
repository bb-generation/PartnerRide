package net.bbgen.karoo.partnerride.data

/** What a tap on the ride data field does. */
enum class FieldTapAction { LEAVE_DEMO_MODE, START_LINK, STOP_LINK }

/**
 * Decides what a tap on the ride data field does.
 *
 * Two jobs in one gesture, because the field is the only PartnerRide surface reachable mid-ride —
 * the settings screen means leaving the ride pages, which is exactly what a rider will not do at
 * 30 km/h:
 *
 * - Demo mode on: leave demo mode. A field stuck cycling synthetic frames shows no real gap, so
 *   getting out of it always wins over anything else the tap could mean. It also gives demo mode
 *   a second exit next to the settings-screen switch (TECHNICAL.md §7.4), reachable from the ride
 *   page the demo is being watched on.
 * - Otherwise: start the link if it is not running, stop it if it is. The tap is also the normal
 *   way to *start* it — the link never starts by itself (TECHNICAL.md §8).
 *
 * Pure so it is unit-testable on the JVM.
 */
fun fieldTapAction(demoMode: Boolean, linkRunning: Boolean): FieldTapAction = when {
    demoMode -> FieldTapAction.LEAVE_DEMO_MODE
    linkRunning -> FieldTapAction.STOP_LINK
    else -> FieldTapAction.START_LINK
}
