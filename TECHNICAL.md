# PartnerRide — Technical Documentation

Internals for developers: the BLE protocol, the wire format, the gap-computation pipeline, and
how to build and install the app (§11–§12). For what the extension does and how to use it on a
ride see [README.md](README.md); for repo conventions see [CLAUDE.md](CLAUDE.md).

## 1. System overview

Two Karoo devices run the identical APK. There is no master/slave, no pairing, no GATT
connection, and no acknowledgement — each device is simultaneously:

- a **broadcaster**: BLE legacy advertisement containing its own most recent GPS fix, refreshed
  in place on every fix (~1 Hz), transmitted every ~100 ms;
- a **receiver**: BLE scanner (continuous, landing updates roughly once a second) that picks up
  the partner's advertisements and recomputes the gap on every accepted packet (never on a
  timer).

Because the link is stateless, "reconnection" does not exist as a concept: when the partner
comes back into radio range, their packets simply start being accepted again. The only receiver
state (the replay guard, §5) auto-resets after 30 s of silence so it can never block recovery.

```
┌─ Karoo A ────────────────────┐          ┌─ Karoo B ────────────────────┐
│ GPS ──► FixBuffer (own 5 s)  │          │ GPS ──► FixBuffer (own 5 s)  │
│   │                          │   BLE    │   │                          │
│   └──► 19-byte payload ──► ADV ~~~~~~~► SCAN ──► validate ──► GapEngine│
│ SCAN ◄~~~~~~ ADV ◄───────────┼──────────┤          ──► data field      │
└──────────────────────────────┘          └──────────────────────────────┘
```

The same pipeline as an animated walkthrough — one packet A → B, from the GPS fix to the value in
the data field (the README carries a simplified version of the same picture):

![Both Karoos take their own GPS fix stamped with satellite time, broadcast it directly to each
other in a 19-byte Bluetooth LE advertisement, validate the received packet, dead-reckon the older
fix forward to a common evaluation time, and show the resulting signed distance in the ride data
field](art/partnerride-workflow.svg)

## 2. BLE transport

| Aspect | Value | Why |
|---|---|---|
| Advertisement type | Legacy, non-connectable, non-scannable (`ADV_NONCONN_IND`) | Fits every scanner; ~24 usable payload bytes is enough |
| Advertising API | `AdvertisingSet` (API 26+), `setLegacyMode(true)` | Allows `setAdvertisingData()` to swap the payload **in place** per GPS fix — no stop/start churn |
| Advertising interval | `INTERVAL_LOW` (~100 ms) | ~10 TX per GPS fix. The redundancy is the point: it is what gets a fix through the packet loss of a moving bike-to-bike link. `INTERVAL_MEDIUM` (~250 ms) was tried for battery and reverted — see the scan mode row |
| TX power | `TX_POWER_HIGH` | Maximize range (~50–150 m open air) |
| Carrier | Manufacturer-specific data, manufacturer ID `0xFFFF` | Bluetooth SIG *test* ID; single constant `PacketCodec.MANUFACTURER_ID` |
| Scan mode | `SCAN_MODE_LOW_LATENCY`, fixed (not user-selectable) | Scans continuously, so a partner packet lands about once per second. Duty-cycled `SCAN_MODE_BALANCED` was tried to save battery (1.5.0–1.6.2) and failed in the field: the gap would go fresh, then the receiver slept through 15–30 s of advertisements and the field sat counting the age up, repeatedly. Battery is the cheaper thing to spend. Not exposed as a setting either — the tradeoff is made once for everyone rather than asking riders to judge it |
| Scan result batching | None — `setReportDelay(0)` | Controller-side batching saves AP wakeups but holds every packet for a whole delay window before delivering it. It shipped alongside the duty-cycled scan mode and was reverted with it. `onBatchScanResults` is still implemented as a safety net for controllers that batch regardless, and sorts each batch chronologically (§4) |
| Scan filter | Hardware `ScanFilter` on manufacturer ID `0xFFFF`, empty data mask | Cheap pre-filter; full validation still happens in software (§4). `USE_HARDWARE_FILTER = false` switches to a permissive scan if a device's filtering proves unreliable |
| Scan restart | Stop + immediate start every **20 min** | Android demotes scans older than 30 min to opportunistic mode; the restart resets that timer |
| Scan-start rate limit | Guard queue keeps starts ≤ 4 per 30 s | Android silently blocks apps starting > 5 scans per 30 s; normal operation is 1 start per 20 min |

