# PartnerRide

A [Hammerhead Karoo](https://www.hammerhead.io/) extension for two riders. Each Karoo sends out
its own GPS position over Bluetooth Low Energy and picks up the partner's; a custom ride data
field shows the live straight-line distance between the two of you, with an arrow for who is
ahead (`42 m ▲` = partner 42 m ahead, `▼` = behind), on a green/yellow/red background.

One identical app runs on both riders' devices — no pairing, no phone, no internet.

## What you need

- Two Hammerhead Karoo devices (Karoo 2 or Karoo 3), one per rider.
- PartnerRide installed on both, in the **same version** — different versions cannot see each
  other.
- Nothing else. No phone, no mobile signal, no account, no subscription. The two Karoos talk
  directly to each other.

## Why you'd want this

You are out riding with your partner, one of you a little ahead of the other, close enough to
talk. Then the rider behind gets dropped:

- only the rider in front got past the car or the roundabout,
- the traffic light turns red right after the first one crosses,
- a chain comes off, a bottle drops, a tyre goes soft.

The rider in front usually notices far too late — often only at the next junction, several
minutes and a couple of kilometers later. Then come the phone calls, the turning around, and the
waiting.

With PartnerRide, the number on the ride screen starts growing the moment it happens: 20 m,
40 m, 80 m, and the background turns yellow, then red. The rider in front knows within seconds
and can look back, ease off, soft-pedal, stop, or ride back — while the two of you are still
within sight of each other.

It works the other way round just as well: as long as the number stays small and green, your
partner is right there behind you, and you can stop checking over your shoulder.

## How it works

![Two riders, each with a Karoo. Both get their position from GPS and send it straight to the other
bike over Bluetooth — no internet, no phone — and the distance between the two of you appears on
your ride screen](art/partnerride-overview.svg)

**A shared code links the two Karoos.** Both riders enter the same six-digit couple code, for
example `428713`. Your Karoo only listens to the partner carrying that code and ignores
everything else around it. There is no pairing step of the kind you know from a phone, a heart
rate strap or a power meter: nothing to search for, nothing to connect, nothing that can fall
apart mid-ride. The code alone is what pairs you, and it stays on both devices between rides.

**Each Karoo sends out its own position.** About once a second your Karoo takes its GPS
position and sends it straight to your partner's Karoo over Bluetooth. That signal reaches
roughly 50–150 m, and it never leaves the two bikes — there is no phone, no server and no
internet involved anywhere.

**The other Karoo works out the gap.** It compares the position it just received with its own
position and shows the distance between you, along with an arrow for whether your partner is
ahead or behind and a color for how big the gap is: green up to 15 m, yellow up to 50 m, red
beyond that.

A received position is always a fraction of a second old by the time it arrives, and at 30 km/h
that is already a few meters. So before comparing, your Karoo moves the older of the two
positions a little further along the direction and speed that rider was travelling — the same
guess you would make yourself watching someone ride away from you. The displayed number is also
averaged over the last few readings, so it doesn't flicker with normal GPS noise.

## Privacy

- **Nothing leaves the two bikes.** PartnerRide has no internet permission, makes no network
  calls, and has no server, no analytics and no crash or usage reporting of any kind. Your
  settings (couple code, alert threshold) are stored on the device only.
- **The Bluetooth signal itself is not encrypted.** Every packet carries your live GPS position
  (position, speed, heading) and a short tag derived from your couple code, in the clear (see
  [TECHNICAL.md](TECHNICAL.md) for the exact layout). Anyone with the right receiver within
  range — roughly 50–150 m, the same range the link itself works at — can read it. There is no
  pairing or connection to keep it private, and that is exactly what lets the link work without
  either device touching a phone or the internet. The packet format is versioned, so an
  encrypted version could be added later without breaking older devices.
- In practice this is the same trust model as an unencrypted power meter or heart rate
  broadcast: someone nearby with the right receiver can listen in, but it is a live, local
  signal, not something collected, logged or retained anywhere, by this extension or by anyone
  else.

## Install on the Karoo

Grab `app-release.apk` from the [latest release](../../releases/latest). Via the Hammerhead
Companion app, no cables needed:

1. Install the **Hammerhead Companion** app on your phone and pair it with the Karoo.
2. Copy `app-release.apk` to the phone (AirDrop/Drive/USB/email...).
3. Open the APK on the phone (tap it in your file manager or the "share" sheet) and choose to
   open/share it with the Hammerhead Companion app → it installs onto the paired Karoo.
4. **On the Karoo, open the PartnerRide app once from the launcher** — extensions register with
   Karoo OS only after the first launch.

Repeat for **both** devices. Both need the same app version; mismatched versions simply won't
see each other.

## First-time setup (both devices)

1. On the Karoo: open PartnerRide. Grant the Bluetooth and Location permissions when prompted.
2. **Device A:** tap **Generate** — it creates a 6-digit code like `428713`.
3. **Device B:** type exactly those 6 digits into the couple-code field.
4. Enable **PartnerRide enabled** on both devices. **Your Karoo only starts sending once it has
   a GPS fix** — until then the status section keeps showing *Broadcasting: no*, even though
   everything is set up correctly. Indoors that fix may never arrive, so don't judge the setup
   from your desk: take both devices outside and give them a minute. Once both have a fix and
   are in range, the status shows *Broadcasting: yes* and a partner signal age of a few seconds.
5. Add the field to a ride page: Profiles → edit page → add field → **PartnerRide → Partner Gap**.
   Full-width and half-width cells are both supported.
6. Optional: enable the **Gap alert** and set a threshold (default 100 m). It beeps and flashes
   once each time the smoothed gap first exceeds the threshold, and re-arms after the gap closes.

The link runs whenever the extension is enabled — no ride recording needed, and the number
updates about once a second.

## Data field states

| Display | Meaning |
|---|---|
| `42 m ▲` on green/yellow/red | Live gap; ≤15 m green, ≤50 m yellow, >50 m red |
| `~180 m · 8 s` on red | No signal for >5 s: last known value + its age |
| `NO SIGNAL` on red | Partner signal lost for >60 s (usually: out of range) |
| `NO SIGNAL` on gray | Running and sending, but no partner heard yet |
| `NO GPS` on gray | No own GPS fix (yet, or for >10 s) — nothing is being sent |
| `NO BT` on gray | Bluetooth is off |
| `NO CODE` on gray | No couple code entered yet (or fewer than 6 digits) — nothing is sent |
| `NO PERM` on gray | Location/Bluetooth permissions missing — open the app to grant them |
| `OFF` on gray | PartnerRide is disabled (or its service is not running) |

Gray states mean "not working, but nobody is being dropped"; red is reserved for a wide gap and
for losing your partner mid-ride.

## Troubleshooting

**The two of you never see each other** (`NO SIGNAL` on gray on both devices) — work down this
list, both devices at a time:

- **Same couple code?** All six digits, exactly the same on both.
- **Same app version?** Different versions cannot see each other at all. This is by far the most
  common cause.
- **Enabled on both?** The **PartnerRide enabled** toggle in the app, not just the field on the
  ride page.
- **Does each Karoo have a GPS fix?** Nothing is sent before the first fix — check for
  *Broadcasting: yes* in the app, outdoors.
- **Bluetooth on and permissions granted?** The field says `NO BT` or `NO PERM` if not.
- **Close enough?** The link reaches roughly 50–150 m in the open, less with buildings, terrain
  or a group of riders in between.

**PartnerRide doesn't appear in the list of data fields:** open the PartnerRide app once from
the Karoo launcher, then look again — extensions only register with Karoo OS after their first
launch.

**The number looks off by a few meters:** that's expected. Each Karoo's GPS is accurate to about
3–5 m, so the gap between two of them has an error floor of roughly 5–10 m. Treat a small gap as
"together", not as an exact measurement.

## About the two Android permission pop-ups

On first launch PartnerRide asks for **location** and **nearby devices (Bluetooth)** using the
plain Android system dialogs. They look out of place on the Karoo — no app can style those
dialogs — and you won't have seen them from most other extensions. That's expected:

- Most extensions get their data *through* Karoo OS, where Hammerhead's own software holds the
  permissions. PartnerRide can't use that path: the data Karoo OS hands out carries no GPS
  timestamp, speed or heading, and comparing two moving riders accurately depends on all three
  (see [TECHNICAL.md](TECHNICAL.md) for the full reasoning). So it reads GPS directly, which
  needs the location permission.
- The direct Bluetooth link between the two Karoos is something Karoo OS offers no interface
  for, so the nearby-devices permission is genuinely required.

Both dialogs appear **once per install**; after granting, they never return.

## Known limitations

- **Range is roughly 50–150 m** in the open, less with bodies or terrain in the way. Beyond that
  the field shows the last known gap for 60 s, then `NO SIGNAL`. The link recovers by itself as
  soon as you are back in range — there is nothing to reconnect and nothing that can get stuck.
- **GPS accuracy floor:** each device is accurate to ±3–5 m, so the displayed gap has an error
  floor of roughly 5–10 m. Treat small gaps as "together", not as centimeter truth.
- **Straight-line distance:** on switchbacks and hairpins the distance along the road between
  you can be much longer than the displayed straight-line gap.
- The Bluetooth signal is unencrypted — see [Privacy](#privacy) above.
- Exactly one partner is supported.

## Developers

- [TECHNICAL.md](TECHNICAL.md) — how to build the app, install it over adb, and the internals:
  the Bluetooth protocol, the wire format, the time model, the gap algorithm and the project
  layout.
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to report a bug or open a pull request, what is
  covered by tests, and the invariants a change must not break.
