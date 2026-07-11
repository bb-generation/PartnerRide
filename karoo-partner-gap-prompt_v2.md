# Prompt for Claude Code

Use the **hammerskill** skill to build a complete Hammerhead Karoo extension called **PartnerRide**. Kotlin, karoo-ext SDK, target Karoo 3. One identical APK runs on both riders' devices. Deliver a buildable Android Studio / Gradle project plus a README with build and sideload instructions (sideload via Hammerhead companion app).

## Purpose

Two riders each run this extension. Each Karoo broadcasts its own GPS position over BLE and receives the partner's. The extension provides a custom ride data field showing the live straight-line distance to the partner, signed by who is ahead.

## Architecture

**Transport: connectionless BLE advertising. No pairing, no GATT connection.**
- Both devices simultaneously advertise and scan. Identical roles, identical code.
- Advertising: legacy BLE advertisement, manufacturer-specific data, `ADVERTISE_MODE_LOW_LATENCY` (~100 ms interval), `ADVERTISE_TX_POWER_HIGH`.
- Scanning: `SCAN_MODE_LOW_LATENCY` by default, filtered on the manufacturer ID. Provide a scan-mode setting: "Performance" (`SCAN_MODE_LOW_LATENCY`, default) and "Battery Saver" (`SCAN_MODE_BALANCED`; note in the UI that this can add a few seconds of update latency).
- Android demotes BLE scans running longer than 30 minutes to opportunistic mode. The foreground service must stop and restart the scan every ~20 minutes to reset this timer. Never restart rapidly: Android blocks apps that start scans more than 5 times per 30 seconds, so the restart cadence must stay coarse.
- Manufacturer ID: use the Bluetooth SIG test ID `0xFFFF` as a single named constant. Prefer a hardware `ScanFilter` on it; if filtering proves unreliable on the device, fall back to scanning with a permissive filter and matching the manufacturer ID programmatically in `onScanResult`.
- Run advertising + scanning in a foreground service with a partial wakelock so Karoo OS never throttles it. Active whenever the extension is enabled in its settings, independent of whether a ride is recording.

**Packet payload (plaintext, no encryption in v1; fits in the ~24 usable bytes of legacy advertising):**
1. Version byte (format version, start at 1; receiver rejects unknown versions)
2. Magic constant, 2 bytes (hardcoded app identifier)
3. Couple code tag, 4 bytes: first 4 bytes of SHA-256 of the normalized couple code (lowercase, trimmed, words joined with "-")
4. GPS fix timestamp, 2 bytes: GPS time in milliseconds mod 65536. MUST come from `Location.getTime()` (satellite-derived UTC), never from `System.currentTimeMillis()` — device clocks drift relative to each other and would break the timestamp matching.
5. Latitude, int32, degrees × 10^7
6. Longitude, int32, degrees × 10^7
7. Speed, 1 byte: m/s × 4 (0–63.75 m/s range; value 0xFF = invalid/unknown), from `Location.getSpeed()`
8. Heading, 1 byte: degrees / 2 (value 0xFF = invalid/unknown), from `Location.getBearing()`

Total 19 bytes.

**Packet validation on receive:**
- Discard silently unless version, magic constant, and couple code tag all match.
- Stale/replay protection: track the last seen fix timestamp; reject packets whose timestamp is not newer (handle the mod-65536 wraparound).
- Known accepted tradeoff: the plaintext couple code tag is a static identifier observable by nearby BLE sniffers. Encryption is a possible later addition; design the packet parsing so an encrypted format can be added as version 2.

**Coupling via word code:**
- Settings screen where both riders enter the same three-word code, e.g. "maple-rocket-sunset".
- "Generate" button: pick 3 random words from the bundled EFF short wordlist (1,296 words); bundle the list as an asset.
- Manual entry field for the partner device. Normalize before hashing; the hash yields the 4-byte couple code tag used in the packet.

## Gap computation (dead reckoning with timestamp alignment — this is the core correctness requirement)