Everything runs in a foreground service (`PartnerLinkService`) holding a partial wakelock,
active whenever the extension is enabled — independent of ride recording. Karoo OS owns the
radios, so the service dispatches karoo-ext's `RequestBluetooth` on start and
`ReleaseBluetooth` on stop. All scan/advertise callbacks are marshalled onto a dedicated
`HandlerThread`; no BLE work happens on the main thread.

## 3. Payload specification (format version 1)

19 bytes of manufacturer-specific data, all multi-byte fields **big-endian**:

| Offset | Size | Field | Encoding | Notes |
|---:|---:|---|---|---|
| 0 | 1 | Version | `0x01` | Receiver silently drops unknown versions; an encrypted format would be version 2 |
| 1 | 2 | Magic | `0x50 0x47` (`"PG"`) | App identifier; silently dropped on mismatch |
| 3 | 4 | Couple code tag | First 4 bytes of `SHA-256(normalized couple code)` | Normalization: lowercase, whitespace/`-`/`_` stripped (see §3.1) |
| 7 | 2 | GPS fix time | `Location.getTime() mod 65536`, unsigned | **Satellite time**, never the device clock (§5). Wraps every 65.536 s |
| 9 | 4 | Latitude | `int32`, degrees × 10⁷ | ~1.1 cm resolution |
| 13 | 4 | Longitude | `int32`, degrees × 10⁷ | |
| 17 | 1 | Speed | `m/s × 4`, unsigned | Range 0–63.5 m/s in 0.25 m/s steps; `0xFF` = invalid/unknown (from `Location.hasSpeed()`) |
| 18 | 1 | Heading | `degrees ÷ 2`, unsigned | Values 0–179 → 0–358°; rounding wraps 360°→0°; `0xFF` = invalid/unknown (from `Location.hasBearing()`) |

On-air size: 19 payload bytes + 2 (manufacturer ID) + 2 (AD length/type) = 23 of the 31-byte
legacy advertising PDU.

The payload is **plaintext by design** (v1 tradeoff): a nearby BLE sniffer can observe the
static couple tag and both positions. The version byte is the upgrade path for an encrypted v2;
receivers already reject any version ≠ 1, so the formats can coexist on air.

### 3.1 Couple code → tag

```
normalize(" 123 456 ") = "123456"   // lowercase, [\s-_]+ stripped
tag = SHA-256(UTF-8(normalized))[0..3]
```

Codes are 6 random digits (10⁶ ≈ 20 bits of entropy — enough to avoid accidental collisions
between nearby couples); the tag is an identifier, not a security boundary, and with only 10⁶
possible codes the plaintext tag is trivially brute-forceable back to the code. Both devices
must produce the same 4 bytes for packets to be accepted. The SHA-256 hashing is kept (rather
than embedding the digits directly) so the tag derivation is independent of the code format.

## 4. Receive-side validation pipeline

In order, each failure is a **silent drop** (debug-level log only, never a crash):

1. Manufacturer data present for ID `0xFFFF` and length == 19
2. Version == 1
3. Magic == `"PG"`
4. Couple tag == own tag (byte compare)
5. Heading byte is 0–179 or the `0xFF` sentinel — a conforming sender emits nothing else, and
   aliasing an out-of-range value through `% 360` would fabricate a plausible heading
6. Replay guard: fix timestamp strictly newer than the last accepted one (§5)

Steps 1–5 also protect against foreign devices using the same test manufacturer ID — such
packets are expected background noise.

## 5. Time model

All packet timestamps are `Location.getTime()` — **GPS/satellite UTC**. The device clocks of the
two Karoos are never compared with each other; `System.currentTimeMillis()` appears nowhere in
the protocol because relative clock drift between devices would corrupt the alignment in §6.
(This is also why GPS is read from `LocationManager` directly: karoo-ext's `OnLocationChanged`
event does not expose the GPS timestamp.)

**Reconstruction.** The packet carries only `t mod 65536` (16 bits of milliseconds). The
receiver reconstructs the full timestamp as the value congruent to the received mod that lies
closest to its own newest fix time — unambiguous as long as the two fixes are within ±32.768 s
of each other, far beyond any real BLE latency:

```
diff = (timeMod - reference mod 65536)  normalized into [-32768, 32767]
fullTime = reference + diff
```

