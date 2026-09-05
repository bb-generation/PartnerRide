# CLAUDE.md

## What this is

PartnerRide — a Hammerhead Karoo (2/3) extension for two riders. Each device broadcasts its GPS
position over connectionless BLE advertising (no pairing/GATT) and shows the live signed
straight-line distance to the partner as a custom ride data field. One identical APK runs on both
devices. Built with the karoo-ext SDK; use the **hammerskill** skill for Karoo API questions.
`TECHNICAL.md` is the reference for the wire format, time model, and gap algorithm — keep it in
sync with protocol or algorithm changes.

## Build and test

JDK 17+ is required but the system default is Java 11 — use Android Studio's JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test              # JVM unit tests (all core logic is covered here)
.\gradlew.bat lint              # Android lint
.\gradlew.bat assembleRelease
```

Run a single test class — this needs the concrete `testDebugUnitTest` task, since `--tests` is
not a valid option on the aggregate `test` task ("Unknown command-line option '--tests'"):

```powershell
.\gradlew.bat testDebugUnitTest --tests "net.bbgen.karoo.partnerride.core.GapEngineTest"
```

The `io.hammerhead:karoo-ext` dependency comes from GitHub Packages and needs auth even though
it's public: `gpr.user`/`gpr.key` (PAT with `read:packages`) in the gitignored `local.properties`
(`settings.gradle.kts` has the gradle-property and env-var fallbacks CI uses).

Deploy over adb: `adb install -r app\build\outputs\apk\release\app-release.apk`, then **open the
app once on the device** or the extension won't register. There is no way to exercise the BLE
link without two physical devices; everything testable without hardware lives in `core/`.

## Release signing

`app/build.gradle.kts` documents where the key is resolved from. What isn't in the code:

- The release build must **always** use the same key. A device holding a real-key build refuses
  to install one signed differently (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only way out
  is an uninstall that loses the couple code and the rest of `PartnerRideSettings`. Regenerating
  the keystore would force that on every existing install.
- The keystore lives outside the repo — not just gitignored, never generated into it. Alias
  `partnerride`. Its filename is recorded only in the gitignored `build-signed-release.bat`;
  don't assume it matches an older commit's version of this file, it has been renamed before
  after a keystore mixup.
- `build-signed-release.bat` (repo root, gitignored) builds a real-key-signed APK on device
  without touching `local.properties`.
- Locally, an unconfigured `assembleRelease` silently debug-signs so a zero-setup build still
  works — such an APK must never reach a device that already has a real-key build. Under CI that
  same fallback is a hard failure instead (`verifyReleaseSigning`).

## Cutting a release

Bump `versionCode`/`versionName`, tag `vX.Y.Z`, publish a GitHub Release. That triggers
`.github/workflows/release.yml`, which attaches the APK plus a generated `manifest.json` and
`icon.png` at stable `.../releases/latest/download/...` URLs.

- The workflow fails if the tag and `versionName` disagree, so bump before tagging.
- Both devices must run the same version, so there is no partial-rollout path — a release is an
  all-or-nothing swap for both riders.
- `.github/workflows/ci.yml` runs tests, lint and a debug build on pushes to `master` and on
  pull requests — a push to a feature branch triggers nothing until a PR exists for it. It
  uploads the debug APK as the `debug-apk` artifact; TECHNICAL.md §11.3 covers why that APK
  cannot simply be installed over a real-key build.

## Update discovery (currently off)

`AndroidManifest.xml` has **no** `io.hammerhead.karooext.MANIFEST_URL`, deliberately. That URL has
to be fetchable with no credentials, and GitHub returns 404 on a private repo's release assets to
anyone unauthenticated. Karoo OS and the phone companion app have no GitHub session — only a
browser does — so 1.6.0 and 1.6.1, the first releases to carry it, failed with "download failed"
on every install, while 1.5.1 (no `MANIFEST_URL`) installed fine over the same companion-app flow.

Add it back **only once this repository is public**, pointing at
`https://github.com/bb-generation/PartnerRide/releases/latest/download/manifest.json`. Verify
first, unauthenticated:

```powershell
curl.exe -sIL -o NUL -w "%{http_code}\n" https://github.com/bb-generation/PartnerRide/releases/latest/download/manifest.json
```

