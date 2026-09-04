---
name: Bug report
about: Something isn't working on a ride
title: ''
labels: bug
assignees: ''
---

<!--
Please don't paste your couple code. It isn't a security boundary (see SECURITY.md),
but there's no reason to publish it either.

For an undisclosed security issue, use the Security tab -> "Report a vulnerability"
instead of this form.
-->

## What happened

<!-- What you saw, and what you expected instead. -->

## App version on each device

<!--
The single most useful thing in a report. Mismatched versions cannot see each other
at all, and that accounts for a large share of "it doesn't work" reports.
Settings -> PartnerRide, or the app's main screen.
-->

- Device A:
- Device B:

## Karoo model

<!-- 2 or 3, for each device. -->

- Device A:
- Device B:

## What the data field showed

<!--
One of: NO SIGNAL, NO GPS, NO BT, NO CODE, NO PERM, OFF, or a live value.
The field always names the *first* problem to fix, so the token says where to look:
README.md section "Data field states" explains each one, TECHNICAL.md section 7 gives
the precedence order. If it was a live value that looked wrong, say what it read and
roughly how far apart you actually were.
-->

## Sanity checks

<!-- Delete any that don't apply. -->

- [ ] Both riders entered the **same** couple code
- [ ] Both devices granted the location and nearby-devices permissions
- [ ] Both devices had Bluetooth on
- [ ] The app has been opened at least once on each device since installing

## Steps to reproduce

<!-- If it's reproducible. "Happens randomly after ~20 min of riding" is also useful. -->

1.
2.

## Anything else

<!--
Riding conditions if they seem relevant (distance between riders, terrain, whether
one of you was in a group), and a logcat excerpt if you have one:
adb logcat -d | Select-String partnerride
-->