At exactly half a modulus the two candidates are equidistant; the tie is resolved as *past*, so
that reconstruction and the replay guard (which treats a forward distance of exactly 32768 as
not-newer) share one convention.

**Replay guard.** The last accepted `timeMod` is tracked; a packet is accepted only if it is
0 < (new − last) mod 65536 < 32768, i.e. strictly newer within the forward half-window. This
rejects duplicates (the same advertisement is received many times at a ~250 ms interval) and
replays. After 30 s without an accepted packet the guard resets, because mod-65536 ordering is
meaningless across longer gaps — this is what makes out-of-range recovery state-free.

The reset interval must stay **below** the guard's own 32.768 s half-window, and `GapEngine`
enforces that with a `require`. A partner out of range for longer than the half-window returns
with a `timeMod` the guard reads as *older* (40 s newer and 25.5 s older are the same 16-bit
value), so it is rejected — and since the reset clock only advances on an *accepted* packet, a
reset slower than the half-window leaves a dead band in which the partner is back, transmitting,
and ignored. Resetting first closes that band at every dropout length.

## 6. Gap computation

Implemented entirely in `core/GapEngine` (pure Kotlin, JVM-tested). Inputs: own GPS fixes
(1 Hz, kept in a ~5 s ring buffer with speed/bearing per fix) and validated partner packets.
Recomputed on every accepted packet.

### 6.1 Position alignment — dead reckoning (primary)

Fixes from different times are never compared directly. On each packet:

1. Reconstruct the partner's full fix time (§5).
2. Evaluation time `T_eval = max(ownLatestFixTime, partnerFixTime)`.
3. Extrapolate the **older** position forward to `T_eval` along its transmitted speed and
   heading, using a flat-earth local tangent projection (sufficient for ≤ 3 s horizons):

   ```
   d      = speed × Δt
   ΔNorth = d × cos(heading);   ΔEast = d × sin(heading)
   lat'   = lat + ΔNorth / R                     (in radians, R = 6 371 000 m)
   lon'   = lon + ΔEast / (R × cos(lat))
   ```

   If the partner's fix is newer than our own, it is *our* position that gets extrapolated
   (using our own fix's GPS speed/bearing).
4. Gap = haversine distance between the two aligned positions.

### 6.2 Extrapolation cap and fallback — timestamp matching

Dead reckoning is skipped, and the receiver falls back to plain timestamp matching, when:

- the partner's speed **or** heading byte is the `0xFF` sentinel, or
- either position would need more than **3 s** of extrapolation (`maxExtrapolationMs`).

Fallback: the partner's raw fix is compared against the own fix from the ring buffer that is
*closest to the partner's fix time* — never against the current own position. Fixes older than
the buffer window are the staleness rules' problem (§7), not the geometry's.

### 6.3 Sign (ahead / behind)

The vector from own position to partner position is projected onto the rider's **own** heading
(no heading data from the packet is used for this): partner ahead ⇔ |bearing-to-partner −
own-heading| < 90°. Own heading comes from the latest fix's GPS bearing, falling back to the
track direction of the ring buffer (newest fix vs the most recent fix ≥ 2 m away). If no
reliable heading exists (standing still), the last stable sign is kept.

- Positive gap / `▲` = partner ahead; negative / `▼` = partner behind.

### 6.4 Smoothing, rounding, colors

- **Smoothing**: rolling average of the last `smoothingWindow` gap **magnitudes**, with the
  current sign applied to the result. The constructor default is `3`. Continuous scanning (§2)
  lands packets about once a second, so a 3-value window takes the GPS jitter off the number
  while the displayed lag stays under a rider's notice. (It was briefly `1` — averaging off —
  during the duty-cycled-scan experiment, when accepted packets were several seconds apart and
  averaging three of them would have tripled an already-visible lag.)
  Averaging *signed* values would be wrong at any window > 1: riding side by side the sign
  oscillates, so `+30 / −30 / +30` would report 10 m and the zone color and drop-off alert
  (both of which consume `abs(smoothedGapMeters)`) would under-read the real separation.
  The buffer is cleared whenever the replay guard resets, so values from before a dropout
  never drag the first reading after it. `smoothingWindow` must be ≥ 1 (`require`); 0 would
  make the average `NaN` and `roundGapForDisplay` throw.