200 means it will work; 404 means it will not. The `generateManifest` task and the workflow's
manifest/icon upload are left in place on purpose, so the assets are already published and correct
whenever that switch is flipped.

Until then, getting a build onto a device means transferring the APK by hand (phone browser →
companion app) or `adb install`; nothing checks for updates on its own.

## Architecture

Two cooperating services in one process, bridged by a `StateFlow` in `core/GapRepository`:

- `service/PartnerLinkService` — foreground service (wakelock), owns all I/O: BLE advertising
  (payload updated in place per GPS fix), BLE scanning, GPS via `LocationManager`, and the gap
  alert. Runs whenever the extension is enabled, independent of ride recording.
- `extension/PartnerRideExtension` — the karoo-ext service Karoo OS binds to; exposes
  `PartnerRideDataType` (RemoteViews from `res/layout/partner_gap_field.xml`).
- `core/` — pure Kotlin, no Android dependencies, fully unit-tested on the JVM. Monotonic "now"
  values are passed in as parameters so the logic stays testable; only the service layer touches
  `SystemClock`.

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
- Fixes from different times are never compared directly: `GapEngine` dead-reckons both positions
  to a common evaluation time (capped at 3 s) and otherwise falls back to matching the partner
  fix against the own fix closest in GPS time — dropping the packet if nothing is close enough.
- The packet format is versioned (`PacketCodec.VERSION`); anything failing validation is silently
  dropped. The 19-byte layout is fixed for version 1; an encrypted format would be version 2.
  Both devices must run the same app version.
- BLE scans are stopped/restarted every ~20 min (Android demotes scans >30 min old), and scan
  starts are rate-limited in `startScanIfAllowed` (Android blocks >5 starts per 30 s).
- All BLE state mutation is confined to the `partnerride-link` handler thread — BLE callbacks and
  `onDestroy` post onto it rather than writing directly. Nothing enforces this at compile time.
- Scan mode is hardcoded to `SCAN_MODE_LOW_LATENCY` (continuous), with no scan-result batching
  and a ~100 ms advertising interval. Duty-cycled `SCAN_MODE_BALANCED` + `setReportDelay(2 s)` +
  ~250 ms advertising shipped in 1.5.0–1.6.2 to save battery and lost the partner for 15–30 s at a
  time on real rides; 1.6.3 reverted all three. Don't trade latency for battery here again.
  There is also deliberately no user-facing battery/latency setting — a Performance/Battery-Saver
  toggle was tried and removed because riders have no way to judge that tradeoff; don't
  reintroduce one without being asked.
- Alerts/beeps go through karoo-ext (`PlayBeepPattern` via `KarooSystemService`) — standard
  Android audio does not route to the Karoo buzzer. In-ride field UI is RemoteViews-only: a
  plain layout whose `TextView` autosizes its own text against the slot it was given, and whose
  background is a rounded-rect drawable matching Karoo's own field corners (TECHNICAL.md §7.2,
  §7.3). Don't set the field's text size from code — `setTextSize` is a no-op under autosizing —
  and don't give it a flat background color, which squares off those corners.
- A tap on the field leaves demo mode, or else toggles `enabled` (TECHNICAL.md §7.1). The field
  runs in Karoo's process, so the only channel back is a `PendingIntent` — `FieldTapReceiver`
  owns both it and the unexported receiver it fires. Re-attach it on every emission (each update
  is a fresh `RemoteViews`, not a patch) and never in `config.preview`.
- Extension id `partnerride` (no dots) must match in `PartnerRideExtension`, `extension_info.xml`,
  and each `DataTypeImpl`'s typeId must have a `<DataType>` entry there.
- Demo mode (data field cycles every display state, `core/DemoFieldFrames`) is reached only by
  tapping the settings-screen title 7 times. The indication-less `clickable` on that title, the
  `if (settings.demoMode)` section that turns it back off, and the field tap taking priority over
  the enable toggle while it is on are all deliberate — riders never need this, and one stuck in
  demo mode has a useless field. Don't give it a visible entry point.
- `PartnerRideSettings` is persisted as JSON with `ignoreUnknownKeys` — add fields with defaults
  only. Change it through `updateSettings {}` (read-modify-write inside the DataStore
  transaction), never by saving a separately-collected snapshot.
