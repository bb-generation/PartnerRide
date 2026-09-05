package net.bbgen.karoo.partnerride.data

/**
 * What a tap on the ride data field does to the settings.
 *
 * Two jobs in one gesture, because the field is the only PartnerRide surface reachable mid-ride —
 * the settings screen means leaving the ride pages, which is exactly what a rider will not do at
 * 30 km/h:
 *
 * - Demo mode on: leave demo mode. A field stuck cycling synthetic frames shows no real gap, so
 *   getting out of it always wins over anything else the tap could mean. It also gives demo mode
 *   a second exit next to the settings-screen switch (TECHNICAL.md §7.4), reachable from the ride
 *   page the demo is being watched on.
 * - Otherwise: flip [PartnerRideSettings.enabled], the same bit the settings screen's enable
 *   switch owns — so the link (BLE advertise + scan + GPS + wakelock) can be dropped and brought
 *   back without leaving the ride.
 *
 * Pure and separate from the file that owns the DataStore so it is unit-testable on the JVM.
 */
fun PartnerRideSettings.afterFieldTap(): PartnerRideSettings =
    if (demoMode) copy(demoMode = false) else copy(enabled = !enabled)