- **Display rounding**: 1 m steps up to 50 m, 5 m steps above.
- **Zone colors** with ±1 m hysteresis on both thresholds (so GPS jitter can't strobe the
  background): green ≤ 15 m, yellow ≤ 50 m, red above — e.g. green→yellow at 16 m but
  yellow→green at 14 m.

## 7. Display state machine (data field)

Implemented in `core/FieldState` (pure Kotlin, JVM-tested); the view layer only maps
`FieldBackground` values to colors. Ages are measured on the monotonic clock
(`SystemClock.elapsedRealtime`), evaluated on every state change and once per second. The
*rendered* rate is lower: karoo-ext drops any `updateView` issued within ~900 ms of the
previous one, so the view flow is throttled to 1 Hz (conflating, so the value that survives
each window is the newest) and deduplicated. A state change can therefore be deferred to the
next tick — up to ~1 s — but is never dropped. When several things are wrong, the **first
matching row from the top wins**, so the field always names the first problem to fix:

| Condition | Display | Background |
|---|---|---|
| Runtime permissions missing | `NO PERM` | gray |
| Link service not running (extension disabled) | `OFF` | gray |
| Bluetooth off | `NO BT` | gray |
| Couple code empty or < 6 digits | `NO CODE` | gray |
| No own GPS fix yet, or own fix > 10 s old (not broadcasting) | `NO GPS` | gray |
| No partner packet since the service started | `NO SIGNAL` | gray |
| Packet ≤ 5 s old | `42 m ▲` | zone color (§6.4) |
| Packet 5–60 s old | `~180 m · 8 s` (last value + age) | red |
| Packet > 60 s old (contact lost) | `NO SIGNAL` | red |

Gray means "the link is not working, but nobody is being dropped"; red is reserved for the
wide-gap zone and for losing a previously established partner signal mid-ride. To keep that
distinction meaningful, each service start resets the session in `GapRepository` (last
packet/fix ages, smoothed gap), so a new session begins at the gray `NO SIGNAL`, never at a
stale red one carried over from an earlier run.

### 7.1 Sizing the text (why the field is a plain autosizing TextView)

A single-line text that does not fit its slot is silently ellipsized: `150 m ▼` becomes `150 …`,
dropping the one number the field exists to show. That was the field's behavior up to 1.6.3, and
overflow was the normal case rather than an edge case — every string in the table above is wider
than the two or three digits a numeric field holds, so a half-width slot truncated every state,
and a 3-row page (tall rows, so a large font) truncated even the full-width field's `5 m ▲`.

The view is therefore `res/layout/partner_gap_field.xml`, a single `TextView` with
`autoSizeTextType="uniform"`, inflated as RemoteViews in Karoo's process. `PartnerRideDataType`
sets only the text and the two colors; the size is chosen by the view, which measures the string
against the width and height it was actually given and takes the largest size that fits
(`maxLines="1"` makes any size that would wrap count as not fitting).

Why the view and not a size computed from `ViewConfig`:

- `ViewConfig.textSize` is what Karoo would use for a *number* in that slot. It does account for
  the slot's width to a degree (a Karoo 3 gives 55 sp full width, 41 sp half at the same height),
  but it knows nothing about the string, and every string here is wider than the two or three
  digits it is sized for.
- `ViewConfig.viewSize` does report the slot in pixels — `478x126` full width, `238x126` half on
  a Karoo 3 — so the first attempt at this fix (`core/TextFit`, computing a fitted size from it)
  could have worked. It was never actually exercised: the screenshots showing it change nothing
  came from a build that predated it. That is why the settings screen now shows the running
  version, and why demo mode reports the config (§7.3).
- Even given a correct width, the view still does it better: nothing has to cross the process
  boundary, and the slot's height is honoured as well as its width.

Bounds live in the layout: `autoSizeMinTextSize="10sp"` (below that the field is unreadable
anyway, so `ellipsize="end"` takes over as the backstop) and `autoSizeMaxTextSize="200sp"`
(far larger than any slot on a 480x800 Karoo, so in practice the slot's own height and width are
the cap). Note that `TextView.setTextSize` is a **no-op** while autosizing is on — the size
cannot be set over RemoteViews, which is why the bounds are XML and not parameters.

This is also why `FieldDisplay` carries no font scale. Up to 1.6.3 it had one (0.7 for word
labels, 0.62 for the last-known-value form) as a hand-tuned way to make longer strings fit; the
view now does that properly, for every string, against the real slot.

