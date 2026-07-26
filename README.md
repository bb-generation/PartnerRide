# PartnerRide

A [Hammerhead Karoo](https://www.hammerhead.io/) extension for two riders. Each Karoo broadcasts
its own GPS position over connectionless BLE advertising and receives the partner's; a custom
ride data field shows the live straight-line distance to the partner, signed by who is ahead
(`42 m ▲` = partner 42 m ahead, `▼` = behind), on a green/yellow/red background.

One identical APK runs on both riders' devices — no pairing, no connection, identical roles.

Developer-level details (BLE transport, payload byte layout, time model, gap algorithm) live in
[TECHNICAL.md](TECHNICAL.md).

## How it works

- Both devices simultaneously advertise (legacy BLE, manufacturer data, ~250 ms interval, max TX
  power) and scan (duty-cycled to save battery, with results batched so updates land roughly
  every 2–3 s). The 19-byte packet carries a format version, an app magic constant, a 4-byte
  couple-code tag, the GPS fix timestamp (mod 65536, from satellite time), lat/lon, and the speed
  and heading of the fix (1 byte each, `0xFF` = unknown).
- The gap is computed by *dead reckoning with timestamp alignment*: both positions are
  extrapolated to a common evaluation time (the newer of the two fix timestamps) along their own
  speed and heading, capped at 3 s. If the partner's speed/heading are unknown (or a fix is too
  old to extrapolate), the receiver falls back to matching the partner's fix against its own fix
  from the same GPS moment (ring buffer of the last ~5 s). Either way, fixes from different times
  are never compared directly, so the gap stays accurate despite packet latency. Distance is
  straight-line (haversine); the ahead/behind sign is the projection of the vector to the partner
  onto the rider's own heading.
