# Blick

A scheduled Android departure display for regular SL commuters, across any SL transport
mode (bus, metro, train, tram, ferry). Users choose a stop, transport mode, line,
direction, weekdays, and time window; during that period, upcoming departures and
relevant disruptions appear in one quiet, updating lock-screen notification. See
`docs/Blick_Project_Documentation.md` for the full product specification.

## Repository layout

```
blick/
├── android/    Kotlin + Jetpack Compose client (see android/README.md)
├── backend/    TypeScript/Hono proxy in front of SL Transport + SL Deviations
│               (see backend/README.md)
├── docs/
│   ├── Blick_Project_Documentation.md / .pdf         the product spec
│   ├── api-contract.md                              backend API contract + upstream
│   │                                                  field-by-field mapping
│   └── framework.svg                                 end-to-end data flow diagram
└── .github/workflows/   CI: backend-ci.yml (Node 22 typecheck/lint/test/build/audit),
                          android-ci.yml (JDK 17 wrapper validation, assembleDebug,
                          lintDebug, testDebugUnitTest)
```

## How it works

![Blick data flow](docs/framework.svg)

**Currently implemented (foreground, manual):** the user creates a routine, opens its
details screen, and the app requests departures for that routine through the backend,
which in turn requests SL Transport (and, for disruption data, SL Deviations); the app
filters the result down to what matches the saved routine and shows the next two
matching departures. Refreshing is manual — the user taps Refresh — for any of the
supported SL transport modes (bus, metro, train, tram, ferry).

**Not yet implemented:** the app does not automatically activate at a routine's start
time, there is no periodic/background refresh, and the planned always-on 30-second
active-routine loop feeding a single updating lock-screen notification does not exist
yet — see `docs/framework.svg` and `android/README.md` for exactly which parts of the
diagram are built versus still planned.

## Status

Foundation plus an initial, real feature set — not yet the finished product described
above. The backend's contract, normalization, and caching logic are implemented and
tested (183 passing tests). The Android side has routine creation (live SL stop search,
transport mode/line/direction discovery, Room persistence), a foreground routine
details/live-preview screen (manual refresh, next two matching departures, and
loading/live/no-departures/offline/stale/unavailable states), and full routine
management (editing, enable/disable, pause/resume today, deletion, and a first-beta
one-routine limit) implemented and tested. **Not yet implemented:** notifications (and
their runtime permission onboarding), the scheduler that would activate a routine
automatically and react to routine changes or a device reboot, the 30-second background
refresh loop, persistent stale-data storage, and the widget. See each subproject's
README for specifics and known limitations.

## Roadmap beyond this MVP

Explicitly planned, **not implemented** anywhere in this repository yet:

- A native iPhone client consuming the same backend contract (`docs/api-contract.md`
  §9). The backend, its models, and its tests were deliberately kept independent of
  Android for this reason — no Swift, SwiftUI, Flutter, React Native, or Kotlin
  Multiplatform code exists in this repo.
- Investigating ActivityKit / Live Activities, APNs, and iOS background-execution
  constraints, once an iPhone client is actually scoped.
- A possible Smart TV departure-board experience — preferably a secure web or casting
  display reusing the existing backend, rather than a native TV app, and treated as a
  separate client rather than a requirement of the mobile MVP.
- Cross-device routine synchronization or pairing, only if a genuine product need for it
  emerges. No accounts, cloud sync, or device pairing exist today.

Explicitly out of scope for the current MVP (unchanged from the original product spec):
user accounts, GPS/location tracking, analytics beyond minimal reliability telemetry,
arbitrary point-to-point journey planning, walking-time advice, and aggressive alert
sounds.