### 7.2 The background shape

Karoo draws its field boundary **over** the graphic, and its own fields clip their background to
that rounded rectangle. A flat background color therefore fills the four corners the boundary
leaves open: the field reads as a square block of color with a rounded outline drawn inside it,
most visibly on a map page, where every other field lets the map through at the corners.

So the background is a rounded-rect drawable (`res/drawable/field_bg_*.xml`), one per zone color,
selected with `setInt(id, "setBackgroundResource", …)`. One drawable tinted per state would be
nicer, but `setBackgroundTintList` over RemoteViews needs API 31 and Karoo 2 is API 26.

The radius (`field_corner_radius`, 10 dp) is measured off Karoo's own fields — an ~18 px arc at
the Karoo 3's 1.875 density — because karoo-ext reports no such value. It is rounded
unconditionally, including when `ViewConfig.boundariesEnabled` is false; what a page without
boundaries looks like has never been seen here, so the flag is reported in demo mode's config
frame rather than branched on. If a rider with boundaries off ever reports odd corners, that flag
is where to hang the exception.

### 7.3 Demo mode

Most rows in the table above need two devices, a lost signal or a revoked permission to reach.
Demo mode makes the field cycle every row, one frame every 2 s, so all of them can be checked on
one device in the slot where they actually render — which is how the truncation above was found
and how a fix for it gets confirmed.

The cycle is 19 frames, a 38 s loop: the 18 display states, preceded by one gray frame reporting
the `ViewConfig` Karoo handed that slot, as
`<cols>x<rows> <width>x<height> t<textSize> b<boundariesEnabled>` — e.g. `60x12 478x126 t55 b1`.
It is the only way to see those numbers (§7.1, §7.2), and it doubles as build identification: an
APK that predates the frame cannot show it at all.

`core/DemoFieldFrames` holds the frames as synthetic `PartnerRideState` values and feeds them
through the real `FieldState.build` rather than emitting hardcoded strings, so what demo mode
shows is by construction what the field shows. `DemoFieldFramesTest` pins each frame's text,
background and font scale, and fails if a `FieldState` branch loses its frame.

Two limits of the format the frames make explicit:

- The shortest age the `~150 m · 5 s` form can show is **5 s**, not 1 s: below `FRESH_MS` the
  fresh-gap row wins, so `~150 m · 1 s` is unreachable on a real ride.
- The longest is **60 s**, capped implicitly by the contact-lost row firing first.

Activation is a hidden gesture — 7 taps on the title of the settings screen, each within 3 s of
the last (`screens/MainScreen`). There is deliberately no visible control for it: riders never
need it, and a rider stuck in demo mode has a useless data field. Taps only ever switch it *on*;
a section that appears only while `demoMode` is set owns switching it off, so it cannot be
entered without an exit. The flag lives in `PartnerRideSettings`, so the running data field picks
it up through `streamSettings()` without being re-added to the page.

## 8. App architecture

Single process, three layers, bridged by one `StateFlow`:

```
PartnerLinkService (foreground, wakelock)          PartnerRideExtension (bound by Karoo OS)
  GPS (LocationManager, GPS provider)                PartnerRideDataType (RemoteViews)
  BLE advertise + scan (HandlerThread)                 reads GapRepository, renders field
  GapEngine + ZoneTracker                            MainActivity / MainScreen (Compose)
  writes ──► GapRepository.state (StateFlow) ◄── reads   settings UI + status line
```

- `core/` has no Android dependencies; monotonic "now" values are injected as parameters, which
  is what makes the whole protocol/geometry layer unit-testable on the JVM.
- Settings (`PartnerRideSettings`) persist as a JSON blob in a preferences DataStore
  (`ignoreUnknownKeys` for forward/backward APK compatibility). `ServiceController.sync()` is the
  single authority mapping the enable toggle to service start/stop. It is deliberately called
  from **every** path that can want the link up — the extension service (Karoo OS binding us),
  a `BOOT_COMPLETED` receiver, `MainActivity.onResume`, the data field's `startView`, and the
  settings UI — because Karoo OS may bind the extension late (or only once the data field is
  first shown); with redundant triggers no single bind order is load-bearing.