- The display is unsmoothed (each accepted packet's raw gap is shown directly) to keep latency low
  under duty-cycled scanning; a rolling-average smoothing option exists in the code but is
  currently disabled. The background color has ~2 m of hysteresis at the 15 m and 50 m
  thresholds.
- Both riders enter the same 6-digit couple code (e.g. `428713`); only packets with a matching
  code tag are accepted. Optional gap alert: beep + full-screen flash the first
  time the gap exceeds a threshold, re-armed when the gap closes again.

## Privacy

- **Nothing leaves the device except the BLE broadcast.** PartnerRide has no `INTERNET`
  permission, makes no network calls, and has no server, no analytics, and no crash/telemetry
  reporting of any kind. Settings (couple code, alert threshold) are stored locally on-device only.
- **The BLE broadcast itself is plaintext, not encrypted.** Each packet carries your live GPS
  position (lat/lon, speed, heading) and the 4-byte couple-code tag in the clear (see
  [TECHNICAL.md](TECHNICAL.md) for the exact layout). Anyone running a BLE sniffer within range
  (roughly 50–150 m, the same range the link itself works at) can read this — there is no pairing
  or connection to keep it private, by design (that's what makes the link work without either
  device touching the internet or a phone). The packet format is versioned so an encrypted v2
  could be added later without breaking older devices.
- Practically: this is the same trust model as an uncoded ANT+/BLE power meter or heart-rate
  broadcast — anyone nearby with the right receiver can listen in, but it's a live, local-only
  signal, not something collected, logged, or retained anywhere, by this extension or anyone else.

## Build

Requirements: JDK 17, and GitHub Packages credentials for the `karoo-ext` dependency (it is
public, but GitHub Packages still requires authentication):

1. Create a GitHub personal access token (classic) with only the `read:packages` scope
   (github.com → Settings → Developer settings → Personal access tokens).
2. Create `local.properties` in the project root (never commit it):

   ```properties
   gpr.user=<your github username>
   gpr.key=<the token>
   ```

3. Build:

   ```bash
   ./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
   ./gradlew test              # unit tests (packet codec, timestamps, gap engine, ...)
   ```

Official releases (the APK attached to each [GitHub Release](../../releases)) are signed with a
real, stable key by CI, so installing a newer release over an older one just updates the app in
place — settings (including the couple code) are preserved. A local `./gradlew assembleRelease`
without that signing key configured falls back to **debug-signing** instead, which is a different
key: a debug-signed build can't be installed over an official release (or vice versa) without
uninstalling first, which wipes settings. That's fine for development, but **don't distribute a
debug-signed APK as an update to someone already running an official release** — point people at
the GitHub Releases page instead.

## Sideload onto the Karoo 3

Grab `app-release.apk` from the [latest release](../../releases/latest) (or build it yourself per
above). Via the Hammerhead companion app (no cables):

1. Install the **Hammerhead Companion** app on your phone and pair it with the Karoo.
2. Copy `app-release.apk` to the phone (AirDrop/Drive/USB/email...).
3. Open the APK on the phone (tap it in your file manager or the "share" sheet) and choose to
   open/share it with the Hammerhead Companion app → it installs onto the paired Karoo.
4. **On the Karoo, open the PartnerRide app once from the launcher** — extensions register with
   Karoo OS only after the first launch.

Alternative via adb: enable Developer Options on the Karoo (Settings → About → tap Build Number
repeatedly), enable USB debugging, then `adb install -r app-release.apk` — and still open the app
once.

Repeat for **both** devices — the same APK (and the same version) goes on both; the packet
format is validated strictly, so mismatched versions simply won't see each other.

## First-time setup (both devices)

1. On the Karoo: open PartnerRide. Grant the Bluetooth and Location permissions when prompted.
2. **Device A:** tap **Generate** — it creates a 6-digit code like `428713`.
3. **Device B:** type exactly those 6 digits into the couple-code field.
4. Enable **PartnerRide enabled** on both devices. The status section should show
   *Broadcasting: yes* and, once both have a GPS fix and are in range, a partner signal age of a
   few seconds.
5. Add the field to a ride page: Profiles → edit page → add field → **PartnerRide → Partner Gap**.
   Full-width and half-width cells are both supported.
6. Optional: enable the **Gap alert** and set a threshold (default 100 m). It beeps and flashes
   once each time the smoothed gap first exceeds the threshold, and re-arms after the gap closes.

The link runs whenever the extension is enabled — no ride recording needed. Scanning is
duty-cycled to save battery, landing updates every ~2-3 s.

## About the two Android permission pop-ups

On first launch PartnerRide asks for **location** and **nearby devices (Bluetooth)** using the
plain Android system dialogs. They look out of place on the Karoo — no app can style those
dialogs — and you won't have seen them from most other extensions. That's expected:

- Most extensions get their data *through* Karoo OS (the karoo-ext SDK), where Hammerhead's own
  software holds the permissions. PartnerRide can't use that path: the SDK's location event
  carries no GPS timestamp, speed, or heading, and the partner link depends on satellite-time
  stamps to compare two moving riders accurately (see TECHNICAL.md for the full reasoning).
  So it reads GPS directly, which needs the location permission.
- The Bluetooth broadcast/scan between the two Karoos is raw BLE advertising — something the
  karoo-ext SDK has no API for — so the nearby-devices permission is genuinely required.

Both dialogs appear **once per install**; after granting, they never return. If you sideload
via adb and want to skip them entirely, pre-grant the permissions:

```
adb shell pm grant net.bbgen.karoo.partnerride android.permission.ACCESS_FINE_LOCATION
adb shell pm grant net.bbgen.karoo.partnerride android.permission.BLUETOOTH_SCAN
adb shell pm grant net.bbgen.karoo.partnerride android.permission.BLUETOOTH_ADVERTISE
```

## Data field states

| Display | Meaning |
|---|---|
| `42 m ▲` on green/yellow/red | Live gap; ≤15 m green, ≤50 m yellow, >50 m red |
| `~180 m · 8 s` on red | No packet for >5 s: last known value + its age |
| `NO SIGNAL` on red | Partner signal lost for >60 s (usually: out of BLE range) |
| `NO SIGNAL` on gray | Running and broadcasting, but no partner heard yet |
| `NO GPS` on gray | No own GPS fix (yet, or for >10 s) — nothing is being broadcast |
| `NO BT` on gray | Bluetooth is off |
| `NO CODE` on gray | No couple code entered yet (or fewer than 6 digits) — nothing is broadcast |
| `NO PERM` on gray | Location/Bluetooth permissions missing — open the app to grant them |
| `OFF` on gray | PartnerRide is disabled (or its service is not running) |

Gray states mean "not working, but nobody is being dropped"; red is reserved for a wide gap and
for losing your partner mid-ride.

## Known limitations

- **BLE range is roughly 50–150 m** in the open (less with bodies/terrain in the way). Beyond
  that the field shows the last known gap for 60 s, then `NO SIGNAL`. The link recovers by
  itself when back in range — there is no reconnect logic to get stuck.
- **GPS accuracy floor:** each device is ±3–5 m, so the displayed gap has an error floor of
  roughly 5–10 m. Treat small gaps as "together", not as centimeter truth.
- **Straight-line distance:** on switchbacks/hairpins the road distance between riders can be much
  longer than the displayed straight-line gap.
- The BLE broadcast is unencrypted plaintext — see [Privacy](#privacy) above.
- Exactly one partner is supported.

## Project layout

- `core/` — pure logic, fully unit-tested: packet codec, couple code, timestamp
  reconstruction/replay guard, GPS fix ring buffer, gap engine (dead reckoning with the
  timestamp-matching fallback, sign, smoothing), zone hysteresis, data field display states.
- `service/PartnerLinkService.kt` — foreground service: BLE advertise + scan (with the 20-minute
  scan restart that dodges Android's 30-minute scan demotion), GPS via `LocationManager`
  (satellite time for the packet timestamps), wakelock, gap alert.
- `extension/` — the karoo-ext extension service and the Glance-rendered data field.
- `screens/MainScreen.kt` — settings UI (enable, couple code, alert, status).
