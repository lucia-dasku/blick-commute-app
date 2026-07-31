# Blick

A scheduled Android departure display for regular SL commuters, across any SL transport
mode (bus, metro, train, tram, ferry). Users choose a stop, transport mode, line,
direction, weekdays, and time window; during that period, upcoming departures and
relevant disruptions appear in one quiet, updating lock-screen notification — requesting
promotion to Android 16's prominent Live Update surface (Samsung's Now Bar where
supported), with a plain ongoing notification as the automatic, transparent fallback on
older devices or wherever the OS/OEM doesn't promote it. See
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

**Currently implemented:** the user creates a routine (any of the supported SL
transport modes — bus, metro, train, tram, ferry), which is saved locally and scheduled
for its next active window via WorkManager (best-effort, not exact — see
`android/README.md`). Whether or not the app is open, that window activates
automatically, requesting departures for the routine through the backend (which in
turn requests SL Transport and, for disruption data, SL Deviations), filtering the
result down to what matches the saved routine, and showing up to two matching
departures in one ongoing, lock-screen-visible notification (requesting promotion to
Android 16's Live Update surface — see the Status section below) that updates silently
about every 30 seconds and is removed at the routine's configured end time. A
debug-only manual "Show/update test notification" control also remains available in
debug builds. Separately, opening the routine's details screen fetches immediately and
again automatically about every 30 seconds while that screen stays open — independent
of the notification loop — with manual Refresh also available.

The notification also always carries a Stop action ("Stop/Unpin" the current window
early — same effect as "pause for today").

**Not yet implemented:** the home-screen widget — see `docs/framework.svg` and
`android/README.md` for exactly which parts of the diagram are built versus still
planned.

## Status

Foundation plus a real, largely end-to-end feature set, device-verified on a physical
tablet. The backend's contract, normalization, and caching logic are implemented and
tested (183 passing tests). The Android side has routine creation (live SL stop search,
transport mode/line/direction discovery, Room persistence), an always-visible
Add-routine control (with an explicit, in-place explanation — never a creation flow
that can't save — once the current first-beta one-routine limit is reached), a
foreground routine details/live-preview screen (automatic 30-second refresh plus manual
refresh, next two matching departures, and
loading/live/no-departures/offline/stale/unavailable states), full routine management
(editing, enable/disable, pause/resume today, deletion), production
notification-permission onboarding with a lifecycle-aware status hint that always
re-checks on resume, and the full ongoing-notification loop
(`WorkManagerRoutineScheduler` + `RoutineActiveWindowWorker` resolving each routine's
window against the device's own local time zone, activating it, checking notification
availability before ever entering foreground execution, posting/silently updating one
stable notification every ~30 seconds, and removing it and rescheduling at window end).
A dedicated `BOOT_COMPLETED` receiver re-schedules every enabled routine immediately
after a reboot, alongside the existing process-start reconciliation pass and the
runtime-registered `ACTION_TIMEZONE_CHANGED` receiver for a live device timezone change.
The last successful departure snapshot used for the `Stale` fallback is durably
persisted (Room-backed, scoped to the routine's exact site/line/direction/mode) rather
than held only in memory, so it survives the app's process being killed and recreated,
and is shared between the foreground preview and the background notification loop.
260 JVM `@Test` functions and 21 instrumented `@Test` functions exist in source as of
this update, all passing in a fresh local `./gradlew testDebugUnitTest lintDebug
assembleDebug connectedDebugAndroidTest` run (0 lint errors, debug APK built, all
instrumented tests run on a physical device) — see `android/README.md`'s Build section
for the exact toolchain and test breakdown. **Not yet implemented:** the home-screen
widget. See each subproject's README for specifics and known limitations.

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
