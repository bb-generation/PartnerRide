# PartnerGap

A [Hammerhead Karoo](https://www.hammerhead.io/) extension for two riders. Each Karoo broadcasts
its own GPS position over connectionless BLE advertising and receives the partner's; a custom
ride data field shows the live straight-line distance to the partner, signed by who is ahead
(`42 m ▲` = partner 42 m ahead, `▼` = behind), on a green/yellow/red background.

One identical APK runs on both riders' devices — no pairing, no connection, identical roles.

Developer-level details (BLE transport, payload byte layout, time model, gap algorithm) live in
[TECHNICAL.md](TECHNICAL.md).

## How it works

- Both devices simultaneously advertise (legacy BLE, manufacturer data, ~100 ms interval, max TX
  power) and scan. The 19-byte packet carries a format version, an app magic constant, a 4-byte
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
- A rolling average over the last 3 values smooths the display; the background color has ~2 m of
  hysteresis at the 15 m and 50 m thresholds.
- Both riders enter the same three-word couple code (e.g. `maple-rocket-sunset`); only packets
  with a matching code tag are accepted. Optional gap alert: beep + full-screen flash the first
  time the gap exceeds a threshold, re-armed when the gap closes again.

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

The release build is debug-signed so it can be sideloaded directly. Replace the signing config
in `app/build.gradle.kts` with a real keystore before distributing publicly.

## Sideload onto the Karoo 3

Via the Hammerhead companion app (no cables):

1. Install the **Hammerhead Companion** app on your phone and pair it with the Karoo.
2. Copy `app-release.apk` to the phone (AirDrop/Drive/USB/email...).
3. Open the APK on the phone (tap it in your file manager or the "share" sheet) and choose to
   open/share it with the Hammerhead Companion app → it installs onto the paired Karoo.
4. **On the Karoo, open the PartnerGap app once from the launcher** — extensions register with
   Karoo OS only after the first launch.

Alternative via adb: enable Developer Options on the Karoo (Settings → About → tap Build Number
repeatedly), enable USB debugging, then `adb install -r app-release.apk` — and still open the app
once.

Repeat for **both** devices — the same APK (and the same version) goes on both; the packet
format is validated strictly, so mismatched versions simply won't see each other.

## First-time setup (both devices)

1. On the Karoo: open PartnerGap. Grant the Bluetooth and Location permissions when prompted.
2. **Device A:** tap **Generate** — it creates a three-word code like `maple-rocket-sunset`.
3. **Device B:** type exactly that code into the couple-code field (case and spacing don't
   matter; `Maple Rocket Sunset` works too).
4. Enable **PartnerGap enabled** on both devices. The status section should show
   *Broadcasting: yes* and, once both have a GPS fix and are in range, a partner signal age of a
   few seconds.
5. Add the field to a ride page: Profiles → edit page → add field → **PartnerGap → Partner Gap**.
   Full-width and half-width cells are both supported.
6. Optional: enable the **Gap alert** and set a threshold (default 100 m). It beeps and flashes
   once each time the smoothed gap first exceeds the threshold, and re-arms after the gap closes.

The link runs whenever the extension is enabled — no ride recording needed. Battery Saver scan
mode saves power but can add a few seconds of update latency.

## About the two Android permission pop-ups

On first launch PartnerGap asks for **location** and **nearby devices (Bluetooth)** using the
plain Android system dialogs. They look out of place on the Karoo — no app can style those
dialogs — and you won't have seen them from most other extensions. That's expected:

- Most extensions get their data *through* Karoo OS (the karoo-ext SDK), where Hammerhead's own
  software holds the permissions. PartnerGap can't use that path: the SDK's location event
  carries no GPS timestamp, speed, or heading, and the partner link depends on satellite-time
  stamps to compare two moving riders accurately (see TECHNICAL.md for the full reasoning).
  So it reads GPS directly, which needs the location permission.
- The Bluetooth broadcast/scan between the two Karoos is raw BLE advertising — something the
  karoo-ext SDK has no API for — so the nearby-devices permission is genuinely required.

Both dialogs appear **once per install**; after granting, they never return. If you sideload
via adb and want to skip them entirely, pre-grant the permissions:

```
adb shell pm grant net.bbgen.karoo.partnergap android.permission.ACCESS_FINE_LOCATION
adb shell pm grant net.bbgen.karoo.partnergap android.permission.BLUETOOTH_SCAN
adb shell pm grant net.bbgen.karoo.partnergap android.permission.BLUETOOTH_ADVERTISE
```

## Data field states

| Display | Meaning |
|---|---|
| `42 m ▲` on green/yellow/red | Live gap; ≤15 m green, ≤50 m yellow, >50 m red |
| `~180 m · 8 s` on red | No packet for >5 s: last known value + its age |
| `—` on red | No signal for >35 s, Bluetooth off, permissions missing, or no own GPS fix |

## Known limitations

- **BLE range is roughly 50–150 m** in the open (less with bodies/terrain in the way). Beyond
  that the field shows the last known gap, then `—`. The link recovers by itself when back in
  range — there is no reconnect logic to get stuck.
- **GPS accuracy floor:** each device is ±3–5 m, so the displayed gap has an error floor of
  roughly 5–10 m. Treat small gaps as "together", not as centimeter truth.
- **Straight-line distance:** on switchbacks/hairpins the road distance between riders can be much
  longer than the displayed straight-line gap.
- The couple-code tag is broadcast in plaintext — a nearby BLE sniffer can observe the (static)
  identifier and positions. The packet format is versioned so an encrypted v2 can be added later.
- Exactly one partner is supported.

## Project layout

- `core/` — pure logic, fully unit-tested: packet codec, couple code, timestamp
  reconstruction/replay guard, GPS fix ring buffer, gap engine (dead reckoning with the
  timestamp-matching fallback, sign, smoothing), zone hysteresis.
- `service/PartnerLinkService.kt` — foreground service: BLE advertise + scan (with the 20-minute
  scan restart that dodges Android's 30-minute scan demotion), GPS via `LocationManager`
  (satellite time for the packet timestamps), wakelock, gap alert.
- `extension/` — the karoo-ext extension service and the Glance-rendered data field.
- `screens/MainScreen.kt` — settings UI (enable, couple code, alert, scan mode, status).
