# Blick — Android

Kotlin + Jetpack Compose client. Talks only to the Blick backend (`../backend`),
never directly to SL Transport/SL Deviations — see `../docs/api-contract.md`.

## Status

A real, largely end-to-end feature set, not a bare scaffold. Implemented: package
structure, Gradle config, Compose navigation, Material 3 theme, Room (routines) +
Preferences DataStore (small settings) wiring, Hilt DI, repository/API client interfaces
with real DTO↔domain mapping, the routine-creation wizard (`ui/screens/routinecreate`,
with its own error/retry handling and regression tests), the live-departure engine
(`domain/usecase/GetLiveDeparturesUseCase` + `LiveDeparturesProcessor` — fetches, filters,
and prepares the next two relevant departures for a saved routine), the routine
details/live-preview screen (`ui/screens/routinedetails` — loads one saved routine and
shows its next departures, refreshing automatically about every 30 seconds while the
screen is open plus a manual Refresh action), and routine management: editing an existing
routine (same wizard/ViewModel as creation, reached via the `routine-edit/{routineId}`
route — see `RoutineCreateViewModel`'s edit-mode support), enable/disable,
pause-today/resume-today (with automatic cleanup of an expired pause on load), delete
(with an in-screen Material3 confirmation dialog), and a first-beta one-routine limit
enforced at the app/UI level: the "Add routine" FAB always stays visible, but tapping it
with a routine already saved shows an in-place dialog explaining the beta limit instead of
opening (and then blocking) the creation flow — see `RoutineListContent`'s doc and
`RoutineCreateViewModel.oneRoutineLimitReached`, kept as defence in depth for any other
entry point into that screen.

The ongoing-notification loop is fully implemented, not just its foundation:
`notification/RoutineNotificationMapper` (a pure mapper, no Android dependency —
converts a routine + the live-departures engine's state + an `Instant` into a
`RoutineNotificationModel`, recomputing each departure's countdown rather than trusting a
cached value), `notification/RoutineNotificationBuilder` (builds the real, single-channel,
`IMPORTANCE_LOW` `Notification` — ongoing, only-alert-once, publicly visible, an
`InboxStyle` expanded view for up to two departures, distinct copy per
Live/Stale/no-departures/offline/unavailable/loading state), `notification/AndroidRoutineNotifier`
(posts/cancels that one stable notification id, bound via Hilt as the app's single
`RoutineNotifier`), and a shared `notification/NotificationAvailabilityChecker` (permission
missing / app disabled / channel disabled / available — the single source of truth used by
the notifier, the worker, and the details-screen status hint, so all three agree). Tapping
the notification reopens the routine details screen for the correct routine
(`MainActivity.onNewIntent`), and a `BuildConfig.DEBUG`-only "Show/update test
notification" / "Remove test notification" pair on the routine details screen remains for
manual testing alongside the automatic loop.

Scheduling and activation are implemented via `scheduling/WorkManagerRoutineScheduler`
(enqueues/replaces/cancels a unique `OneTimeWorkRequest` per routine for its next active
occurrence, resolved against the device's own local time zone via an injectable
`DeviceZoneProvider` — never against the injected `Clock`'s own zone, which would
otherwise silently miscompute a Stockholm 07:30 routine as 07:30 UTC) and
`scheduling/RoutineActiveWindowWorker` (a `CoroutineWorker` that, once its window starts,
checks notification availability before ever entering foreground execution — it never
calls `setForeground()`, starts the ~30-second update loop, recreates a disabled channel,
or reports delivery as active if notifications are unavailable — then runs the loop while
the routine stays enabled and its window is open, and reliably reschedules the next
eligible occurrence on normal completion, a handled fetch/notification failure, a late
start, or unavailability, while a genuine `CancellationException` from
delete/disable/replacement propagates unconverted and does not resurrect the work).
Production notification-permission onboarding (`ui/notification/NotificationPermissionGate`)
gates a proper rationale UI behind `AppSettingsDataStore.hasSeenNotificationRationale`,
and the routine details screen's own notification-status hint re-checks availability on
every lifecycle resume (e.g. after returning from system settings), not just once. A
device timezone change is reconciled live via a runtime-registered
`ACTION_TIMEZONE_CHANGED` receiver in `BlickApplication` calling the same
`RoutineScheduleReconciler` used at process start.

**Still not implemented**: a dedicated `BOOT_COMPLETED` receiver (reboot currently relies
on WorkManager's own persistence plus the process-start reconciliation pass, not an
explicit boot receiver), persistent stale-data storage across process death, notification
action buttons, and the widget.

## Pinned versions and why

| Setting | Value | Source |
|---|---|---|
| `compileSdk` / `targetSdk` | 36 (Android 16) | [apilevels.com](https://apilevels.com/) — Android 16 is the current stable release; Google Play requires `targetSdk 36+` for new apps/updates from Aug 31, 2026 ([Play Console target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)) |
| `minSdk` | 26 (Android 8.0) | **Product/support-coverage decision, not a technical requirement.** Notification channels only exist on API 26+, but a real implementation could support a lower `minSdk` by conditionally creating channels behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`. 26 was chosen to simplify the codebase during the MVP phase; revisit if real-world device data says otherwise. |
| Android Gradle Plugin | 9.2.1 | Patch release fixing a documented R8 `ClassNotFoundException` regression in 9.2.0; requires Gradle 9.4.1, JDK 17+ ([Android Gradle plugin release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes)) |
| Gradle | 9.4.1 | Paired with AGP 9.2.x per the same source. Wrapper jar and scripts are committed (`gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`), sourced directly from the `v9.4.1` tag of `github.com/gradle/gradle` and verified against its SHA-256 published at [gradle.org/release-checksums](https://gradle.org/release-checksums) — both `gradle-wrapper.properties`' `distributionSha256Sum` and the committed jar match the official value. |
| Kotlin | 2.3.10 | Pinned to match the Kotlin version AGP 9.2.x's **built-in Kotlin** support bundles by default (per AGP's own fixed-issues notes for 9.2.0/9.2.1). This project uses AGP's built-in Kotlin rather than the separate `org.jetbrains.kotlin.android` plugin — see "AGP 9 built-in Kotlin migration" below. |
| KSP | 2.3.9 | KSP moved to an independent version scheme starting at 2.3.0 (no longer `{kotlinVersion}-{kspVersion}`); 2.3.9 is its latest stable release, and 2.3.1+ added explicit support for AGP 9.0/built-in Kotlin. |
| Compose BOM | 2026.06.00 | Latest stable BOM per [Android Compose BOM docs](https://developer.android.com/develop/ui/compose/bom) |
| Hilt / Dagger | 2.59.2 | Must be 2.59+ — the Hilt Gradle plugin has no AGP 9 support before that release ([google/dagger#4944](https://github.com/google/dagger/issues/4944); [Dagger 2.59 release notes](https://github.com/google/dagger/releases/tag/dagger-2.59) confirm AGP 9 became a requirement for the Hilt plugin as of 2.59). 2.59.2 additionally fixes two more AGP-9-specific bugs found shortly after. |
| Room | 2.8.1 | Room 3.0 (Kotlin Multiplatform-focused) is alpha-only; 2.x remains the stable line and is what this scaffold uses — no KMP dependency was introduced, matching the "no Kotlin Multiplatform now" constraint |

Secondary library versions (navigation-compose, lifecycle, datastore, retrofit,
kotlinx-serialization/coroutines, test libraries) are pinned in
`gradle/libs.versions.toml` to reasonable current-as-of-scaffold versions but were not
each individually re-verified against the Maven registry the way the rows above were —
bump them there as needed.

## Known limitations (see `../docs/api-contract.md` for full detail)

- **Direction discovery is incomplete.** `LiveDeparturesDirectionOptionsSource` (the only
  implementation of `DirectionOptionsSource`) can only offer lines/directions that are
  currently running in SL Transport's live forecast window. A route that isn't running
  right now won't be selectable during setup. This is intentionally not solved with a
  Journey Planner/GTFS dependency in this scaffold — the interface exists so the data
  source can be swapped later without touching Room or the UI.
- **Scheduling is best-effort, not exact.** `scheduling/WorkManagerRoutineScheduler` uses a
  plain `OneTimeWorkRequest` with a computed initial delay, not `AlarmManager`'s exact-alarm
  APIs (explicitly out of scope — see this doc's project instructions), so Android may
  briefly defer the initial execution under Doze/battery-saver the same way any other
  WorkManager job can be deferred. The notification/details-screen refresh cadence is
  "about every 30 seconds," not a guaranteed exact interval, and one notification is updated
  silently in place rather than posting a new card each refresh.
- **Attribution is not yet wired into a real screen.** `R.string.attribution_text`
  ("Based on information from Trafiklab.se") exists, but no About/Settings screen
  displays it yet — see `../docs/api-contract.md` §8 before shipping publicly.

## Pointing at a deployed backend

`NetworkModule` reads the backend URL from `BuildConfig.BACKEND_BASE_URL`, generated from
the `BLICK_BACKEND_BASE_URL` Gradle property (see `app/build.gradle.kts` `defaultConfig`).

**The default already points at the live deployment**, `https://blick-backend.vercel.app/`
— a normal build with no property set talks to the real backend. Only override this if
you deliberately want to point at a different backend (a locally-run one, a staging
deployment, etc.):

```
# gradle.properties (repo-local, or ~/.gradle/gradle.properties for a machine-local
# override that never gets committed):
BLICK_BACKEND_BASE_URL=http://10.0.2.2:8787/

# or per-invocation:
./gradlew assembleDebug -PBLICK_BACKEND_BASE_URL=http://10.0.2.2:8787/
```

**History:** this used to default to an intentionally-unreachable placeholder host,
reasoning that an unconfigured build should fail loudly rather than silently work against
the wrong thing. In practice this backfired — a 2026-07-28 incident traced a "no stops
found" bug in the app all the way back to a build (run from Android Studio's Run button)
that never picked up the Gradle property override and silently fell back to that
placeholder, which is a much easier mistake to make across machines/IDE syncs than to
notice. The "fail loudly" goal is now instead served by the app itself surfacing a visible
(friendly, not raw-exception) failure state on a real network error (see
`RoutineCreateViewModel`'s `searchFailed`/`directionsFailed`/`saveFailed`) rather than by
making the default backend unreachable.

### Redeploying the backend

```
cd backend
npm install
npx vercel login      # once, interactive
npx vercel deploy --prod
```

If Vercel ever prints a different production URL than `blick-backend.vercel.app`, update
the default above (`app/build.gradle.kts`) to match. Confirm a deployment is actually live
before relying on it:

```
curl https://blick-backend.vercel.app/api/v1/health
```


## Build

**Verified baseline (real local run, Android Studio's bundled JDK 17 + Android SDK):**
debug APK build succeeded; 117 JVM unit tests passed with zero failures as of the
stale-snapshot-identity fix; `lintDebug` completed with 0 errors and 37 warnings; and six
Room instrumented (`connectedDebugAndroidTest`) tests previously passed on a physical
Lenovo TB350FU running Android 14 — that connected-device run predates the newest
routine-details/live-preview and routine-management changes, so it covers the Room DAO
only, not those newer screens/ViewModels.

**The ongoing-notification foundation milestone, plus its subsequent correction passes,
added 76 more source-level `@Test` functions beyond that 117 baseline, for 193 total.**
A fresh local `./gradlew testDebugUnitTest lintDebug assembleDebug` run (Android
Studio's bundled JDK + Android SDK) has since validated all of them: `testDebugUnitTest`
passed with zero failures across all 193 tests, `lintDebug` completed with 0 errors and
37 warnings, and `assembleDebug` produced a debug APK. Breakdown: 28 in
`RoutineNotificationMapperTest` (plain JVM, no Android dependency); 32 across
`RoutineNotificationBuilderTest`/`AndroidRoutineNotifierTest` (Robolectric-backed,
targeting `@Config(sdk = [34])` — see `libs.versions.toml`'s `robolectric` entry for
why — including the disabled-notification-channel detection added in the latest
correction pass); 5 in `NotificationIntentCoordinatorTest` (Robolectric, covers the
one-time notification-tap intent consumption fix); 4 in `DebugNotificationMessageTest`
(Robolectric, covers the debug UI's `NotificationPostResult`-to-message mapping); 7
debug-trigger/notifier-result cases in `RoutineDetailsViewModelTest`. That run also
caught and fixed two real issues surfaced only by actually executing this code: a test
assertion that could false-fail against a departure's own line designation rather than
an actual countdown value, and two Android Lint findings — a `MissingPermission` false
positive on the already permission-guarded notification post, and two
`context.getString()` calls inside non-composable callbacks flagged for potential
staleness across configuration changes — all now fixed.

**Two further work sessions after the 193-test baseline above have added substantially
more JVM and instrumented source — 258 JVM `@Test` functions and 12 instrumented
`@Test` functions exist in source as of this update. None of the 65 JVM tests or 3
instrumented tests beyond the 193/9 baseline have been compiled or run yet, in this
environment or on the project owner's machine — they are new source only. The
193-JVM-test/`lintDebug`/`assembleDebug` run described above remains the last real,
executed validation.**

The first of those two sessions (the WorkManager-scheduling milestone: restored
Add-routine FAB, Routine Details 30-second auto-refresh, production
notification-permission flow, `WorkManagerRoutineScheduler`, and the
`RoutineActiveWindowWorker` active-window notification loop) added 42 JVM `@Test`
functions and 3 instrumented ones, reaching 235 JVM / 9 instrumented at the time.
Breakdown: 12 in `NextOccurrenceCalculatorTest` (plain JVM, next-active-window
computation including both sides of daylight-saving transitions); 9 in
`WorkManagerRoutineSchedulerTest` (Robolectric + a real `WorkManager` test instance via
`WorkManagerTestInitHelper`, covering enqueue/replace/cancel of the unique per-routine
work item); 8 in `RoutineActiveWindowWorkerTest` (`TestListenableWorkerBuilder` with a
fake worker factory, covering the tick loop, late-start skip, disable/delete mid-run,
and the stale-fallback-on-fetch-failure case); **11** (not 13, an arithmetic error in
an earlier draft of this note, now corrected) new cases added to
`RoutineDetailsViewModelTest` covering the 30-second auto-refresh lifecycle
(immediate/duplicate-free first fetch, fetch after 30s, cancellation, restart, and the
scheduler-integration calls from enable/disable/pause/resume/delete), plus one new test
each in `RoutineCreateViewModelTest` (save schedules activation) and
`RoutineListViewModelTest` (delete cancels activation); and 3 new instrumented
`RoutineListScreenTest` cases.

A second, corrective session then fixed several audited issues in that milestone
(device-local timezone resolution, checking notification availability before
foreground execution, lifecycle-aware notification-status refresh in the UI, and an
explicit one-routine Add-button explanation) and added the tests proving them: 3 more
in `WorkManagerRoutineSchedulerTest` (Stockholm summer/winter activation time,
timezone-change recalculation); 7 more in `RoutineActiveWindowWorkerTest` (permission
missing/app-disabled/channel-disabled/missing-channel-but-available before
`setForeground()`, a handled failure building the foreground notification, a handled
failure mid-loop, and a real cancellation that does not reschedule); a new
`RoutineScheduleReconcilerTest` (3 tests, plain JVM, no Robolectric) for the
reconciliation pass now shared between process start and a device-timezone-change
receiver; 3 more in `RoutineDetailsViewModelTest` (notification availability
reflecting the checker on first load and on every lifecycle resume, in both
directions); and the existing `RoutineListScreenTest` instrumented cases were expanded
from 3 to 6 to cover the one-routine explanation dialog. That is 23 further JVM tests
and 3 further instrumented tests, for the 258 JVM / 12 instrumented total stated above.

**The environment used to prepare and edit this repository cannot run that Gradle build
itself.** It has only a JRE (not a JDK) at Java 11, no Android SDK, and no network path
to `services.gradle.org` to provision either one, so `assembleDebug`, `lintDebug`, and
`testDebugUnitTest` could not be executed from it directly (confirmed again during the
correction pass: `./gradlew --version` still fails with `UnknownHostException:
services.gradle.org`). The 193-test validation above was performed locally, in Android
Studio, by the project owner — not from this environment. AGP 9.2.1 requires a JDK 17+
with `javac`. On a machine with a real JDK 17 and Android SDK (or Android Studio, which
provides both), build with:

```
cd android
./gradlew assembleDebug              # requires a local Android SDK (Android Studio, or `sdkmanager`)
./gradlew lintDebug                  # static analysis
./gradlew testDebugUnitTest          # JVM unit tests (RoutineCreateViewModelTest, RoutineDetailsViewModelTest, RoutineListViewModelTest, RoutineMappersTest, ...)
./gradlew connectedDebugAndroidTest  # instrumented Room DAO + RoutineListScreenTest (Compose UI), needs a device/emulator
```

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle-wrapper.properties` with `distributionSha256Sum`) is fully committed, so a fresh
clone builds without Android Studio or a pre-existing local Gradle install.

`.github/workflows/android-ci.yml` runs `assembleDebug`/`lintDebug`/`testDebugUnitTest`
on a real JDK 17 + Android SDK runner on every push — this is the genuine build
verification that couldn't happen while preparing the repository. Its runs have caught
three real issues so far, exactly what that workflow is there to catch:

- Hilt's Gradle plugin needing 2.59+ for AGP 9 support (see the version table above).
- A missing explicit `com.squareup.okhttp3:okhttp` dependency — `NetworkModule.kt` uses
  `okhttp3.MediaType`/`toMediaType()` directly, but Retrofit's own Gradle module metadata
  doesn't expose those classes on a consumer's compile classpath, so relying on it
  transitively compiled locally-unverified but failed on a real CI run. A missing
  `androidx.compose.material:material-icons-core` dependency for `Icons.Filled.Add`
  (used in `RoutineListScreen.kt`) was caught the same way — material3 doesn't pull it
  in either.
- The `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0`
  converter dependency resolving as a coordinate but its classes still not landing on
  the Kotlin compile classpath (`Unresolved reference 'asConverterFactory'`) — that
  legacy 2021 artifact ships only a `.pom`, no Gradle Module Metadata, which AGP 9's
  stricter variant-aware resolution doesn't handle the same way older AGP did. Fixed by
  switching to `com.squareup.retrofit2:converter-kotlinx-serialization`, the first-party
  artifact Retrofit itself has shipped this exact converter under since 2.10.0.

## AGP 9 built-in Kotlin migration

This project targets AGP 9's **built-in Kotlin** support rather than applying the
separate `org.jetbrains.kotlin.android` plugin (applying both fails with `Cannot add
extension with name 'kotlin', as there is an extension already registered with that
name`). Concretely, compared to a pre-AGP-9 Kotlin Android project:

- `libs.plugins.kotlin.android` (`org.jetbrains.kotlin.android`) is **not** applied
  anywhere — not in `gradle/libs.versions.toml`, not in the root `build.gradle.kts`, not
  in `app/build.gradle.kts`. AGP 9 supplies the Kotlin compiler itself.
- `android { kotlinOptions { jvmTarget = "17" } }` (the old DSL, which requires the
  `kotlin-android` plugin) was migrated to `kotlin { compilerOptions { jvmTarget.set(...)
  } }` (the built-in-Kotlin DSL) in `app/build.gradle.kts`.
- The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) and the
  serialization plugin (`org.jetbrains.kotlin.plugin.serialization`) are still applied
  explicitly and still pinned to the `kotlin` catalog version — both must exactly match
  whatever Kotlin compiler is actually in use, which is why `kotlin = "2.3.10"` in the
  catalog is chosen to match AGP 9.2.x's bundled built-in-Kotlin version rather than an
  arbitrary "latest" Kotlin release.
- Room's KSP annotation processing is unaffected (Blick never used kapt).
