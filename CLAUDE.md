# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PartnerRide — a Hammerhead Karoo (2/3) extension for two riders. Each device broadcasts its GPS
position over connectionless BLE advertising (no pairing/GATT) and shows the live signed
straight-line distance to the partner as a custom ride data field. One identical APK runs on both
devices. Built with the karoo-ext SDK; use the **hammerskill** skill for Karoo API questions.
`TECHNICAL.md` documents the wire format, time model, and gap algorithm — keep it in sync with
protocol or algorithm changes.

## Build and test

JDK 17+ is required but the system default is Java 11 — use Android Studio's JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test              # JVM unit tests (all core logic is covered here)
.\gradlew.bat assembleRelease   # debug-signed, sideloadable APK
.\gradlew.bat lint              # Android lint
```

Run a single test class: `.\gradlew.bat test --tests "net.bbgen.karoo.partnerride.core.GapEngineTest"`

The `io.hammerhead:karoo-ext` dependency comes from GitHub Packages and needs auth even though
it's public: `gpr.user`/`gpr.key` (PAT with `read:packages`) in the gitignored `local.properties`
(read by custom logic in `settings.gradle.kts`, which falls back to gradle properties and
`USERNAME`/`TOKEN` env vars for CI).

Deploy to a Karoo over adb: `adb install -r app\build\outputs\apk\release\app-release.apk`, then
**open the app once on the device** or the extension won't register. There is no way to exercise
the BLE link without two physical devices; everything testable without hardware lives in `core/`.

## Architecture

Two cooperating services in one process, bridged by a `StateFlow`:

- `service/PartnerLinkService` — foreground service (wakelock), owns all I/O: BLE advertising
  (AdvertisingSet API, payload updated in place per GPS fix), BLE scanning, GPS via
  `LocationManager`, and the gap alert. Runs whenever the extension is enabled in settings,
  independent of ride recording. Writes results into `core/GapRepository`.
- `extension/PartnerRideExtension` — the karoo-ext service Karoo OS binds to; exposes
  `PartnerRideDataType` (Glance→RemoteViews, reads `GapRepository`) and keeps the link service in
  sync with the enable setting (covers start-after-boot).
- `core/` — pure Kotlin with no Android dependencies, fully unit-tested on the JVM: packet
  codec, couple code, timestamp reconstruction + replay guard, fix ring buffer, gap engine,
  zone hysteresis. Monotonic "now" values are passed in as parameters so the logic stays
  testable; only the service layer touches `SystemClock`.

`ServiceController.sync()` is the only place that starts/stops the link service. It is called
redundantly from every path that can want the link up — the extension service, a
`BOOT_COMPLETED` receiver, `MainActivity.onResume`, the data field's `startView`, and the
settings UI — because Karoo OS may bind the extension late (or only once the data field is
shown). Don't remove one of these triggers because it "looks duplicated".

## Invariants that are easy to break

- Packet timestamps are `Location.getTime()` (satellite UTC), **never** `System.currentTimeMillis()`
  — device clocks drift between the two riders and would break the timestamp matching in
  `GapEngine`. This is why GPS comes from `LocationManager`, not karoo-ext's `OnLocationChanged`
  (which carries no GPS timestamp).
- Fixes from different times are never compared directly: `GapEngine` dead-reckons both
  positions to a common evaluation time (flat-earth extrapolation along each fix's speed/heading,
  capped at 3 s) and falls back to matching the partner fix against the *own fix closest in GPS
  time* (ring buffer) when the partner's speed/heading bytes are the `0xFF` sentinel or the cap
  is exceeded.
- The packet format is versioned (`PacketCodec.VERSION`); unknown versions and wrong sizes are
  silently dropped. The 19-byte layout (incl. speed/heading bytes with `0xFF` sentinels) is
  fixed for version 1; an encrypted format would be version 2. Both devices must run the same
  app version.
- BLE scans are stopped/restarted every ~20 min (Android demotes scans >30 min old), and scan
  starts are rate-limited in `startScanIfAllowed` (Android blocks >5 starts per 30 s).
- Alerts/beeps go through karoo-ext (`PlayBeepPattern` via `KarooSystemService`) — standard
  Android audio does not route to the Karoo buzzer. In-ride field UI is RemoteViews-only (Glance).
- Extension id `partnerride` (no dots) must match in `PartnerRideExtension`, `extension_info.xml`,
  and each `DataTypeImpl`'s typeId must have a `<DataType>` entry there.
- `PartnerRideSettings` is persisted as JSON with `ignoreUnknownKeys` — add fields with defaults
  only.
