# PartnerRide — Technical Documentation

Internals for developers: the BLE protocol, the wire format, and the gap-computation pipeline.
For build/install/usage see [README.md](README.md); for repo conventions see [CLAUDE.md](CLAUDE.md).

## 1. System overview

Two Karoo devices run the identical APK. There is no master/slave, no pairing, no GATT
connection, and no acknowledgement — each device is simultaneously:

- a **broadcaster**: BLE legacy advertisement containing its own most recent GPS fix, refreshed
  in place on every fix (~1 Hz), transmitted every ~250 ms;
- a **receiver**: BLE scanner (duty-cycled to save battery, landing updates roughly every
  2–3 s) that picks up the partner's advertisements and recomputes the gap on every
  accepted packet (never on a timer).

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

## 2. BLE transport

| Aspect | Value | Why |
|---|---|---|
| Advertisement type | Legacy, non-connectable, non-scannable (`ADV_NONCONN_IND`) | Fits every scanner; ~24 usable payload bytes is enough |
| Advertising API | `AdvertisingSet` (API 26+), `setLegacyMode(true)` | Allows `setAdvertisingData()` to swap the payload **in place** per GPS fix — no stop/start churn |
| Advertising interval | `INTERVAL_MEDIUM` (~250 ms) | Own GPS fixes change ~1x/s, so `INTERVAL_LOW`'s 10 TX/s was pure redundancy; ~4 TX/s still gives a duty-cycled scanner several chances per fix at ~1/4 the advertising-side radio time |
| TX power | `TX_POWER_HIGH` | Maximize range (~50–150 m open air) |
| Carrier | Manufacturer-specific data, manufacturer ID `0xFFFF` | Bluetooth SIG *test* ID; single constant `PacketCodec.MANUFACTURER_ID` |
| Scan mode | `SCAN_MODE_BALANCED`, fixed (not user-selectable) | Duty-cycles the receiver (~25% listening) to save radio power while still landing updates roughly every 2–3 s given the advertising interval above. Not exposed as a setting — the tradeoff is made once for everyone rather than asking riders to choose |
| Scan result batching | `setReportDelay(SCAN_REPORT_DELAY_MS)` (2 s), when `BluetoothAdapter.isOffloadedScanBatchingSupported` | The controller buffers matched advertisements in its own memory and wakes the AP once per delay window (`onBatchScanResults`) instead of once per advertisement (`onScanResult`) — cuts CPU/Binder wakeups without dropping any packets or affecting radio listening time. Falls back to immediate per-result delivery on hardware without batching support |
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
5. Replay guard: fix timestamp strictly newer than the last accepted one (§5)

Steps 1–4 also protect against foreign devices using the same test manufacturer ID — such
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
diff = (timeMod - reference mod 65536)  normalized into (-32768, 32768]
fullTime = reference + diff
```

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
  current sign applied to the result. The constructor default is `1` — effectively disabled
  (was `3`; the parameter and averaging logic are kept, not removed, so raising it back is a
  one-line change). Under duty-cycled scanning (§2) accepted packets already land several
  seconds apart, and averaging N of them would multiply the displayed lag by N, working
  against the ~2–3 s freshness target.
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
(`SystemClock.elapsedRealtime`), evaluated on every state change and once per second. When
several things are wrong, the **first matching row from the top wins**, so the field always
names the first problem to fix:

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

## 8. App architecture

Single process, three layers, bridged by one `StateFlow`:

```
PartnerLinkService (foreground, wakelock)          PartnerRideExtension (bound by Karoo OS)
  GPS (LocationManager, GPS provider)                PartnerRideDataType (Glance → RemoteViews)
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
- The in-ride field is RemoteViews-only (Karoo renders it in its own process) — hence Glance,
  and hence the field state must be re-rendered rather than animated.
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
  `pm grant` (see README).
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
| Extrapolation cap | 3 s | `GapEngine.maxExtrapolationMs` |
| Replay-guard reset | 30 s | `GapEngine.DEFAULT_REPLAY_RESET_MS` (must be < 32.768 s) |
| Smoothing window | 1 value (disabled; was 3) | `GapEngine.smoothingWindow` |
| Scan report delay (batching) | 2 s, if supported | `PartnerLinkService.SCAN_REPORT_DELAY_MS` |
| Heading reliability distance | 2 m | `GapEngine.headingMinDistanceM` |
| Zone thresholds / hysteresis | 15 m, 50 m / ±1 m | `ZoneTracker` |
| Fresh / signal-lost limit | 5 s / 60 s | `FieldState.FRESH_MS` / `FieldState.SIGNAL_LOST_MS` |
| Own-fix stale limit | 10 s | `FieldState.OWN_FIX_STALE_MS` |
| GPS update interval | 1 s | `PartnerLinkService.LOCATION_INTERVAL_MS` |
| Scan restart period | 20 min | `PartnerLinkService.SCAN_RESTART_INTERVAL_MS` |
| Scan retry backoff | 5 s, doubling to 60 s | `PartnerLinkService.SCAN_RETRY_BASE_MS` / `_MAX_MS` |
