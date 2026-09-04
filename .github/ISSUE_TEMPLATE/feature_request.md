---
name: Feature request
about: Suggest a change to how PartnerRide behaves
title: ''
labels: enhancement
assignees: ''
---

<!--
Opening this before writing code is the point: scope gets agreed here, before you
invest the work. See CONTRIBUTING.md.

A few things have already been tried and deliberately reverted, so please check
these before filing:

- A Performance/Battery-Saver toggle, and duty-cycled scanning generally. Shipped in
  1.5.0-1.6.2, reverted in 1.6.3: on real rides the partner dropped out for 15-30 s
  at a time. Battery-for-latency trades are not wanted here.
- More than one partner. PartnerRide is deliberately a two-rider link.
- Encrypting the payload is welcome, but it is a protocol v2 with its own constraints
  (31-byte advertising PDU, both riders must upgrade together) - see SECURITY.md
  section "The path to an encrypted v2".
-->

## The problem

<!-- What doesn't work well today, on an actual ride. Not the solution yet. -->

## What you'd like instead

<!-- Your proposed behaviour. -->

## Alternatives you considered

<!-- Including "live with it" - sometimes that's the right answer. -->

## What it would touch

<!--
Rough guess is fine, it just helps size the discussion:

- core/ (packet codec, gap engine, field state machine) - testable on the JVM
- service/ or extension/ (BLE, GPS, the in-ride field, settings UI) - needs two
  physical Karoos to verify, CI can only prove it compiles
- Does it change the packet format? That forces an all-or-nothing upgrade for both
  riders, so it needs discussing before implementation.
-->

## Anything else

<!-- Screenshots, links, how other apps solve it. -->
