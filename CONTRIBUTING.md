# Contributing

Thanks for looking. PartnerRide is a small, single-maintainer project with an unusual constraint:
the thing it does — a BLE link between two Karoos — **cannot be exercised without two physical
devices**. That shapes most of what follows.

Start with [README.md](README.md) for what the app does, and [TECHNICAL.md](TECHNICAL.md) for
how to build it plus the protocol, the time model and the gap algorithm.
[CLAUDE.md](CLAUDE.md) carries the repo conventions.

## Before you write code

- **Bug fixes and documentation fixes:** open a pull request directly. No issue needed.
- **Anything that changes behaviour** — a new setting, a change to the packet format, a different
  algorithm, new UI — **please open an issue first.** Scope gets agreed there, before you invest
  the work. Some things have already been tried and deliberately reverted (see
  [Invariants](#invariants-a-pr-should-not-break)), and an issue is a cheaper place to find that
  out than a review.

## Reporting a bug

The single most useful thing you can include is **the app version on both devices** — mismatched
versions simply cannot see each other, and that accounts for a large share of "it doesn't work"
reports. Beyond that:

- Karoo model (2 or 3) on each device.
- Exactly which state the data field shows: `NO SIGNAL`, `NO GPS`, `NO BT`, `NO CODE`, `NO PERM`,
  `OFF`, or a live value. README.md § Data field states explains what each one means, and
  TECHNICAL.md §7 gives the precedence order — the field always names the *first* problem to fix,
  so the token tells you where to look.
- Whether both riders entered the same couple code, and whether both granted the location and
  nearby-devices permissions.

Do not paste your couple code into a public issue. It is not a security boundary (see
[SECURITY.md](SECURITY.md)), but there is no reason to publish it either.

## Building and testing

Build setup — JDK 17 and a GitHub Packages token for the `karoo-ext` dependency — is in
TECHNICAL.md §11. The token trips up most first-time builds: `karoo-ext` is public, but GitHub
Packages still requires authentication, so a `local.properties` with `gpr.user`/`gpr.key` is not
optional.

What CI runs on every push and pull request, and what you should run before pushing:

```bash
./gradlew test           # JVM unit tests
./gradlew lint           # Android lint
./gradlew assembleDebug  # compiles; CI deliberately does not build a release APK
```

A single test class needs the concrete `testDebugUnitTest` task — `--tests` is not a valid option
on the aggregate `test` task:

```bash
./gradlew testDebugUnitTest --tests "net.bbgen.karoo.partnerride.core.GapEngineTest"
```

### What is testable, and what isn't

This matters more here than in most projects:

- **`core/`** — packet codec, couple code, timestamp reconstruction and replay guard, GPS fix ring
  buffer, gap engine, geometry, zone hysteresis, field state machine. Pure Kotlin, no Android
  dependencies, covered by JVM unit tests. Monotonic "now" values are passed in as parameters
  precisely so this layer stays testable; keep it that way. **A change here can be fully proven by
  a test, and should come with one.**
- **`service/`, `extension/`, `screens/`** — BLE advertising and scanning, GPS via
  `LocationManager`, the foreground service, the RemoteViews data field, the settings UI.
  None of this is covered by the test suite and none of it is exercised by CI beyond compiling.
  Green CI on a change here means "it builds", nothing more.

### Say what you tested

Because CI cannot speak for the second half of that list, **tell us in the pull request which
layer you touched and what you actually ran**:

- `core/` only, with tests → say so; that is a complete story on its own.
- `service/` or `extension/` → say what you ran on hardware: one Karoo or two, how long, what you
  observed. "Compiles, not tested on device" is a perfectly acceptable answer — it just tells the
  maintainer the change needs verifying on two devices before merge, rather than leaving it to be
  discovered later.

Don't claim hardware verification you didn't do. A change that looks obviously correct and breaks
the link in the field is the worst outcome this project has, because it reaches both riders at
once.

## Invariants a PR should not break

These are load-bearing and easy to break by accident. CLAUDE.md and TECHNICAL.md explain the
reasoning behind each in more depth.

- **Packet timestamps are `Location.getTime()` — satellite UTC, never
  `System.currentTimeMillis()`.** The two devices' clocks drift relative to each other, which
  would corrupt the timestamp alignment in `GapEngine`. This is also why GPS comes from
  `LocationManager` rather than karoo-ext's `OnLocationChanged`, which carries no GPS timestamp.
- **Fixes from different times are never compared directly.** `GapEngine` dead-reckons both
  positions to a common evaluation time (capped at 3 s), or falls back to matching against the own
  fix closest in GPS time, or drops the packet.
- **The packet format is versioned.** The 19-byte layout is fixed for version 1; a changed layout
  is version 2, and both devices must upgrade together. Anything failing validation is silently
  dropped, never surfaced as an error.
- **All BLE state mutation stays on the `partnerride-link` handler thread.** BLE callbacks and
  `onDestroy` post onto it rather than writing directly. Nothing enforces this at compile time.
- **`PartnerRideSettings` gains fields with defaults only** — it is persisted as JSON with
  `ignoreUnknownKeys` so that APK versions stay mutually readable. Change it through
  `updateSettings {}`, never by saving a separately-collected snapshot.
- **The redundant `ServiceController.sync()` call sites are deliberate.** Karoo OS may bind the
  extension late, or only once the data field is first shown, so every path that can want the link
  up calls `sync()`. Don't remove one because it looks duplicated.
- **Scan mode stays `SCAN_MODE_LOW_LATENCY`, with no user-facing battery/latency setting.**
  Duty-cycled scanning and scan-result batching were tried for battery life and reverted in
  1.6.3: on real rides the partner dropped out for 15–30 s at a time. A Performance/Battery-Saver
  toggle was also implemented and removed — riders have no way to judge that tradeoff. Please
  don't reintroduce either without asking first.
- **Alerts go through karoo-ext** (`PlayBeepPattern` via `KarooSystemService`); standard Android
  audio does not reach the Karoo buzzer. The in-ride field is RemoteViews-only — a layout from
  `res/layout/`, with the `TextView` sizing its own text (TECHNICAL.md §7.2).
- **The extension id `partnerride`** must stay in sync across `PartnerRideExtension`,
  `extension_info.xml`, and every `DataTypeImpl`'s `typeId`.

## Style and housekeeping

- Match the style of the surrounding code. There is no autoformatter configured, so there is
  nothing to run — just don't reformat code you aren't changing.
- **Keep `TECHNICAL.md` in sync** with any protocol or algorithm change. It is the reference for
  the wire format, the time model and the gap algorithm; a change that makes it wrong is
  incomplete.
- Commit messages: plain imperative summaries, matching the existing history. No prefix
  convention.
- **Don't bump `versionCode`/`versionName` in a pull request.** Releases are cut by the
  maintainer, and the release workflow fails if the tag and `versionName` disagree — version bumps
  belong to that process, not to feature branches.
- Target `master`.

## Licence

PartnerRide is licensed under the Apache License 2.0. By contributing, you agree your
contributions are licensed under the same terms (Apache-2.0 §5). There is no CLA and no
sign-off requirement.