- The in-ride field is RemoteViews-only (Karoo renders it in its own process): a layout from
  `res/layout/`, no custom `View` classes, and the field state re-rendered rather than animated.
  Glance was used for this until the field became an autosizing `TextView`, and is no longer a
  dependency — a composed view cannot autosize its text (§7.1).
- The gap alert dispatches karoo-ext effects (`PlayBeepPattern`, `TurnScreenOn`, `InRideAlert`);
  standard Android audio does not route to the Karoo buzzer. Armed/disarmed logic fires once per
  threshold crossing and re-arms only after the gap drops back below the threshold.
- Both the zone hysteresis and the alert are gated on own-fix freshness
  (`FieldState.OWN_FIX_STALE_MS`), not just the display. With own GPS stale the ring buffer still
  holds the last fix, so the computed "gap" is really the distance *we* have covered since —
  enough to trip the alert while the field correctly reads `NO GPS`. Packets received in that
  state still refresh the partner-signal age but publish no gap value.

## 9. Permission model — why the app prompts where other extensions don't

Karoo extensions normally act as pure karoo-ext clients: events (`OnLocationChanged`, stream
states, ride state) arrive over the SDK's binder IPC from Karoo OS, which holds the underlying
Android permissions itself. A pure client app therefore declares no dangerous permissions and
never shows a runtime permission dialog. PartnerRide deliberately steps outside that model in two
places, and each step has a permission cost:

| Capability | karoo-ext path | Why PartnerRide can't use it | Resulting permission |
|---|---|---|---|
| Own position | `OnLocationChanged` event (no prompt) | Delivers only `lat`/`lng`/`orientation` — no `Location.getTime()` (the satellite time base of §5), no `getSpeed()`/`getBearing()` (dead-reckoning inputs of §6). Stamping fixes at receipt time would add an unknown 0.1–1 s of pipeline latency ≈ 1–10 m of error at riding speed | `ACCESS_FINE_LOCATION` for `LocationManager` (GPS provider) |
| Device-to-device link | None — the SDK's Bluetooth surface is the *managed sensor framework* (`scansDevices`/`connectDevice`), where Karoo owns pairing and connections | The transport is connectionless raw BLE advertising + scanning (§2), which the sensor framework cannot express | `BLUETOOTH_ADVERTISE` + `BLUETOOTH_SCAN` (API 31+) |

Consequences:

- The two runtime prompts (location, nearby devices) are stock AOSP dialogs; they cannot be
  themed and are shown once per install. `RequestBluetooth` (§2) only asks Karoo OS to power the
  radio — it does not substitute for the app-level Android permissions.
- `BLUETOOTH_SCAN` is declared *without* `neverForLocation`: the app holds fine location anyway,
  and asserting the flag risks the OS filtering beacon-like advertisements out of scan results —
  exactly what our manufacturer-data packets look like.
- For prompt-free installs (e.g. test devices), the permissions can be pre-granted over adb with
  `pm grant` (§11.2).
- A degraded mode using karoo-ext location (no prompts, worse accuracy) is architecturally
  possible — the `GapEngine` fallback path (§6.2) would carry it — but is intentionally not
  implemented: it silently violates the accuracy contract the protocol is built around.

## 10. Constants reference

| Constant | Value | Location |
|---|---|---|
| Manufacturer ID | `0xFFFF` | `PacketCodec.MANUFACTURER_ID` |
| Packet size / version | 19 / 1 | `PacketCodec` |
| Timestamp modulus | 65 536 ms | `PacketCodec.TIME_MOD` |
| Own-fix ring buffer window | 5 s | `FixBuffer.DEFAULT_WINDOW_MS` |
| GPS clock-jump limit | 60 s | `FixBuffer.DEFAULT_MAX_JUMP_MS` |
| Extrapolation cap | 3 s | `GapEngine.maxExtrapolationMs` |
| Matching-fallback error cap | 5 s | `GapEngine.maxMatchErrorMs` |
| Replay-guard reset | 30 s | `GapEngine.DEFAULT_REPLAY_RESET_MS` (must be < 32.768 s) |
| Smoothing window | 3 values | `GapEngine.smoothingWindow` |
| Scan report delay (batching) | 0 (off) | `PartnerLinkService.startScanIfAllowed` |
| Heading reliability distance | 2 m | `GapEngine.headingMinDistanceM` |
| Zone thresholds / hysteresis | 15 m, 50 m / ±1 m | `ZoneTracker` |
| Fresh / signal-lost limit | 5 s / 60 s | `FieldState.FRESH_MS` / `FieldState.SIGNAL_LOST_MS` |
| Own-fix stale limit | 10 s | `FieldState.OWN_FIX_STALE_MS` |
| Data field font size range | 10-200 sp, autosized | `res/layout/partner_gap_field.xml` |
| Data field horizontal padding | 4 dp | `res/layout/partner_gap_field.xml` |
| Data field corner radius | 10 dp | `field_corner_radius` (`res/values/dimens.xml`) |
| Demo mode frame length / cycle | 2 s / 19 frames | `DemoFieldFrames.FRAME_MS` + the config frame |
| GPS update interval | 1 s | `PartnerLinkService.LOCATION_INTERVAL_MS` |
| Scan restart period | 20 min | `PartnerLinkService.SCAN_RESTART_INTERVAL_MS` |
| Scan retry backoff | 5 s, doubling to 60 s | `PartnerLinkService.SCAN_RETRY_BASE_MS` / `_MAX_MS` |

