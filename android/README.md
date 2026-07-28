# Blick — Android

Kotlin + Jetpack Compose client. Talks only to the Blick backend (`../backend`),
never directly to SL Transport/SL Deviations — see `../docs/api-contract.md`.

## Status

This is a scaffold, not a feature-complete app. Implemented: package structure, Gradle
config, Compose navigation between three placeholder screens, Material 3 theme, Room
(routines) + Preferences DataStore (small settings) wiring, Hilt DI, repository/API
client interfaces with real DTO↔domain mapping, and a real Room DAO test + a real
ViewModel test. **Not implemented**: the actual routine setup flow, notifications,
scheduling, and the widget — see `notification/`, `scheduling/`, and the placeholder
screens for the interfaces those will be built behind.

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
- **Notification/scheduling are interfaces only** (`notification/RoutineNotifier.kt`,
  `scheduling/RoutineScheduler.kt`) — no `NotificationCompat`, `AlarmManager`, or
  `WorkManager` implementation exists yet.
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

This project has been authored and structurally validated (brace/paren balance, XML
well-formedness, internal import resolution, Gradle Kotlin DSL syntax) but **has not
been compiled or run as part of preparing this repository**: that environment has
only a JRE (not a JDK) at Java 11, no Android SDK, and no network path to
`dl.google.com`/`repo.maven.apache.org`/`services.gradle.org` to provision either one —
so `assembleDebug`, `lintDebug`, and `testDebugUnitTest` could not genuinely be executed
here, and no result from them should be assumed. AGP 9.2.1 requires a JDK 17+ with
`javac`. On a machine with a real JDK 17 and Android SDK (or Android Studio, which
provides both), build with:

```
cd android
./gradlew assembleDebug              # requires a local Android SDK (Android Studio, or `sdkmanager`)
./gradlew lintDebug                  # static analysis
./gradlew testDebugUnitTest          # JVM unit tests (RoutineListViewModelTest, RoutineMappersTest)
./gradlew connectedDebugAndroidTest  # instrumented Room DAO test, needs a device/emulator
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
