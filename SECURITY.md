# Security Policy

PartnerRide is a small hobby project maintained by one person. Reports are read and handled on a
best-effort basis — there is no guaranteed response time.

## Supported versions

Only the **latest release** is supported. There is no backport path, and one would not help
anyone: the packet format is validated strictly, so both riders must run the same version for
their devices to see each other at all. Every fix therefore reaches users as an all-or-nothing
upgrade on both devices. If you are running an older version, update before reporting.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting — the **[Security tab](../../security) → "Report a
vulnerability"**. That opens a thread visible only to you and the maintainer. There is no
security email address; the GitHub channel is the only one.

Please do not open a public issue for an undisclosed vulnerability. For anything already listed
under [Known and by design](#known-and-by-design) below, a normal public issue or discussion is
fine — those are documented properties, not undisclosed findings.

Helpful in a report: the app version on **both** devices, the Karoo model (2 or 3), what you
observed, and how to reproduce it.

## Scope

PartnerRide has no server, no backend and no network surface: the app declares no `INTERNET`
permission, makes no network calls, and has no analytics or crash reporting. There is no
infrastructure to report on — only the app itself, its BLE link, and its release pipeline.

### In scope

Anything that contradicts what [README.md](README.md) and [TECHNICAL.md](TECHNICAL.md) promise,
in particular:

- **Data leaving the device by any route other than the BLE broadcast.** The privacy claim is
  that nothing else does; a counter-example is a real finding.
- **A hostile or malformed advertisement that crashes, hangs or destabilises a receiver.** The
  receive-side validation pipeline (TECHNICAL.md §4) is specified so that every failure is a
  silent drop, never a crash. Anything that gets past that is in scope, including packets from
  unrelated devices sharing the `0xFFFF` test manufacturer ID.
- **A packet being accepted whose couple tag does not match the receiver's own** (§4, step 4),
  or a replay-guard bypass beyond what §5 documents.
- **Locally stored settings** (couple code, alert threshold) being readable by other apps on the
  device — they are documented as local-only.
- **The release and signing pipeline**: anything that could deliver a tampered or
  differently-signed APK through the documented update path (GitHub Releases and the
  `manifest.json` that `MANIFEST_URL` points at).

### Known and by design

These are documented properties of the version 1 protocol, not vulnerabilities. Reports about
them will be closed as by-design — but see [The path to an encrypted v2](#the-path-to-an-encrypted-v2).

- **The broadcast payload is plaintext.** Each advertisement carries the sender's live position,
  speed and heading, plus the couple-code tag, in the clear. Anyone with a BLE sniffer inside the
  link's own working range (roughly 50–150 m) can read it. There is no pairing and no connection
  to hide behind — that absence is what lets the link work without either device touching a phone
  or the internet. See README.md § Privacy and TECHNICAL.md §3.
- **The couple code is not a security boundary.** It is 6 digits (10⁶ ≈ 20 bits), and the
  broadcast tag is the first 4 bytes of its SHA-256. TECHNICAL.md §3.1 states outright that the
  tag is an identifier rather than a security boundary, and that with only 10⁶ possible codes it
  is trivially brute-forceable back to the code. The code exists to keep nearby couples from
  accidentally reading each other, not to keep a motivated party out.
- **Consequently, packets can be spoofed by someone in radio range.** Acceptance is decided by
  tag equality plus the replay guard (§4–§5) and nothing else, so anyone who observes or
  brute-forces the tag can transmit packets a device will accept, and thereby influence the gap
  it displays. This follows directly from the two points above.
- **The tag is static and linkable.** It does not change during a ride (or between rides, while
  the code stays the same), so an observer can correlate sightings of the same pair over time.
- **The displayed gap is safety-relevant only as an estimate.** GPS gives an error floor of
  roughly 5–10 m and the distance is straight-line, not along the road (README § Known
  limitations). Do not treat it as ground truth.

The intended trust model is the one README.md states: comparable to an uncoded ANT+ or Bluetooth
heart-rate broadcast — anyone nearby with the right receiver can listen in, but it is a live,
local-only signal that is not collected, logged or retained anywhere.

## The path to an encrypted v2

The plaintext payload is a version 1 tradeoff, not a permanent decision. The first payload byte
is a format version, and receivers already reject any version other than 1 (TECHNICAL.md §3–§4),
so an encrypted version 2 could be introduced without breaking anything on air — the two formats
can coexist.

A v2 is a welcome direction. Two constraints shape it: the whole packet has to stay inside the
31-byte legacy advertising PDU (v1 uses 23 of it), and because both devices must run the same
version, any format change is an all-or-nothing upgrade for both riders. Please open an issue to
discuss the design before implementing it — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Installing safely

Official release APKs are attached to [GitHub Releases](../../releases) and signed by CI with a
stable key. A build signed with a different key cannot be installed over an official one without
uninstalling first, which wipes the couple code and the other settings. Get APKs from the
releases page rather than from third parties.