## 11. Building, signing and installing

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
   ./gradlew lint              # Android lint
   ```

### 11.1 Release signing

Official releases (the APK attached to each [GitHub Release](../../releases)) are signed with a
real, stable key by CI, so installing a newer release over an older one just updates the app in
place — settings, including the couple code, are preserved. A local `./gradlew assembleRelease`
without that signing key configured falls back to **debug-signing** instead, which is a different
key: a debug-signed build cannot be installed over an official release (or vice versa) without
uninstalling first, which wipes the settings. That is fine for development, but **never
distribute a debug-signed APK as an update to someone already running an official release** —
point them at the GitHub Releases page instead. Under CI the same fallback is a hard failure
(`verifyReleaseSigning`) rather than a silent debug-sign.

### 11.2 Installing over adb

The user-facing route (Hammerhead Companion app, no cables) is in
[README.md](README.md) § Install on the Karoo. On a development device, adb is faster:

1. Enable Developer Options on the Karoo (Settings → About → tap Build Number repeatedly) and
   turn on USB debugging.
2. `adb install -r app-release.apk`
3. **Open the app once on the device** — extensions register with Karoo OS only after the first
   launch.

Both devices must run the same APK version; the packet format is validated strictly (§4), so
mismatched versions never see each other.

For prompt-free installs, the two runtime permissions (§9) can be pre-granted:

```
adb shell pm grant net.bbgen.karoo.partnerride android.permission.ACCESS_FINE_LOCATION
adb shell pm grant net.bbgen.karoo.partnerride android.permission.BLUETOOTH_SCAN
adb shell pm grant net.bbgen.karoo.partnerride android.permission.BLUETOOTH_ADVERTISE
```

### 11.3 The debug APK from CI

`.github/workflows/ci.yml` runs on pushes to `master` and on pull requests — **not** on a push to
a feature branch, so a branch gets no CI at all until a PR is open for it. Each run uploads the
`assembleDebug` output as the `debug-apk` artifact (14-day retention), downloadable from the run's
page under the Actions tab. That is the quickest way to try a branch without a local Android
toolchain.

It is **debug-signed** and shares the release build's `applicationId`, so it cannot be installed
over a real-key build: Android rejects it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the
uninstall needed to clear that takes the couple code and the rest of `PartnerRideSettings` with
it. Use it on a spare device, or accept re-entering the couple code. The debug keystore is
generated on the runner rather than committed, so two runs may not be signature-compatible with
each other either.

To upgrade a rider's Karoo in place, build the release APK locally with the real key (§11.1).

## 12. Project layout

- `core/` — pure logic, fully unit-tested: packet codec, couple code, timestamp
  reconstruction/replay guard, GPS fix ring buffer, gap engine (dead reckoning with the
  timestamp-matching fallback, sign, smoothing), zone hysteresis, data field display states.
- `service/PartnerLinkService.kt` — foreground service: BLE advertise + scan (with the 20-minute
  scan restart that dodges Android's 30-minute scan demotion), GPS via `LocationManager`
  (satellite time for the packet timestamps), wakelock, gap alert.
- `extension/` — the karoo-ext extension service and the data field (RemoteViews from
  `res/layout/partner_gap_field.xml`, on the rounded `res/drawable/field_bg_*.xml`).
- `screens/MainScreen.kt` — settings UI (enable, couple code, alert, status).