- Keep a ring buffer of the device's own last ~5 seconds of GPS fixes with their GPS timestamps (`Location.getTime()`), including own speed and bearing per fix.
- On receiving a partner packet, reconstruct its full GPS timestamp from the 2-byte mod value relative to the local latest `Location.getTime()`.
- Primary method (dead reckoning): choose a common evaluation time = the newer of (own latest fix timestamp, partner fix timestamp). Extrapolate the older position forward to that time along its own speed and heading (flat-earth local tangent projection is sufficient for these durations). Compute the haversine gap between the two positions at the common time. Never compare fixes from different times without extrapolation.
- Cap extrapolation at 3 s per position; if a fix is older than that, treat it under the staleness rules instead of extrapolating further.
- Fallback (timestamp matching): if the partner's speed or heading byte is the invalid sentinel (0xFF), skip extrapolation of the partner position and instead match it against the own fix from the ring buffer closest to the partner's fix timestamp.
- Sign: project the vector to the partner onto the device's own heading, derived locally from its own recent GPS fixes (or Karoo's bearing if exposed). Partner ahead → positive, shown with ▲. Partner behind → ▼. If own heading is unreliable (standing still), keep the last stable sign.
- Smoothing: rolling average over the last 3 computed values. Display rounding: 1 m steps up to 50 m, 5 m steps above.
- Recompute on every received packet, not on a timer.

## Data field (karoo-ext custom data type)

- Implement as a graphical data type (`graphical="true"` in extension_info.xml) rendered via RemoteViews (Jetpack Glance or classic XML layouts) — a plain numeric data type cannot change its background color. Reference pattern: existing Karoo extensions such as eiRadar render fresh RemoteViews per update cycle.
- Content: numeric with arrow, e.g. "42 m ▲".
- Background color by distance: ≤ 15 m green, ≤ 50 m yellow, > 50 m red. Signal lost → red. Apply ~2 m hysteresis at both thresholds (e.g. green→yellow at 16 m, yellow→green at 14 m) so GPS jitter near a boundary doesn't strobe the color.
- Staleness handling: no valid packet for 5 s → switch to last known value with age, e.g. "~180 m · 8 s", still red. After 30 s more → show "—".
- Support full-width and half-width layouts.

## Optional gap alert

- Off by default. When enabled: beep + visual flash when the smoothed gap first exceeds a configurable threshold (default 100 m). Re-arm only after the gap drops below the threshold again, so it fires once per drop-off, not continuously.
- Audio MUST use the native karoo-ext beep API (`PlayBeepPattern` dispatched via `KarooSystemService`), not Android `ToneGenerator`/`MediaPlayer` — standard Android audio may not route to the Karoo's buzzer.

## Settings screen

- Enable/disable toggle (controls the foreground service)
- Couple code: display, manual entry, Generate button
- Alert toggle + threshold input
- Scan mode: Performance / Battery Saver
- Status line: broadcasting yes/no, partner signal age, own GPS fix age

## Robustness requirements

- Handle Bluetooth off / permission missing with a clear status message in settings and "—" in the data field.
- Handle missing own GPS fix the same way.
- Survive the partner going out of range and returning without any reconnect logic (stateless by design; verify no state blocks recovery).
- All BLE work off the main thread.
- The periodic ~20-minute scan restart must be seamless: no data-field dropout, and it must not count toward the 5-scans-per-30-seconds limit.
- Log packet parse failures at debug level only; never crash on malformed packets.

## Explicit non-goals (do not build)

- No payload encryption (deliberate v1 decision; keep the door open via the version byte)
- No route-based distance
- No support for more than one partner
- No internet/MQTT fallback

## Deliverables

1. Full project source, buildable with `./gradlew assembleRelease`
2. README: build steps, sideload steps for Karoo 3, first-time setup for both devices (generate code on device A, type it on device B), known limitations (BLE range ~50–150 m, GPS accuracy floor ±3–5 m per device, straight-line distance on switchbacks)
3. Unit tests for: packet encode/decode roundtrip (including speed/heading and the 0xFF sentinels), packet validation with matching and non-matching couple code tags, timestamp wraparound handling, dead-reckoning extrapolation (known position + speed + heading + Δt → expected position), the 3 s extrapolation cap, fallback timestamp matching against the ring buffer, sign computation
