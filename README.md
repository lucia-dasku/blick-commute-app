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

A saved routine drives the loop: the backend requests SL Transport and SL Deviations,
the app filters departures down to what matches the saved routine, and the display
refreshes every 30 seconds while active. This flow is the same regardless of which SL
transport mode (bus, metro, train, tram, ferry) the routine is for.

## Status

Architecture and foundation scaffold — not a feature-complete app. The backend's
contract, normalization, and caching logic are implemented and tested (181 passing
tests). The Android side has its package structure, DI, Room/DataStore, navigation, and
placeholder screens in place, with the actual routine-setup/notification/scheduling
flows still to be built. See each subproject's README for specifics and known
limitations.

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
