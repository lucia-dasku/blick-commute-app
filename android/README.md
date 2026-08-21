# Blick — Android

## Free and Premium tiers

The app now uses one Google Play package with a central, server-verified entitlement source.
Free keeps one saved line-and-direction routine. The non-consumable one-time product
`blick_premium_lifetime` unlocks multiple non-overlapping routines and exact-destination
journeys. The displayed price always comes from Google Play; the planned 49 SEK value is a
Play Console configuration value, never app copy.

Routine creation is one unified form for both tiers. Origin is always active. Destination is
shown disabled with Premium guidance for Free users and becomes searchable for Premium users.
Premium may leave Destination blank to continue with line/direction, or select a destination to
skip those steps and save an exact-destination routine.

Exact-destination routines store Journey Planner origin/destination IDs separately from SL
Transport site/direction fields. Their Routine Details Transport row has a `+` multi-select whose
persisted allow-list controls foreground refreshes, the widget, and the notification worker. They
show the earliest-final-arrival journey and only label another journey Alternative when it uses a
different public-transport mode combination; a later trip on the same mode is not an alternative.
The compact widget shows only the fastest and a larger widget also shows a genuine alternative.
The ongoing notification intentionally projects only the fastest
journey's first public-transport leg into the existing notification model—never the exact
destination, alternative, transfer comparison, or final arrival.

Room schema version 5 adds the routine type and Journey Planner identity/display columns via
`MIGRATION_4_5`; version 6 adds the journey-mode allow-list via `MIGRATION_5_6`. Existing routines
start with all regular modes enabled. Schedule validation treats each
week as circular, handles overnight windows and the Sunday/Monday boundary, and permits touching
but not overlapping endpoints. This retains a single active 30-second worker and stable
notification owner.

If Premium is revoked or refunded, stored routines are retained. Premium-only routines remain
visible but locked; the user can select one existing line-and-direction routine to run as Free.
A previously verified Premium entitlement is cached only as a temporary outage fallback, never
as permanent proof of purchase. See [`../docs/play-console-checklist.md`](../docs/play-console-checklist.md)
before any release.

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
(with an in-screen Material3 confirmation dialog), central Google Play entitlement state,
tier-aware limits enforced in both UI and Room persistence, Premium exact-destination creation,
and multiple-routine schedule-overlap protection. Refunded/revoked Premium routines remain
stored and visible but locked; one selected line/direction routine remains eligible for Free.

The ongoing-notification loop is fully implemented, not just its foundation:
`notification/RoutineNotificationMapper` (a pure mapper, no Android dependency —
converts a routine + the live-departures engine's state + an `Instant` into a
`RoutineNotificationModel`, recomputing each departure's countdown rather than trusting a
cached value), `notification/RoutineNotificationBuilder` (builds the real, single-channel,
`IMPORTANCE_LOW` `Notification` — ongoing, only-alert-once, publicly visible, a
`BigTextStyle` expanded view for up to two departures, distinct copy per
Live/Stale/no-departures/offline/unavailable/loading state), `notification/AndroidRoutineNotifier`
(posts/cancels that one stable notification id, bound via Hilt as the app's single
`RoutineNotifier`), and a shared `notification/NotificationAvailabilityChecker` (permission
missing / app disabled / channel disabled / available — the single source of truth used by
the notifier, the worker, and the details-screen status hint, so all three agree). Tapping
the notification reopens the routine details screen for the correct routine
(`MainActivity.onNewIntent`), and a `BuildConfig.DEBUG`-only "Show/update test
notification" / "Remove test notification" pair on the routine details screen remains for
manual testing alongside the automatic loop.

Every notification `RoutineNotificationBuilder` builds also requests Android 16's
promoted-ongoing ("Live Update") surface via
`NotificationCompat.Builder.setRequestPromotedOngoing(true)` — the prominent lock-screen
card (Samsung's Now Bar where supported), not merely a notification-shade entry, per the
product doc's "Lock-screen Live Update" requirement. This is a *request*, never a
guarantee: the OS and OEM still decide based on real-time eligibility (Android 16+ only,
user settings, other active promoted notifications), which is exactly why `BigTextStyle`
replaced the previous `InboxStyle` expanded view above — promoted notifications are
restricted to a handful of styles (`BigTextStyle` among them) and `InboxStyle` is not one
of them — and why no separate "unpromoted" code path exists: the exact same `Notification`
this builds is also a perfectly valid plain ongoing notification on any device/state where
promotion doesn't happen. The soonest departure's own countdown (or "Cancelled") is set as
the promoted surface's short "status chip" text via `setShortCriticalText`. Requires the
normal, non-runtime `POST_PROMOTED_NOTIFICATIONS` manifest permission. A separate
`notification/PromotedNotificationChecker` (`canPostPromotedNotifications`) — distinct from
`NotificationAvailabilityChecker`, since promotion is an enhancement layered on top, never a
blocking condition — is surfaced through the existing debug notification section so
"whether promotion is available and enabled" can be verified without needing a real
Android 16 device's lock screen to confirm it by eye.

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
`ACTION_TIMEZONE_CHANGED` receiver in `BlickApplication`, routed through
`scheduling/NotificationRecoveryCoordinator.onTimeZoneChanged()` — the same coordinator
that also owns process-start (`onAppStart`) and foreground (`onForeground`) reconciliation,
serialized by one shared `Mutex` so none of the three can ever race and replace each
other's work. `onTimeZoneChanged` deliberately still calls `RoutineScheduleReconciler`'s
own unconditional `reconcileAll()` (replacing even an already-`RUNNING` worker — a
genuine timezone change must reinterpret a routine's configured local time against the
new zone even for a window that's active right now), while `onAppStart`/`onForeground`
instead respect `RoutineScheduler.isActivationRunning` and never replace one. There is no
dedicated Blick-specific `BOOT_COMPLETED` receiver: `Application.onCreate()` always runs
before any component executes in a freshly-started process, so WorkManager's own bundled
boot receiver (which independently re-establishes its persisted work's scheduling after a
reboot) already causes `onAppStart()` to run on every boot that starts the process at
all — a separate Blick-specific receiver only added an uncoordinated second reconciler
capable of replacing a worker `onAppStart()` had already correctly left running. The last
successful departure snapshot used for the `Stale` fallback is durably persisted via a
Room-backed `StaleSnapshotRepository` (`data/local/room/StaleSnapshotEntity.kt`), keyed by
routine id and scoped to the exact `DepartureIdentity` that produced it, and shared by both
`RoutineDetailsViewModel` and `RoutineActiveWindowWorker` — it survives the app's process
being killed and recreated, unlike a plain in-memory field, and its row is automatically
removed (`ON DELETE CASCADE`) whenever its owning routine is deleted.

The ongoing/promoted notification also always carries a Stop action (the spec's
"Stop/Unpin" control) — a plain `NotificationCompat.Action`, not a custom view, so it
stays valid on the promoted surface too. Its `PendingIntent` targets
`notification/StopRoutineNotificationReceiver`, an `exported="false"`
`@AndroidEntryPoint` `BroadcastReceiver` only ever triggered by this app's own explicit
intent, which hands off to `notification/StopRoutineNotificationAction` — kept as its
own plain, unit-tested class rather than logic embedded directly in the receiver (see
`scheduling/NotificationRecoveryCoordinator` for the same split behind `BlickApplication`'s
own receivers).
Tapping it has exactly the same effect as the existing "pause for today" control:
it writes `pausedDate` to today's date (resolved in the device's own zone, matching
`RoutineActiveWindowWorker`'s own break condition, not a zone-less clock) and
reschedules, which the worker's own next loop tick already observes and stops on —
and additionally removes the notification directly, so it disappears immediately on
tap rather than up to ~30 seconds later. `RoutineDetailsViewModel`'s own "pause
today"/"resume today" controls were fixed to resolve "today" the same device-zone way
(an injected `DeviceZoneProvider`, not a zone-less `LocalDate.now(clock)`) — the
previous zone-less resolution could pause the wrong calendar day shortly after local
midnight in any zone ahead of UTC (e.g. Sweden), a mismatch against the worker's own
device-zone break condition that the Stop action's introduction surfaced.

The debug notification section's promotion-status line now reads as an eligibility check
rather than a confirmation: `canPostPromotedNotifications()` only means the OS currently
permits requesting promotion, not that any specific OEM surface (e.g. Samsung's Now Bar)
actually rendered a card — see `PromotedNotificationChecker`'s KDoc and the Known
limitations entry below. When the routine details screen's notification status is
`Available` but promotion isn't currently eligible, a `LiveUpdatePromotionRow` now offers a
link straight to Android's own per-app Live Update settings
(`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` via
`ui/notification/promotedNotificationSettingsIntent`) — but only on Android 16+
(`Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA`), since
`isLiveUpdatePromotable` is unconditionally `false` below that and the settings screen
itself doesn't exist there either; on an OEM build that omits the screen even on
Android 16+, `launchLiveUpdateSettings` catches the resulting `ActivityNotFoundException`
and falls back to the ordinary per-app notification settings screen instead of leaving
the tap do nothing. A real Android control, but distinct from and unable to reach Samsung's
separate Now Bar developer gate (see Known limitations below). A simple `ui/screens/about/AboutScreen`
(reached via an info icon in the routine list's top app bar) now shows the app name, a
tagline, the version and build number, `R.string.attribution_text`, a link to
Trafiklab.se, a non-affiliation disclaimer, the full privacy policy (last-updated date,
what Blick does and does not collect, where routine/preference data is stored, what the
backend receives, and a contact address for privacy questions), and a centered copyright
line, closing the `../docs/api-contract.md` §9 attribution requirement and giving the app
its first real privacy policy.
`LiveDeparturesDirectionOptionsSource` now requests the `forecast` window at Blick's own
tested cap (1200 minutes ≈ 20 hours — an empirically observed value, not a maximum
documented or guaranteed by Trafiklab/SL; see
`../docs/api-contract.md`'s departures endpoint entry) instead of the live-display
default, so routine setup can offer directions for routes that aren't running at the
exact moment of setup but do run at least once every 20 hours; see Known limitations
below for the residual gap this doesn't close.

`AndroidManifest.xml` now declares `android:dataExtractionRules` (`xml/data_extraction_rules.xml`)
and `android:fullBackupContent` (`xml/full_backup_content.xml`) — an explicit decision
to keep Blick simple by never letting a saved routine transfer or restore onto a new
device by any mechanism. `android:allowBackup="false"` already disables cloud backup and
adb backup everywhere, but Android's own docs note it doesn't reliably disable Android
12+ device-to-device "Copy your data" transfer on every OEM; both XML files explicitly
exclude every backup/transfer domain (`root`, `file`, `database`, `sharedpref`,
`external` — not just `root` alone, since Android's schema treats these as separate,
non-overlapping domains) from both cloud backup and device transfer, so the Room
database and DataStore preferences are covered either way (see the XML files' own doc
comments). The
adaptive launcher icon also gained a `<monochrome>` layer (`ic_launcher_monochrome.xml`)
for Android 13+ themed-icon support, and the manifest now declares `android:roundIcon`
pointing at the `ic_launcher_round` resource that already existed but was unused.

A home-screen widget (`widget/BlickRoutineWidget`, Jetpack Glance — `androidx.glance:glance-appwidget`
1.1.1, the latest stable release) is now implemented, deliberately reusing every existing
piece of machinery rather than building a second one: it shows the same routine name,
station and direction, next and following departure (a minute-only countdown always
recomputed against the current instant via the same `countdownMinutes` function the
notification uses — never a fixed or cached value), and the same
loading/live/stale/offline/unavailable/no-upcoming-departures states, with a cancelled
departure flagged the same way. `RoutineWidgetMapper` is the widget's exact counterpart
to `RoutineNotificationMapper`, applying the identical expired-departure filter (a
departure whose effective time has passed by render time is dropped, exactly like the
notification) to the identical `LiveDeparturesState` input. `RoutineWidgetUpdater`
(`updateWithDepartures`/`clear`/`reconcile`/`showNotificationsUnavailable`) is called
from `RoutineActiveWindowWorker`'s existing ~30-second loop — right after the same
tick's `RoutineNotifier.showOrUpdate` call, using the exact same already-fetched
`CommuteRoutine`/`LiveDeparturesState` — and from every one of that worker's exit paths
that cannot continue an active commute (missing/deleted/disabled routine, an elapsed or
late-started window, notifications unavailable at startup or discovered mid-loop, and a
handled failure before or during foreground execution), each mapped to whichever of
`clear`/`reconcile`/`showNotificationsUnavailable` correctly represents that specific
exit — see `RoutineActiveWindowWorker.doWork`'s own doc for the full enumeration; no
second worker, timer, foreground service, or departure engine exists. Every other
routine-lifecycle mutation (create/edit save, enable/disable, pause/resume, delete, the
notification's own Stop action via `StopRoutineNotificationAction`),
`NotificationRecoveryCoordinator` (covering process start, foreground, and
notification-availability recovery — which itself also covers reboot), and
`RoutineScheduleReconciler.reconcileAll()` (covering device-timezone change, invoked via
that same coordinator's `onTimeZoneChanged()`) call `RoutineWidgetUpdater.reconcile()`,
which reuses `NextOccurrenceCalculator` (the same active-window calculation the worker
and scheduler already use) to decide the correct resulting state from scratch — including
checking `NotificationAvailabilityChecker` itself, so `reconcile()` reports
`NotificationsUnavailable` rather than a misleading `Loading` placeholder for an active
window whose notifications are blocked. `BlickRoutineWidgetReceiver` also calls
`reconcile()` from its own `onUpdate` (in addition to, never instead of, Glance's own
per-instance render from persisted state — see that class's own doc), specifically so a
newly-placed widget instance derives its correct current state immediately instead of
defaulting to "No active commute." until the next lifecycle event. Outside any active
window the widget reads exactly **"No active commute."**
(`R.string.widget_no_active_commute`). **Live widget updates depend on notification
availability**, by design: the worker's loop — the widget's only data source, exactly
like the notification — only runs while `NotificationAvailabilityChecker` reports
`Available`, so if notifications are unavailable (permission missing, app disabled, or
the Blick channel disabled) during an otherwise-active window, the widget shows
`NotificationsUnavailable` instead of live departures; this is an accepted design
tradeoff to avoid a second, notification-independent departure-fetching engine, not a
platform limitation. Tapping the widget opens the routine details
screen via `routineDetailsTapIntent`, reusing `MainActivity`'s existing
`RoutineNotificationIds.EXTRA_ROUTINE_ID` navigation contract unchanged — the same one
the notification's own tap already uses. All installed widget instances update together
(`GlanceAppWidgetManager.getGlanceIds` + `BlickRoutineWidget.updateAll`); state
persists per-instance via Glance's built-in `PreferencesGlanceStateDefinition`. The
provider (`res/xml/blick_routine_widget_info.xml`) sets `android:updatePeriodMillis="0"`
deliberately — Android's own widget-update scheduler is never used — and declares both
the legacy (`minWidth`/`minHeight`/`minResizeWidth`/`minResizeHeight`) and Android 12+
(`targetCellWidth`/`targetCellHeight`/`maxResizeWidth`/`maxResizeHeight`) sizing
attributes for ordinary resizing on every supported launcher; `BlickRoutineWidget`
explicitly overrides `sizeMode` to `SizeMode.Exact` (`GlanceAppWidget`'s own default is
`SizeMode.Single`, which does NOT recompose on resize — an earlier draft of this widget
incorrectly assumed `Exact` was the default and shipped without the override, meaning it
never actually responded to resizing; see this doc's own git history), so the layout
reads the live exact size from `LocalSize` on every resize and adapts — below a height
threshold the "following" departure row and stale/no-upcoming explanatory text are
dropped rather than clipped — with a `GlanceTheme` + the bundled
`androidx.glance.appwidget.components.Scaffold` providing a theme-aware, readable
background (`GlanceTheme.colors.widgetBackground`, proper `appWidgetBackground()`
marking, Android-12+ system corner radius) and explicit `GlanceTheme.colors.onBackground`/
`onSurfaceVariant` text colors, since `Text`'s own default color is a fixed black that
would be unreadable against a dark theme background. There is no widget configuration
screen — with the existing first-beta one-routine limit, every instance simply mirrors
whichever one routine is currently enabled. The existing notification and Android 16
Live Update behaviour is completely unchanged; disruptions remain out of scope for this
milestone.

**Widget layout ("Design 1"):** a header row shows the routine's own pinned line number
in a small rounded, colored badge (`RoutineWidgetModel.lineDesignation`/`transportMode`,
carried through by `RoutineWidgetMapper` and persisted by `RoutineWidgetPreferences`
alongside every other identity field, so the badge renders correctly in every content
state, not only once a departure has actually been fetched) followed by the destination;
a large, bold next-departure countdown sits on the left with the station → direction
line and the following departure's own smaller countdown ("Next  X min") on the right;
and a colored dot plus "Live"/"Scheduled"/"Cancelled" label sits underneath, reflecting
the next departure's own `isRealTime`/`isCancelled` flags. `LineBadgeColorMapping`
(`widget/LineBadgeColorMapping.kt`) maps Stockholm's own per-line-family colors — bold
white badge text on every color, for reliable contrast: Pendeltåg (commuter rail) lines
40/41/42X/43/43X/44/48 in pink (`#FF49A5`), Metro blue-line 10-11 in blue (`#177BC0`),
Metro red-line 13-14 in red (`#EE2D28`), Metro green-line 17-19 in green (`#51BA5B`),
and every other mode/line combination in a neutral grey — a plain, Android-independent
function (mode AND line number both matter: a bus or train sharing a metro line's own
number, e.g. a bus "14", is never colored as if it were that metro line, and vice versa),
covered by a dedicated `LineBadgeColorMappingTest` (17 JVM tests: every colour group, the
X/express-line suffix, normalization of case and whitespace, overlapping mode/number
combinations, and unmapped values). Font sizes for the badge, countdown, and secondary
text scale through four `LocalSize`-width-driven tiers (`sizeTierFor` in
`BlickRoutineWidget.kt`) rather than a single fixed size — the original "Design 1"
reference mock was captured on a tablet-sized placement, whose grid cells are physically
much larger than an ordinary phone's, so using those same absolute point sizes
unconditionally would overflow or clip badly on a realistic phone-sized placement.

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

- **Direction discovery still has a residual gap.** `LiveDeparturesDirectionOptionsSource`
  (the only implementation of `DirectionOptionsSource`) now requests SL Transport's
  `forecast` query parameter at its empirically-confirmed maximum, 1200 minutes (see
  `../docs/api-contract.md`), instead of the live-display default, so a route is
  selectable during setup as long as it runs at least once within the next ~20 hours —
  not only at the exact moment of setup, as before. `/v1/lines` and `/v1/stop-points` were
  checked directly against the real API and confirmed not to provide a static
  site/direction association that could close the gap entirely without a Journey
  Planner/GTFS dependency (intentionally out of scope for this scaffold — the
  `DirectionOptionsSource` interface exists so the data source can be swapped later
  without touching Room or the UI). A route that runs less often than every ~20 hours can
  still be missed during setup.
- **Scheduling is best-effort, not exact.** `scheduling/WorkManagerRoutineScheduler` uses a
  plain `OneTimeWorkRequest` with a computed initial delay, not `AlarmManager`'s exact-alarm
  APIs (explicitly out of scope — see this doc's project instructions), so Android may
  briefly defer the initial execution under Doze/battery-saver the same way any other
  WorkManager job can be deferred. The notification/details-screen refresh cadence is
  "about every 30 seconds," not a guaranteed exact interval, and one notification is updated
  silently in place rather than posting a new card each refresh.
- **Blick's Live Update implementation is standard and correct, but Samsung currently
  blocks it for ordinary third-party apps, with no known removal date — treat the
  prominent Now Bar experience as device/firmware-dependent, not a guaranteed feature.**
  On a real Samsung Galaxy S23 Ultra running One UI 8.5 (not a beta build), the promoted
  card only appeared after manually enabling Settings → Developer options → "Live
  notifications for all apps." Android's own docs note that "OEMs can enforce additional
  criteria for Live update eligibility," and current reporting confirms third-party Live
  Updates remain restricted on One UI 8/8.5 unless that developer option is enabled — an
  earlier claim in this project that the restriction was beta-only and would lift once
  One UI 8 shipped stable was tech-press speculation, not an official Samsung statement,
  and is contradicted by this device (already on 8.5) still requiring the flag. No
  official Samsung source for a removal date has been found; do not restate that
  prediction without one. Blick cannot bypass Samsung's own gate — there is no manifest
  flag, permission, or API call available to a third-party app that enables it. **Do not
  prompt or instruct ordinary users to enable Developer options** to work around this.
  With the flag off (the default for an ordinary user today), Blick's
  `setRequestPromotedOngoing(true)` request is simply not honored and the OS silently
  posts the plain ongoing notification fallback instead — which is exactly what was
  otherwise fully verified end to end on that same device (single notification, ~30s
  refresh, up to two departures, lock-screen visibility, survives being swiped from
  Recent Apps, disappears at window end, Stop works immediately including from the
  locked screen and correctly pauses the routine, reboot recovery, no duplicate screens
  on tap, disable/re-enable behavior). This project's own connected-device verification
  target, a Lenovo TB350FU on Android 14, also only ever shows the plain ongoing
  notification fallback, since promotion is an Android 16+ platform feature regardless of
  OEM. Use the debug notification section's promotion status line (backed by
  `notification/PromotedNotificationChecker`) to check `canPostPromotedNotifications()`
  on any device — though this has not been separately confirmed to reflect Samsung's own
  developer-option gate one way or the other. Android separately exposes a real,
  permanent per-app settings control for its own Live Update eligibility —
  `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` — which the routine details
  screen now links to, only on Android 16+, falling back to the ordinary per-app
  notification settings screen (rather than a `PackageManager` resolvability query
  beforehand) on an OEM build that omits it even there (see the Status section above),
  but that is a distinct, general Android control: it cannot enable Samsung's
  separate "Live notifications for all apps" developer option, so it would not make
  Blick's Now Bar card generally available on Samsung devices either way. `androidx.core` is
  deliberately held at 1.17.0 rather than the newest stable release for this same
  reason — see `libs.versions.toml`'s `coreKtx` entry.
- **The home-screen widget has no configuration screen and shows one shared state across
  every placed instance** — with the existing first-beta one-routine limit, there is only
  ever one routine to show, so every instance simply mirrors it (or "No active commute.");
  this would need revisiting if the one-routine limit is ever lifted.
- **Live widget updates depend on notification availability** (see the Status section
  above) — this is an accepted design tradeoff, not a bug, but worth restating here: if
  notifications are unavailable during an active window, the widget shows
  `NotificationsUnavailable` rather than live departures, since its only data source is
  the same worker loop the notification depends on.

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

**Two further work sessions after the 193-test baseline above added substantially more
JVM and instrumented source before any of it had been compiled or run — 258 JVM
`@Test` functions and 12 instrumented `@Test` functions existed in source only, with
none of the 65 JVM tests or 3 instrumented tests beyond the 193/9 baseline yet
validated.** That gap has since been closed: see "Full verification pass" below for
what running all of it for the first time actually found.

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
Three later sessions (a `BOOT_COMPLETED` receiver plus durable stale-snapshot storage;
the promoted Live Update request, its Stop action, and a pause-today device-timezone
fix; and re-checking notification availability on every active-window loop tick) added
13 further JVM tests with no further instrumented ones, reaching 271 JVM / 21
instrumented. A further session (correcting the debug Live Update status wording, the
guarded Settings deep-link, the About screen, and the `forecast`-based direction-discovery
fix) added 4 more JVM tests (`LiveDeparturesDirectionOptionsSourceTest`) and 3 more
instrumented tests (`AboutScreenTest`'s 2, plus one more in `RoutineListScreenTest` for the
new info action), reaching 275 JVM / 24 instrumented. A correction pass then fixed a real
bug the previous session had introduced — the Live Update settings row rendered (with a
dead link) on every Android version instead of only Android 16+ — by extracting the gating
decision into `shouldOfferLiveUpdateSettingsLink` and covering it with 3 new JVM tests
(`ShouldOfferLiveUpdateSettingsLinkTest`), reaching 278 JVM / 24 instrumented. A second
correction pass fixed a related silent-failure case — tapping the settings link on an
OEM build without that Settings screen did nothing, with no fallback — by extracting
`launchLiveUpdateSettings`, which now falls back to the ordinary per-app notification
settings screen, and adding 2 new JVM tests (`LaunchLiveUpdateSettingsTest`); the same
pass also added `xml/data_extraction_rules.xml` and `xml/full_backup_content.xml` (an
explicit decision that a saved routine must never transfer or restore onto a new device
by any mechanism — see their own doc comments — since `allowBackup="false"` alone
doesn't reliably disable Android 12+ device-to-device transfer on every OEM) and a
monochrome launcher-icon layer plus `android:roundIcon` for themed-icon support,
reaching 280 JVM / 24 instrumented. A further session then implemented the home-screen
widget itself (`RoutineWidgetMapper`, `RoutineWidgetUpdater`, `BlickRoutineWidget` — see
the Status section above for the full design), adding 53 further JVM `@Test` functions
(mapper state mapping, countdown recalculation, expired-departure filtering,
active/inactive reconciliation transitions, Preferences round-trip persistence, tap-intent
contents, and every routine-lifecycle call site's widget-update/reconcile wiring) with no
further instrumented tests (all widget tests are plain JVM, matching this project's
stated preference — see `libs.versions.toml`'s `robolectric` entry), reaching 333
JVM / 24 instrumented. A final re-audit session then fixed the widget lifecycle/visual
gaps described in "Widget re-audit and correction" below (Stop-action wiring, every
worker exit path, the `NotificationsUnavailable` state, responsive sizing, and the
readable theme background) and added 8 further JVM `@Test` functions with no further
instrumented ones, reaching 341 JVM / 24 instrumented. A "Design 1" visual redesign
session then added the colored line-number badge (`LineBadgeColorMapping`), the large
countdown/secondary-block/status-row layout, and the width-driven responsive size tiers
described above, adding 23 further JVM `@Test` functions (17 in
`LineBadgeColorMappingTest`, plus mapper/preferences/reconciler coverage for the new
`lineDesignation`/`transportMode` model fields) with no further instrumented ones,
reaching 364 JVM / 24 instrumented. A further session then integrated disruptions into
the Android client for the first time — see "Disruptions integration, verified end to
end on-device" below — adding 61 further JVM `@Test` functions with no further
instrumented ones, reaching 425 JVM / 24 instrumented. A further session then fixed three
reported UI issues without changing app behaviour — the adaptive launcher icon's
foreground silhouette sat too close to its safe-zone edges, the empty-routines message
looked ragged (not centered) once it wrapped to multiple lines on a narrow phone, and all
seven weekday selector chips overflowed a single row on a narrow phone (Saturday
wrapping, Sunday pushed off-screen entirely) — see "UI fixes: icon padding, centered
empty state, responsive weekday selector" below, adding 9 further instrumented `@Test`
functions with no further JVM ones, reaching 425 JVM / 33 instrumented. A further session
then fixed the notification's disruption layout and tightened disruption filtering — see
"Notification layout, dedup, and disruption card restyle" below — adding 7 further JVM
`@Test` functions (content-based dedup, plus the collapsed indicator/never-leaks-full-text
cases in `RoutineNotificationBuilderTest`) and 7 further instrumented ones
(`RoutineDetailsScreenTest`, new), reaching 432 JVM / 40 instrumented. A further session
then completed a set of widget fixes — see "Widget fixes: stale indicator, responsive
compact layout, routine name, badge contrast, reliable reconcile scheduling" below —
adding 11 further JVM `@Test` functions (`BlickRoutineWidgetTest`, new; plus
`WidgetReconcileWorkerTest`) with no further instrumented ones, reaching 443 JVM / 40
instrumented. A further session then fixed a zero-delay rescheduling regression, made
every widget operation genuinely best-effort, decoupled disruption fetch timing from
departure notifications, and corrected disruption-relevance wording — see "Zero-delay fix,
widget best-effort, decoupled disruption timing, and corrected relevance wording" below —
adding 16 further JVM `@Test` functions with no further instrumented ones, reaching
459 JVM / 40 instrumented. A further session then fixed two more real bugs found during a
broader project audit — notification re-enabling not resuming today's already-active
routine, and `WidgetReconcileWorker`'s one remaining unprotected widget call — see "Audit
follow-up: notification re-enable resumes today, WidgetReconcileWorker retries" below —
adding 6 further JVM `@Test` functions with no further instrumented ones, reaching
465 JVM / 40 instrumented. A further session then fixed five more real bugs found during
a broader project audit — editing a routine silently deleted its offline stale-departure
fallback (`RoutineDao.upsert()`'s `OnConflictStrategy.REPLACE` triggered the
`stale_snapshots` table's `ON DELETE CASCADE` on every edit, not just a real delete),
notifications disabled then re-enabled entirely while the app stayed backgrounded could
still miss the rest of the day (closed with a `ProcessLifecycleOwner`-driven reconcile on
every foreground return, not only when the Routine Details screen happened to observe the
transition itself), an already-expired fallback disruption could keep being shown across
ticks where every subsequent fetch timed out, tick spacing could drift up to
`DISRUPTIONS_FETCH_TIMEOUT_MS` beyond the intended 30-second cadence on a slow-but-
successful disruptions fetch, and the backend's SL Deviations refresh-lock release could
turn an already-successful, already-cached fetch into a 500 if the release call itself
failed — adding 2 further JVM `@Test` functions and 1 further instrumented one, reaching
467 JVM / 41 instrumented. A further session then audited the whole project for dead code
and safely removed what proved genuinely unused, verified by tracing real call sites
rather than trusting IDE "unused" hints: the older, now-superseded embedded per-departure
"site deviation" domain model (`SiteDeviation`, `SiteDeviationLineRef`,
`SiteDeviationStopPointRef`, and their DTOs/mapping — `SiteDeviationStopAreaRef` was kept,
since the standalone SL Deviations `Disruption` model reuses its exact shape) and the
`DeparturesResult`/`DeparturesResponseDto` `siteDeviations` field they fed (the backend
still returns this field — a documented, contract-visible part of `/api/v1/departures` —
but nothing on the Android side ever read it; `ignoreUnknownKeys = true` means dropping it
client-side changes nothing observable); `RoutineListViewModel.deleteRoutine()`/
`pauseForToday()`, which had no caller anywhere in the app (no swipe/button/menu affordance
on `RoutineListScreen`, no other ViewModel, nothing but their own now-removed unit tests);
the backend's `RATE_LIMITED` error code, reserved in the `ErrorCode` union but never once
thrown by any route or service; and a handful of unused derived TypeScript type aliases
(`Journey`, `LineRef`, `StopAreaRef`, `StopPointRef`, `RequestTransportMode`, `RawJourney`,
`RawLineRef`, `RawStopAreaRef`, `RawStopPointRef` — their underlying Zod schemas remained
in active use, only the `z.infer` alias itself had zero consumers). Two more candidates,
`DisruptionsResponseSchema` and `StopSearchResponseSchema`, turned out to be well-formed
contract-validating scaffolding that simply had never been wired into a test — matching the
exact fixture-validation pattern `DeparturesResponseSchema` already followed in
`contract.test.ts` — so they were wired in rather than deleted. This removed 7 now-obsolete
JVM `@Test` functions (the dead-function tests in `RoutineListViewModelTest`) and added 2
new backend contract tests, reaching 460 JVM / 41 instrumented (backend: 268). A further
session fixed a foreground-scheduling regression: `BlickApplication`'s `ON_START` observer
called `RoutineScheduleReconciler.reconcileAll()` on every single app foreground, and that
call's `ExistingWorkPolicy.REPLACE` could cancel and replace an already-`RUNNING`
`RoutineActiveWindowWorker` merely because the user opened the app — notification/widget
flicker, duplicate departures/disruptions requests, a lost in-memory disruption fallback,
and a race where the cancelled worker's own `finally` could clear content a "replacement"
worker had already posted. Replaced with a new `ForegroundNotificationRecovery`: a new
`NotificationAvailabilityStateStore` (DataStore-backed, surviving process recreation, unlike
an in-memory ViewModel field) detects a genuine unavailable-to-available transition; only
then does recovery act, and even then only for routines whose active window is open right
now AND have no worker already `RUNNING` for it (a new `RoutineScheduler.isActivationRunning`
query) — a routine outside its window, or with a worker already running, is left completely
untouched. Added 10 further JVM `@Test` functions (6 real-`WorkManager` regression tests
proving the RUNNING-work-untouched/no-reschedule-without-transition/immediate-start-on-
transition/next-day-replace-only-when-absent/future-schedule-preserved/no-clobbered-content
guarantees, plus direct coverage of the new store and scheduler query), reaching
470 JVM / 41 instrumented — see `../docs/Blick_Project_Documentation.md`'s "Validation
status" note for the full account of each.

A further session replaced `ForegroundNotificationRecovery` and its boolean
`NotificationAvailabilityStateStore` with one `@Singleton` `NotificationRecoveryCoordinator`
as the sole, `Mutex`-serialized authority for both cold-start reconciliation and
notification-availability recovery — `BlickApplication.onCreate()` no longer launches
`RoutineScheduleReconciler.reconcileAll()` independently alongside the foreground trigger, so
the two can never race into scheduling the same routine twice. The old boolean
"last-known-available" comparison (which could never durably remember an unavailable state
across process recreation if the app happened to restart before the next check) was replaced
with a durable `RecoveryPendingStateStore.recoveryPending` flag: every real detector of
"notifications are unavailable right now" (`RoutineActiveWindowWorker` before it stops,
`RoutineDetailsViewModel`'s own checks, and the coordinator's own startup/foreground checks)
marks it, and only a fully successful recovery attempt clears it — a Room/DataStore/WorkManager
failure partway through leaves it set so the next foreground/startup retries. Per-routine
content ownership (a new Room-backed `RoutineWorkOwnershipEntity`, keyed by each run's own
WorkManager id) now gates `RoutineActiveWindowWorker`'s `finally`-block cleanup: a run that has
been superseded by a replacement (e.g. editing an active routine) can no longer clear
notification/widget content the replacement already posted, while the current owner still
cleans up exactly as before. `RoutineDetailsViewModel` no longer schedules recovery itself —
it only reports an observed unavailable state through a new, narrow
`NotificationRecoveryReporter` interface. Removed 8 JVM `@Test` functions with the deleted
`ForegroundNotificationRecoveryTest`/`PreferencesNotificationAvailabilityStateStoreTest` files
and added 19 new ones (a new `NotificationRecoveryCoordinatorTest` covering serialized
concurrent foreground/startup calls, retry-after-failure, RUNNING-work-untouched, and
cancellation propagation; new persistence tests proving both the pending flag and content
ownership survive simulated process recreation; and new `RoutineActiveWindowWorkerTest`/
`RoutineDetailsViewModelTest` coverage for ownership-gated cleanup and report-only behavior),
reaching 481 JVM / 41 instrumented.

On a machine with a real JDK 17 and Android SDK (or Android Studio, which provides
both), build with:

```
cd android
./gradlew assembleDebug              # requires a local Android SDK (Android Studio, or `sdkmanager`)
./gradlew lintDebug                  # static analysis
./gradlew testDebugUnitTest          # JVM unit tests (RoutineCreateViewModelTest, RoutineDetailsViewModelTest, RoutineListViewModelTest, RoutineMappersTest, ...)
./gradlew connectedDebugAndroidTest  # instrumented: Room DAOs (RoutineDaoTest, StaleSnapshotDaoTest) + RoutineListScreenTest (Compose UI), needs a device/emulator
```

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle-wrapper.properties` with `distributionSha256Sum`) is fully committed, so a fresh
clone builds without Android Studio or a pre-existing local Gradle install.

### Full verification pass

A complete local run — `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and
`connectedDebugAndroidTest`, most recently on the physical Lenovo TB350FU (Android 14) and
a Samsung Galaxy S23 Ultra (`SM-S918B`, Android 16) connected simultaneously — has since
been completed, using Android Studio's own bundled JDK. All 470 JVM `@Test` functions and
all 41 instrumented `@Test` functions pass on both devices; `lintDebug` reports 0 errors
(44 warnings: two expected, already-guarded `InlinedApi` findings — the API-36
`ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` deep-link and the API-33
`POST_NOTIFICATIONS` permission constant — plus four expected `UnusedAttribute` findings
on `res/xml/blick_routine_widget_info.xml`'s Android-12+-only sizing attributes, present
alongside their legacy fallbacks specifically so both old and new launchers get correct
resize behaviour, exactly like Google's own official widget-provider XML example; the one
new warning since the prior 43-warning count is an expected `GradleDependency` finding for
the newly-added `androidx.lifecycle:lifecycle-process` dependency, pinned to the same
`lifecycle` version as every other `androidx.lifecycle` artifact already in this project);
and the debug APK builds, installs, and launches without crashing on both devices. The six
new `ForegroundNotificationRecovery` regression tests are themselves Robolectric JVM tests
against a real, in-memory `WorkManager` instance (see `ForegroundNotificationRecoveryTest`'s
own doc), so they ran as part of the 470 JVM figure above regardless of device
availability.

**Notification-recovery coordination and worker-cleanup ownership fix, verified.** A
further session replaced `ForegroundNotificationRecovery`/`NotificationAvailabilityStateStore`
with the `NotificationRecoveryCoordinator`/`RecoveryPendingStateStore`/content-ownership
design described earlier in this document, and re-ran the full local suite —
`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` (a new addition to this
verification pass; the release build config has no signing config configured, so this
produces an unsigned APK, which is expected), and `connectedDebugAndroidTest`. Only the
physical Lenovo TB350FU (Android 14) was connected during this session — the Samsung Galaxy
S23 Ultra used for the run above was not available, so the instrumented suite ran on that
one device only. All 481 JVM `@Test` functions and all 41 instrumented `@Test` functions
pass; `lintDebug` still reports 0 errors and the same 44 warnings (none of the files touched
by this fix introduced any new finding); `assembleDebug` and `assembleRelease` both
succeeded. The new coordinator/ownership regression tests are themselves JVM-side Robolectric
tests (several against a real, in-memory `WorkManager` instance, and two against a real,
file-backed Room database/DataStore proving durable state survives simulated process
recreation), so they ran as part of the 481 JVM figure above regardless of device
availability. One known, narrow race remains and is documented rather than papered over:
`RoutineScheduler.isActivationRunning` and any subsequent `scheduleActivation` call are not
atomic, since WorkManager exposes no atomic "replace-unless-running" primitive — see
`NotificationRecoveryCoordinator`'s own class doc for the full reasoning.

A follow-up bug-hunting pass (no architecture changes, per explicit instruction) over the
same coordinator found one genuine, reachable race: `reportUnavailable()` — called from
`RoutineActiveWindowWorker`/`RoutineDetailsViewModel`, entirely separate coroutines that
never otherwise touch `NotificationRecoveryCoordinator` — wrote to `RecoveryPendingStateStore`
without acquiring the coordinator's own `Mutex`. A worker discovering unavailability while
`attemptPendingRecoveryIfNeeded` was already mid-attempt (for a different routine) could have
its `markRecoveryPending()` write silently overwritten by that in-progress attempt's own
unconditional `clearRecoveryPending()` at the end — losing a genuinely fresh "recovery is
owed" signal. Fixed by having `reportUnavailable()` acquire the same `Mutex` (the one
already-locked internal caller, `recordCurrentAvailabilityIfUnavailable`, now calls a
private, non-locking equivalent directly, to avoid self-deadlocking on Kotlin's
non-reentrant `Mutex`). Added one regression test proving a report arriving mid-attempt now
survives that attempt's own clear — confirmed to fail against the pre-fix code before being
verified against the fix. The rest of the notification-recovery/ownership code was audited
for further bugs and dead code; none were found. Reaching 482 JVM / 41 instrumented.

The 41 instrumented tests were then also re-run on the Samsung Galaxy S23 Ultra
(`SM-S918B`, Android 16), once it was reconnected — all 41 pass there too (0 skipped, 0
failed), confirming this fix's instrumented coverage on both physical devices.

**Shared line-number badge, extended app-wide.** The colored, rounded line-number badge
previously only rendered by the home-screen widget (`BlickRoutineWidget`'s own
Glance-based `LineBadge`) is now reused everywhere else a line number is shown: route
selection (`RoutineCreateScreen`'s direction step), the routine list, Routine Details'
own line-detail row, and each departure row. A new standard Jetpack Compose composable,
`se.blick.app.ui.components.LineBadge`, renders it outside the widget — Glance composables
cannot be called from standard Compose, so this is a second renderer, but it draws from
the exact same `LineBadgeColorMapping`/color values as the widget's own badge (moved into
`LineBadgeColorMapping.kt`, `internal`, so both renderers share one source of truth rather
than duplicating the literal SL line-family colors). Widget rendering and all
departure-preparation logic are unchanged — only new UI call sites were added, plus one
new `transportMode` parameter threaded through `RoutineDetailsScreen`'s existing
`DeparturesSection`/`DeparturesList`/`DepartureRow` chain so departure rows can resolve
their own badge color without changing `PreparedDeparture` itself. `RoutineCreateScreen`'s
private `DirectionStep` was bumped to `internal` for direct testability, the same
convention `WeekdaySelector`/`RoutineDetailsContent` already use. Added 15 further
instrumented `@Test` functions (a new `LineBadgeTest` covering the shared composable
itself; a new `DirectionStepTest`; and new coverage in `RoutineListScreenTest`/
`RoutineDetailsScreenTest`) with no further JVM ones (colors themselves are already
exhaustively covered by the existing, unaffected `LineBadgeColorMappingTest`), reaching
482 JVM / 56 instrumented — all passing on the Samsung Galaxy S23 Ultra; `lintDebug` still
reports 0 errors and the same 44 warnings, and `assembleDebug` succeeded. Manually
verified end to end on that same device: created a live routine against the real deployed
backend and confirmed the badge renders correctly (green line 17/18/19, red 13/14, red 14
after saving) on the direction-selection list, the routine list, the Line detail row, and
the departure row showing real live SL data — then deleted the test routine to leave the
device clean.

**Disruptions integration, verified end to end on-device (with one real-data caveat):**
the previously-built-but-unwired `DisruptionRepository`/`RemoteDisruptionRepository`
(see "Not yet implemented" in `../docs/Blick_Project_Documentation.md`'s prior revision)
is now called from a new shared `DisruptionCache` (60-second TTL, in-flight
de-duplication so `RoutineActiveWindowWorker` and the Routine Details screen's own
30-second auto-refresh never trigger more than one upstream-bound fetch per filter per
minute between them), a new `GetDisruptionsUseCase`/`DisruptionsState`, a new "Disruptions"
section on the Routine Details screen, and the ongoing notification's expanded view (the
highest-priority disruption's header/details, appended after the existing departure/
last-checked lines, never touching the collapsed countdown, departure text, Stop action,
or Live Update request). Verified against the real deployed backend
(`https://blick-backend.vercel.app/`) on the same physical device: created a live routine
(T-Centralen, line 18 Metro → Hökarängen), confirmed the Disruptions section correctly
read real SL data ("No disruptions right now" — genuinely true for that line at
verification time) and the notification's expanded view correctly added nothing when no
disruption existed; then disabled Wi-Fi and mobile data (`adb shell svc wifi disable` /
`svc data disable`) and let the cache's 60-second TTL elapse before refreshing again, to
force a real fetch failure rather than merely serving a still-fresh cached value — the
Disruptions section correctly flipped to "Couldn't load disruptions right now" while the
departures section *independently* fell back to its own Stale state at the same time,
confirming failure isolation between the two live, not only via fakes in the automated
suite; the notification, refreshed at that point, again correctly showed no disruption
line. Connectivity was then restored, both sections recovered on the next fetch, and the
test routine and its notification were removed to leave the device clean. **One state was
not observed with real data, stated plainly rather than glossed over:** no live SL
Deviations disruption was active on the tested line during verification, so the
"a disruption is actually present" rendering path — the Disruptions section's own list
(header, details, priority order), and the notification's appended lines — was exercised
only through the automated suite's explicit fixtures (`DisruptionTest`,
`GetDisruptionsUseCaseTest`, `RoutineDetailsViewModelTest`,
`RoutineNotificationMapperTest`, `RoutineNotificationBuilderTest`), not by direct visual
confirmation of a real disruption rendering on-screen.

**UI fixes: icon padding, centered empty state, responsive weekday selector — verified
end to end on-device:** three reported UI issues, fixed without changing any app
behaviour:

- **Launcher icon margins.** `ic_launcher_foreground`'s five per-density PNGs (no vector
  source exists to reuse — see `ic_launcher_monochrome.xml`'s own doc) render their content
  at 83% of its original footprint and re-center it on the same canvas (a plain
  resize-and-recenter, so colours and relative proportions are unchanged, just smaller and
  better-padded);
  `ic_launcher_monochrome.xml`'s vector paths got the equivalent transform via a `<group
  android:scaleX="0.83" android:scaleY="0.83" android:pivotX="54" android:pivotY="54">`
  wrapper, kept in sync with the same factor by hand since the two layers have no shared
  source. Verified on the physical device's own app-drawer icon (this launcher's squircle
  mask) after reinstalling, and separately by compositing the same updated background/
  foreground assets under both a circular mask and a rounded-square mask in a throwaway
  script — both previews show the silhouette centered with visibly more padding on every
  side than before, never touching either mask's edge.
- **Empty-routines message centering.** `RoutineListContent`'s empty state already
  centered the whole text block via its `Box`'s `contentAlignment = Alignment.Center`, but
  once the message wrapped to multiple lines on a narrow phone, the wrapped lines
  themselves defaulted to start-aligned text within that (already-centered) block, looking
  ragged. Fixed with `textAlign = TextAlign.Center` plus `Modifier.fillMaxWidth()` and
  horizontal padding (so line length stays reasonable on a wide tablet too). Verified live
  on the physical device by temporarily forcing its display to phone-narrow resolutions
  (`adb shell wm size 480x800`, then `360x640`) — at both, the message visibly wrapped to
  two lines with the second line correctly centered under the first, not flush left; `wm
  size reset` restored the device's native resolution afterward.
- **Weekday selector overflow.** The seven day chips were a single, unwrapped `Row` that
  overflowed a narrow phone's width (Saturday wrapping to a clipped second line, Sunday
  pushed off-screen entirely — exactly what the bug report described). Replaced with
  `WeekdaySelector`: a `BoxWithConstraints` that renders one full-width row when the
  measured available width is at least 400dp (comfortably true for tablets and landscape
  phones), or two balanced rows (Monday–Thursday, then Friday–Sunday) below that — each
  row gives every one of its chips an equal `Modifier.weight(1f)` share, which is what
  makes both the seven-chip and the four/three-chip split "balanced," keeps each day's
  short label on one line (`maxLines = 1`) at every supported width, and keeps every
  chip's touch target comfortably above Material's 48dp minimum even in the narrower
  four-chip row. Verified live on the physical device at the same forced phone-narrow
  resolutions: at ~480x800, all seven days rendered fully visible in one row (matching the
  balanced-single-row path — evidently still above the 400dp threshold at this device's
  density); the two-row split itself was additionally confirmed deterministically via the
  new instrumented `RoutineCreateScreenTest`, which forces an exact 320dp width regardless
  of the real device (`Modifier.requiredWidth`) and asserts Monday–Thursday share one row's
  vertical position while Friday–Sunday share a different, lower one — run and passing on
  this same physical device, not just in principle.

Adds 9 new instrumented `@Test` functions (`RoutineCreateScreenTest`, plus new cases in
`RoutineListScreenTest`) covering narrow-phone and tablet widths for the centering and
weekday-row fixes; no JVM-level behaviour changed, so the JVM suite stays at 425.

**Cross-device re-run surfaced (and fixed) a real test-only bug, not a production one:**
once a physical Samsung Galaxy S23 Ultra (`SM-S918B`, One UI) was connected, running
`connectedDebugAndroidTest` on it exposed that both "wide/tablet" cases had used
`Modifier.requiredWidth(900.dp)` — deterministic on the Lenovo tablet used above, but on
this phone (whose own portrait content width, `1080px / 450dpi × 160 ≈ 384dp`, is itself
*narrower* than 900dp and, as it turned out, than `WeekdaySelector`'s own
`WEEKDAY_SINGLE_ROW_MIN_WIDTH` threshold too) forcing an un-renderable 900dp width pushed
content beyond the device's actual viewport. The visibility assertion was changed to
check layout (existence + non-zero
measured size) instead of physical on-screen visibility, which is what a forced,
device-independent width should be judged against; the row-position assertions were
already layout-only and needed no change. Re-run on the S23 Ultra: all 33 instrumented
tests pass. The empty-message centering fix was also confirmed directly on this phone in
its native portrait orientation (not a forced resolution) — the message visibly wrapped
to two lines with the second centered under the first — and the weekday selector was
confirmed the same way: at this phone's own native ~384dp width, all seven days rendered
in two balanced rows (Monday–Thursday, Friday–Sunday), exactly the two-row branch the
original bug report was about, on the exact class of device (a current flagship phone in
portrait) most likely to hit it. The launcher icon's extra padding was also confirmed on
this device's own app-drawer icon, rendered under One UI's own squircle mask — a second,
independent real-world mask shape beyond the Lenovo tablet's.

**Notification layout, dedup, and disruption card restyle:** `RoutineNotificationBuilder`
now appends a fixed, translation-safe "Disruptions…" indicator line to the collapsed
`contentText` (after a blank spacer line, and always after the state's own departure/status
text) whenever a relevant disruption exists, across all six content states — the
disruption's own header/details are still only ever rendered in the expanded `BigTextStyle`
body, never in the collapsed indicator, so the platform constraint described above (a
promoted-ongoing notification cannot show custom content while collapsed) still holds; the
one new collapsed-view change is the fixed indicator text itself, never the disruption's
actual wording. `relevantDisruptions` gained a second de-duplication pass, applied after the
existing `disruptionId`+version step and the priority sort: SL Deviations can publish the
identical rider-facing text as separate deviation cases scoped to different, overlapping
stop-area/line combinations, which the `disruptionId`-based step alone does not catch since
each case has its own id; entries are now also collapsed by identical
`(header, details)` text, keeping whichever duplicate has the higher priority. On the
Routine Details screen, the "Disruptions" heading and section are now skipped entirely once
`DisruptionsState.NoDisruptions` is confirmed (Loading and Unavailable still render, since
neither means "confirmed nothing relevant"), and each disruption renders as a card using
`MaterialTheme.colorScheme.errorContainer`/`onErrorContainer` — Material3's own low-opacity,
theme-derived red-tint role, already tuned for readable contrast in both light and dark mode
without a hand-picked alpha over the brighter `error` red used for genuine failure states
elsewhere — collapsed by default to the disruption's header only, with a
`KeyboardArrowDown`/`KeyboardArrowUp` icon button (content-described, not color-only)
revealing the details below it, mirroring the notification's own collapsed-header/
expanded-details split. Journey-segment/direction-specific filtering (beyond the existing
site + line + transport-mode scoping the backend already applies) was investigated and
found not achievable with the data upstream (SL Deviations) actually provides: neither
`RawDeviationSchema` nor the normalized `Disruption` model carries a direction or stop-
sequence field to filter by, and `CommuteRoutine` itself only has a free-text
`destinationLabel`, not a structured destination stop id — recorded here rather than
papering over it with unverified logic. Adds 7 further JVM `@Test` functions
(`DisruptionTest`'s new content-dedup cases, plus `RoutineNotificationBuilderTest`'s
collapsed-indicator/never-leaks-full-text cases replacing the one test whose asserted
behaviour this milestone deliberately changed) and a new instrumented
`RoutineDetailsScreenTest` (7 `@Test` functions) covering the hidden-when-none-relevant
section, the collapsed/expanded card content, and the expand/collapse toggle — run and
passing on the same physical Galaxy S23 Ultra.

**Widget fixes: stale indicator, responsive compact layout, routine name, badge contrast,
reliable reconcile scheduling:** `BlickRoutineWidget` now shows a short, fixed "Stale"
marker as part of the header row (`StaleIndicator`) whenever `RoutineWidgetContent.Stale`
is the current content — the header renders identically in every layout this widget can
produce, including the compact (header + countdown only) one and the case where every
stale departure has since expired to `null`, so this is the one place a stale-data warning
is now guaranteed visible rather than only appearing in the fuller body-text sentence the
non-compact, still-has-a-departure case already showed. The compact/full layout decision
(`isCompactLayout`, pulled out as its own pure, unit-tested function) now checks BOTH
`LocalSize`'s width and height — previously height alone, which left a narrow-but-tall
single-column placement rendering the full (not compact) layout despite having too little
width for the un-weighted secondary station/direction block beside the countdown. The
routine's own user-given name (already computed by `RoutineWidgetMapper`/persisted by
`RoutineWidgetPreferences`, but never actually rendered) is now shown as a small label
above the header in non-compact layouts. The line badge's pink/red/green family colors
were darkened (hue preserved, each channel scaled toward black) after computing their real
WCAG contrast against the badge's white text: the original values measured 3.11/4.17/2.46
against the 4.5:1 AA minimum for normal-size text — green badly so — while blue (4.54) and
grey (4.83) already passed narrowly enough to get a small safety-margin nudge too; exact
contrast ratios for all five are now asserted directly in `BlickRoutineWidgetTest`.
`BlickRoutineWidgetReceiver.onUpdate` no longer launches a raw, untracked
`CoroutineScope(...).launch { }` for its self-correcting `reconcile()` call — a process
kill in the moments after `onUpdate` returns could previously drop that coroutine
silently, with no retry — it now enqueues a small `@HiltWorker` (`WidgetReconcileWorker`,
unique work, `ExistingWorkPolicy.REPLACE`) via WorkManager instead, the same
persists-across-process-death guarantee every other scheduled unit of work in this app
already gets. The widget receiver's manifest entry was changed from `exported="true"` to
`exported="false"`, correcting an earlier assumption that `exported="true"` was required
for launcher-delivered `APPWIDGET_UPDATE` broadcasts: checked directly against AOSP's own
`frameworks/base` sources, `android.appwidget.action.APPWIDGET_UPDATE` is NOT on the
platform's protected-broadcast allowlist, but `AppWidgetServiceImpl` always delivers it via
an explicit `intent.setComponent(...)` broadcast from `system_server` — the platform's
`exported` check gates other apps targeting a component, not the system's own
explicitly-addressed delivery — and the widget picker/launcher never queries this receiver
directly either (`AppWidgetManager.getInstalledProviders()` is a Binder call into
`system_server`, which does its own unrestricted lookup). See the manifest's own comment
for the full citation trail. `blick_routine_widget_info.xml`'s `minResizeWidth`/
`minResizeHeight` were raised (120dp→160dp, 60dp→80dp) after checking
`androidx.glance.appwidget.components.Scaffold`'s real source: it applies only 12dp of
*horizontal* padding (no vertical padding at all), which left the old declared minimums
with too little real margin above the compact layout's own content once accessibility
font-scaling is accounted for — `minWidth`/`minHeight` (the default, non-resized placement
size) were already comfortably above this floor and left unchanged. **Not fully confirmed
by an on-device screenshot, stated plainly:** the connected device for this session's
verification (a Lenovo TB350FU) runs the OEM `com.tblenovo.launcher.TabUILauncher`, which
— as already noted in the "Widget re-audit" entry below from an earlier session — does not
support scripted widget placement or resizing via `adb shell input`, only physical by-hand
interaction; the `minResizeWidth`/`minResizeHeight` values above are grounded in
`Scaffold`'s real source and this layout's own font-size constants, not a rendered
screenshot at those exact dimensions. Everything else in this entry — the compact/width
decision logic, the badge color contrast ratios, and the reconcile worker actually running
and calling through to `RoutineWidgetUpdater` — is covered by 11 new JVM `@Test` functions
(`BlickRoutineWidgetTest`, `WidgetReconcileWorkerTest`) and confirmed passing.

**Zero-delay fix, widget best-effort, decoupled disruption timing, and corrected relevance
wording:** four fixes, unrelated to each other except that all four were real, concretely
reproducible bugs rather than theoretical ones.

`RoutineActiveWindowWorker.rescheduleSkippingToday` always overwrites the temporary
scheduling copy's `pausedDate` with today's date now, never
`latest.pausedDate ?: today` — the old `?:` was a genuine zero-delay busy-loop bug: if a
routine already carried a STALE `pausedDate` from an earlier, unrelated "pause today" (simply
never cleared, still set to some previous day), that old date was left in place instead of
today's, so `NextOccurrenceCalculator` no longer excluded today's still-open window at all,
`WorkManagerRoutineScheduler` enqueued the very same occurrence again with a zero initial
delay, and the worker re-ran immediately into the exact same exit condition. Reproduced and
fixed with two new regression tests, each constructing a routine with yesterday's date
already sitting in `pausedDate`.

Every `RoutineWidgetUpdater` call across the worker, `RoutineScheduleReconciler`,
`StopRoutineNotificationAction`, and every routine-mutating ViewModel function
(`RoutineListViewModel.deleteRoutine`/`pauseForToday` at the time — later found to have no
real caller and removed during a dead-code audit, see below;
`RoutineDetailsViewModel.toggleEnabled`/`pauseToday`/`resumeToday`/`deleteRoutine`/`reload`;
`RoutineCreateViewModel.save`) now goes through a new shared
`runWidgetUpdateSafely { ... }` (in `widget/RoutineWidgetUpdater.kt`) that swallows any
ordinary exception — logged, not silently dropped — but always rethrows a genuine
`CancellationException` unconverted. Before this, a widget/Glance/DataStore failure could:
cut the active-window loop short and remove an already-successfully-posted notification
early (the widget call inside the loop shared the same `catch (e: Exception)` as everything
else in `doWork`); crash the whole app outright, since `viewModelScope.launch { }` and a
`BroadcastReceiver`'s own detached coroutine scope have no default exception handler on
Android (`RoutineListViewModel`'s two functions and `RoutineDetailsViewModel.reload` had no
try/catch around their widget call at all); or — the subtlest case — silently overwrite an
already-genuinely-successful create/edit/delete/enable/pause/resume action's own success
state with a "failed" one, and skip its `onSaved()`/`onDeleted()` callback, purely because
the widget call after the real mutation had already succeeded happened to throw. Verified
with 16 new JVM `@Test` functions across `RoutineActiveWindowWorkerTest`,
`RoutineDetailsViewModelTest`, `RoutineListViewModelTest`, `RoutineCreateViewModelTest`,
`StopRoutineNotificationActionTest`, and `RoutineScheduleReconcilerTest`, each injecting a
widget updater fake that throws from every method and asserting the real operation still
completes, still reports success, and (for delete/save) still invokes its callback.

The active-window loop's departures fetch and notification post no longer wait on
disruptions at all — previously, despite a doc comment claiming otherwise ("adds no more
than max(departures, disruptions), never their sum"), the loop actually awaited BOTH the
disruptions `async` and the departures fetch before posting anything, so a slow SL
Deviations request delayed the departures notification by exactly its own latency, sum or
not. Departures are now fetched and posted alone, first; disruptions are fetched only
afterward, bounded by a 5-second `DISRUPTIONS_FETCH_TIMEOUT_MS` via `withTimeoutOrNull`
(`RoutineActiveWindowWorker.kt`), and a timed-out or failed fetch falls back to whichever
disruption was last successfully confirmed (`lastKnownDisruption`, carried across loop
ticks) rather than dropping to "none shown" for one tick — a confirmed `NoDisruptions`
result, by contrast, does clear it, since that's a genuine positive confirmation, not an
absence of information. If the disruption fetched this tick differs from what the
departures notification was already posted with, a second, silent `showOrUpdate` call
folds it in — "update the notification afterward if needed," not an unconditional second
post every tick. Verified with a new test using a disruption repository that never resolves
within 100× the timeout, proving departures still post normally and on schedule regardless.

The Routine Details "Disruptions" heading was changed from the bare word "Disruptions" to
"Disruptions related to this station and line" — matching, precisely, is by station + line
+ transport mode only (`deviationsFilter.ts`, `Disruption.kt`), never direction, since SL
Deviations provides no direction or stop-sequence field to match against; the previous
heading (and, more concretely, this project's own product-principles doc, which flatly
claimed direction-based filtering existed) overstated what the matching actually
guarantees. `docs/Blick_Project_Documentation.md`'s "Relevant" product principle was
corrected to state the same real constraint rather than the previous, incorrect claim.

**Not run this session, stated plainly:** an on-device screenshot confirming the new
heading's exact rendered wording — the JVM/instrumented suites and the wording change
itself were verified; nothing about this specific change required a live device beyond the
already-passing `RoutineDetailsScreenTest`, which reads the string resource dynamically
rather than asserting a hardcoded literal, so it exercises the new text automatically.

**Widget re-audit and correction, since verified end to end on-device:** an earlier pass
of this widget shipped with several real lifecycle/visual gaps — `StopRoutineNotificationAction`
never touched the widget, several `RoutineActiveWindowWorker` exit paths (missing/deleted/
disabled routine, an elapsed window, notifications unavailable at startup, and a failure
before foreground entry) left it stale, notifications-unavailable-while-active left it on
`Loading` forever instead of saying so honestly, `GlanceAppWidget`'s actual default
`SizeMode.Single` (not `Exact`, as an earlier draft incorrectly assumed) meant it never
responded to resizing at all, and its default un-themed text color would have been
unreadable against a themed background. All of these were fixed (see the Status section
above for the design of each) and covered by 8 further JVM `@Test` functions (341 JVM /
24 instrumented total). Because this device's OEM launcher (Lenovo's `TabUILauncher`) does
not respond to scripted `adb shell input draganddrop`/resize-handle gestures, placement
and resizing were performed by hand on the physical device rather than scripted, with
`adb`-driven screenshots and `dumpsys appwidget` confirming each step: the widget placed
successfully via the launcher's own widget picker; a freshly-placed instance immediately
rendered live departure data (the `BlickRoutineWidgetReceiver.onUpdate` → `reconcile()`
fix, rather than defaulting to "No active commute." until the next lifecycle event); its
countdown visibly ticked down in step with the existing ~30-second worker loop across
repeated screenshots; it was resized (confirmed both by the launcher's visible resize
handles and directly by the person performing the resize) without any clipping, readable
throughout against its themed `Scaffold` background; and tapping the notification's Stop
action both removed the notification and reconciled the widget to "No active commute."
immediately. The one sub-case not reachable on this specific device is deliberately
noted rather than glossed over: this launcher's minimum resize grid step never went below
roughly 145dp in testing, comfortably above the 110dp height threshold below which the
widget drops its "following" departure row — so that specific compact-layout branch was
exercised by reasoning about the measured widget height against the threshold, not by
direct visual observation of the row disappearing; it remains covered in principle by the
same `SizeMode.Exact`/`LocalSize` mechanism confirmed working for every other resize
observed.

**"Design 1" visual redesign, also verified end to end on-device:** the colored
line-number badge, big-countdown layout, and responsive size tiers described in the
Status section above were confirmed on the same physical device and the same
already-placed widget instance — reinstalling the debug APK over an existing install
un-bound the previously-placed widget on this launcher (its host `dumpsys appwidget`
entry disappeared even though the app's own data/package were preserved), so placement
was repeated by hand exactly as before. With the routine's own window edited to cover
the current time, the widget immediately rendered: a red "14" badge (Metro red-line 13-14,
confirming `LineBadgeColorMapping` picks the right family for a real routine's own line
and mode) next to the destination, a large bold countdown, the station → direction line
with the following departure's own smaller countdown beside it, and a green dot with
"Live" underneath — matching the reference mock. Shrinking the widget to this launcher's
same ~145dp minimum height (see above) kept every element legible with no clipping,
confirming the new layout is at least as robust as the previous one at every size this
launcher's grid actually reaches.

Actually compiling and running the 65 JVM / 3 instrumented tests that had only existed
as source (see above) surfaced three genuine issues, all now fixed:

- `RoutineListScreenTest` imported `assertExists`/`assertDoesNotExist` as top-level
  functions; in the Compose UI testing version this project pins, both are member
  methods on `SemanticsNodeInteraction` instead, so the imports themselves were invalid.
- `POST_NOTIFICATIONS` (API 33+) is a dangerous runtime permission — declaring it in the
  manifest does not grant it, matching real first-install device behavior, so
  Robolectric denies it by default. Most of `RoutineNotificationBuilderTest`'s
  Robolectric suites assumed it was already granted; only the two tests specifically
  about the permission itself accounted for the default-denied state. Fixed with a
  default grant in `@Before`, leaving those two tests free to still exercise the
  missing-permission path explicitly.
- `WorkManagerRoutineSchedulerTest`'s shared fake "now" coincided exactly with the
  default test routine's start time — which `WorkManagerRoutineScheduler` correctly
  treats as "already active" (`Duration.ZERO` delay), running the worker immediately
  and synchronously under that test class's `SynchronousExecutor` harness, where it
  then failed with no Hilt component present. Separately, three timing assertions
  compared `WorkInfo.nextScheduleTimeMillis` (WorkManager's own real-wall-clock enqueue
  time plus the requested delay) against an absolute instant computed from a fake,
  fixed-in-the-past `Clock` — structurally never equal. Both fixed; see this file's own
  git history for the exact commits.

That same verification pass also caught a real production bug no amount of source
review had: enabling a routine never produced the automatic background notification,
silently, regardless of settings. `RoutineActiveWindowWorker` is a `@HiltWorker`, and
WorkManager was falling back to its plain reflection-based `WorkerFactory` and crashing
on the worker's real (dependency-injected) constructor every single time it was due to
run — with no UI attached to a background worker to ever surface that crash. Root
cause: `@HiltWorker`'s own dependency-binding codegen requires the
`androidx.hilt:hilt-compiler` annotation processor specifically, a separate Maven
artifact from `com.google.dagger:hilt-android-compiler` (already applied here for
`@HiltViewModel`) despite the near-identical name — and it had never been added
alongside `hilt-work`. Fixed by declaring it as an additional `ksp(...)` processor in
`app/build.gradle.kts` (see `gradle/libs.versions.toml`'s `androidx-hilt-compiler` entry
for the full account, including how the missing generated binding was confirmed).

`.github/workflows/android-ci.yml` runs `assembleDebug`/`lintDebug`/`testDebugUnitTest`
on a real JDK 17 + Android SDK runner on every push. Its runs have caught three further
real issues, exactly what that workflow is there to catch:

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

**Widget layout redesign to match the reference mock, plus a bottom disruption strip —
verified end to end on-device, including responsive resizing this time:** a reported bug
("widget's text is totally misaligned" on a real Samsung phone, with a screenshot showing
the routine name duplicated above the header and everything crammed against the top edge)
traced back to `Scaffold`'s own signature (confirmed via decompiling the Glance AAR's
`-api.jar`, since its source isn't vendored): it applies only horizontal padding, none
vertical, so `ActiveRoutineContent`'s content had zero top/bottom margin, and the routine
name was rendered twice — once by a now-removed explicit `Text`, once inside the header
row that already showed it. `ActiveRoutineContent` no longer uses the shared `Scaffold` at
all (`NoActiveCommuteContent` still does, unaffected) — it hand-builds the same chrome
(`GlanceModifier.appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
.cornerRadius(16.dp)`) around its own padded content column, specifically so a new bottom
disruption strip can sit as an unpadded sibling and reach the widget's left/right/bottom
edges directly — a full-bleed strip is not reachable from inside `Scaffold`'s single, uniformly-
padded content slot. `DepartureMainContent` changed from a side-by-side `Row` to a left-aligned
vertical `Column` (badge + destination, then the large countdown, then the route, then the
next departure, then Live/Scheduled/Cancelled status), with explicit `Spacer`s between each
line so the content actually fills the widget instead of clustering at the top, matching the
reference mock's proportions.

The disruption strip reuses data the worker already fetches — never a second, independent
fetch — mirroring the notification's own existing disruption-in-expanded-view pattern:
`RoutineWidgetModel` gained a `disruptionHeadline: String?` field, `RoutineWidgetMapper.map`
gained a `topDisruption: Disruption?` parameter (`disruptionHeadline = topDisruption?.message?.header`),
and `RoutineWidgetPreferences` persists it like every other identity field. `RoutineWidgetUpdater`
gained a new four-argument `updateWithDepartures` overload (routine, departures, now,
disruption) with a default body that forwards to the pre-existing three-argument one — a
new overload, not a new parameter on the existing method, specifically so every one of the
13 existing test fakes across seven unrelated files that only ever implemented the
three-argument method keep compiling and behaving exactly as before, with only
`GlanceRoutineWidgetUpdater` itself (and the worker's own `RecordingWidgetUpdater` test fake)
needing to actually handle the new argument. `RoutineActiveWindowWorker`'s loop follows the
exact same two-phase shape it already used for the notification: departures are fetched and
the widget is updated first (with `disruptionAtPost`, whatever was already known before this
tick's own disruption fetch), then disruptions are fetched (still bounded by the existing
`DISRUPTIONS_FETCH_TIMEOUT_MS`), and only if `lastKnownDisruption != disruptionAtPost` does a
second, silent widget update fold the newly-confirmed disruption in — same tick, no separate
timer or fetch. The strip itself (`DisruptionStrip`) is a plain flat `GlanceTheme.colors
.errorContainer`/`onErrorContainer` row — the same muted-red role `RoutineDetailsScreen`'s own
`DisruptionRow` already uses for the identical visual purpose — rendered only when
`disruptionHeadline` is non-null, with no hand-rounded bottom corners (Glance has no clean
asymmetric-corner API, and Android 12+ launchers already clip the whole widget to their own
rounded mask at the system level).

Verified end to end on a physical Samsung Galaxy S23 Ultra: placed the redesigned widget
fresh via the launcher's own widget picker, and it rendered exactly like the reference
mock — no duplicated text, a large bold countdown, the route and next-departure lines, a
green-dot "Live" status, and, genuinely by chance, a real live SL Deviations disruption
("3 augusti stängs en utgång vid Slussen") rendering correctly in the new strip, not merely
a synthetic fixture. Tapping the widget still opened Routine Details for the correct
routine, confirming tap behaviour is unchanged. Responsiveness was also confirmed by
actually resizing the placed widget on this launcher (which, unlike the Lenovo TB350FU used
for earlier widget-resize verification, does support scripted `adb shell input draganddrop`
resize-handle gestures) rather than only reasoning from `isCompactLayout`'s existing
boundary-value tests: shrinking it to this launcher's own minimum height kept every line
legible with no clipping or overlap, and shrinking it to a narrow single-column width
correctly collapsed it to the compact tier (badge + destination + countdown only, with the
route/next/status/disruption lines dropped rather than clipped) — both confirmed by
screenshot, not assumption. Adds 9 further JVM `@Test` functions (three each in
`RoutineWidgetMapperTest` and `RoutineWidgetPreferencesTest` for `topDisruption`/
`disruptionHeadline`, two new disruption-triggered-update cases in
`RoutineActiveWindowWorkerTest`, and a new `RoutineWidgetUpdaterTest` covering the
four-to-three-argument default-forwarding behaviour) with no further instrumented ones,
reaching 491 JVM / 56 instrumented; `lintDebug` still reports 0 errors and the same 44
warnings, and `assembleDebug` succeeded.

**About screen rewritten with a real privacy policy, given exact copy to use.**
`ui/screens/about/AboutScreen.kt` now shows, in order: the app name, a one-line tagline
(`about_tagline`), the version and build number (`about_version_label`, now taking both
`BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` rather than just the version
name), a "Data and attribution" section (the existing `attribution_text`, now also stating
that departure/disruption information may be delayed or inaccurate, the existing
Trafiklab.se link button, and a strengthened non-affiliation disclaimer), a full "Privacy
Policy" section (last-updated date, the operating entity, what Blick does and does not
collect, where routine/preference/cached-departure data is stored and how to remove it,
what the backend receives from a station search or route selection versus what the hosting
provider may separately log, a plain statement that this information is never sold or used
for advertising, a contact address for privacy questions or deletion requests, and a
standard "we may update this policy" line), and a centered copyright line at the bottom.
All wording is a set of exact strings supplied directly, added as new string resources
(`about_tagline`, `about_section_data_attribution`, `about_section_privacy_policy`,
`about_privacy_last_updated`, `about_privacy_operator`, `about_privacy_no_account`,
`about_privacy_local_storage`, `about_privacy_backend`, `about_privacy_usage`,
`about_privacy_contact`, `about_privacy_updates`, `about_copyright`) rather than composed
or paraphrased, matching this project's existing convention of sourcing user-facing/legal
text from string resources rather than inline literals. The screen's `Column` gained
`Modifier.verticalScroll(rememberScrollState())` since the added privacy-policy content is
now long enough to exceed a typical phone screen's height. This is a pure content/layout
change — no ViewModel, navigation, or non-UI behaviour was touched, and the existing
`AboutScreenTest` (which reads `attribution_text` and the back button dynamically from
string resources rather than asserting a hardcoded literal) required no changes and still
passes. **Not confirmed by an on-device screenshot this session, stated plainly:** the
physical Samsung Galaxy S23 Ultra used for this session's widget verification was no
longer connected by the time this change was made, so it is verified by
`testDebugUnitTest`/`lintDebug`/`assembleDebug` (491 JVM tests still passing, 0 errors/44
warnings, debug APK builds) and by the instrumented `AboutScreenTest` covering this exact
screen, but not by a live render.

**Ongoing notification simplified to standard `NotificationCompat` fields only, verified
end to end on-device with a genuinely live disruption.** Given an exact target format
(`"14 · Slussen → Fruängen"` title, `"3 min · Live"` / `"Next 18 min"` /
`"Disruption available · Tap for details"` collapsed body lines, the real disruption
message expanded-only, the Stop action, and no repeated route/line/destination/disruption
text anywhere), `RoutineNotificationBuilder` was rewritten around one new `title()` helper
(`R.string.notification_title_format`, `"%1$s · %2$s → %3$s"`) that is now the single
place a routine's pinned line, station, and destination render — `setSubText` (the old
line+direction summary, now redundant with the title) was removed entirely. The old
per-departure row text (`"%1$d min • %2$s • %3$s → %4$s"`, repeating the line designation
and destination on every row) was replaced by two new, much shorter formats:
`notification_departure_status_format` (`"%1$d min · %2$s"`) for the soonest departure,
and `notification_next_departure_format` (`"Next %1$d min"`) for the following one, shown
only when a second departure actually exists — a cancelled departure drops the countdown
entirely (`routine_details_departure_cancelled` alone for the primary line,
`notification_next_departure_cancelled` for the following one), matching the existing
cancellation-takes-priority convention. `notification_disruptions_indicator`
(`"Disruptions…"`) was renamed to `notification_disruption_available`
(`"Disruption available · Tap for details"`) to match the required wording exactly, and
the previous exception where Offline/Unavailable/Loading's expanded view showed *only*
the disruption (silently dropping their own status message) was fixed in passing — their
expanded view now always includes their own message alongside a real disruption, never
one or the other. `RoutineNotificationContent.Stale`'s expanded view keeps its
last-known departure line(s) (via the same `departureLines` helper every other state
uses) after its own warning text, preserving previously-available information the new
three-line collapsed budget has no room for. No mapper, worker, Stop-action, or Live
Update code changed at all — this was a pure `RoutineNotificationBuilder`/string-resource
change, so `RoutineNotificationMapperTest` and every other test file needed no updates;
`RoutineNotificationBuilderTest` itself was rewritten section by section to assert the
new title/body shape (title tests replace the old subtext tests one-for-one; new
Next-line/cancelled-Next-line tests; disruption tests updated for the renamed indicator
and the Offline/Unavailable inclusive-expanded-view fix). Verified end to end on the
physical Samsung Galaxy S23 Ultra: created a live routine (`14 → Fruängen` from Slussen)
against the real deployed backend, and the posted notification matched the required
format exactly, including a genuinely live SL Deviations disruption
("3 augusti stängs en utgång vid Slussen…") rendering as its own real message once
expanded — not a synthetic fixture. Tapping the notification's body opened Routine
Details for the correct routine; tapping Stop (its exact tap target found via
`uiautomator dump`'s `android:id/action0`, since a same-position tap after the shade had
auto-collapsed once already missed it and instead reopened Routine Details) removed the
notification immediately, confirmed via `dumpsys notification` showing zero
`pkg=se.blick.app` records afterward. A net one further JVM `@Test` function (many old
row/subtext tests replaced one-for-one by new title/Next-line/cancelled-Next-line ones,
rather than simply added alongside the old) with no further instrumented ones, reaching
492 JVM / 56 instrumented; `lintDebug` still reports 0 errors and the same 44 warnings,
and `assembleDebug` succeeded.

**Correction: a promoted-ongoing notification has no collapse state at all, so the
disruption's real text was leaking at a glance — fixed by never rendering it in the
notification in the first place.** A follow-up report on a real device (the same physical
Samsung Galaxy S23 Ultra) showed the disruption's actual header/details always fully
visible, with no expand/collapse chevron anywhere on the notification to even attempt to
collapse it. Confirmed directly rather than assumed: `uiautomator dump`'s raw view
hierarchy shows the Blick notification's row has no `android:id/expand_button` node at
all, while every ordinary (non-promoted) notification in the same shade does — consistent
with `dumpsys notification` showing `PROMOTED_ONGOING|FOREGROUND_SERVICE` in both
`flags` and `originalFlags`. This is genuine Android 16 platform behavior, not a bug in
how the collapsed/expanded content was composed: once a notification is actually promoted
to a Live Update, `NotificationCompat.BigTextStyle`'s "expanded-only" body is
unconditionally what's shown — there is no reliable "expand to reveal" gate left to hide
anything behind, which defeated the entire point of keeping the disruption's real message
out of the collapsed view. Fixed by no longer ever placing `disruptionHeadline`/
`disruptionDetails` in `BigTextStyle` at all — only the same fixed
`notification_disruption_available` indicator, identically in both the collapsed
`contentText` and the "expanded" `bigText`, so the real message can never appear in the
notification regardless of whether a given instance ends up promoted. "Tap for details"
now means exactly what it says: the real message is read by tapping the notification into
Routine Details' own Disruptions section, which already showed it in full. Re-verified on
the same device: the notification (still rendered fully "expanded", still with no
chevron, confirming the platform behavior is unchanged) now shows only
"Disruption available · Tap for details" where the real disruption text used to appear.
`RoutineNotificationModel.disruptionHeadline`/`disruptionDetails` themselves are
unchanged — `RoutineNotificationBuilder` simply stopped reading their text (only whether
`disruptionHeadline` is non-null, to decide whether to show the indicator at all).
`RoutineNotificationBuilderTest`'s disruption assertions were updated to check for the
indicator string rather than the literal "Delays on line 14" fixture text wherever they
previously asserted the real message appeared. Reaching 491 JVM `@Test` functions (one
fewer than before, from consolidating a few now-redundant assertions) with no further
instrumented ones — still 56 instrumented; `lintDebug` still reports 0 errors and the
same 44 warnings, and `assembleDebug` succeeded.

**Routine default names and rows collapsed to one line, badge doing the work text
previously duplicated.** A routine's default name (`RoutineCreateViewModel.selectDirection`'s
`suggestedName`) used to be `"{lineDesignation} → {destination}"` (e.g. `"14 → Fruängen"`),
shown as its own line in both the routine list and Routine Details, with the site name
repeated as a second, separate line right below it (`"Fruängen"`) — two lines carrying
overlapping information, and the line number spelled out as plain text even though every
one of those screens already shows it via the colored `LineBadge` right next to the name.
`suggestedName` now builds `"{chosen stop} → {destination}"` instead (e.g.
`"Slussen → Norsborg"`) — the line number is dropped from the text entirely, since the
badge already says it — matching the same "identity shown once, via the badge; the text
line covers the route" shape `RoutineNotificationBuilder`'s own title already uses.
`RoutineListContent`'s `ListItem` no longer has a `supportingContent` line for the site
name (now redundant, since the name already includes it) — badge plus one line of text
only. `RoutineDetailsScreen`'s header dropped the same now-redundant `Text(routine.siteName)`
line that would otherwise have shown the site name a second time directly under a name
that already contains it. Neither the notification nor the widget needed any change at
all — both already build their own station/direction text straight from
`routine.siteName`/`routine.destinationLabel`, never from `routine.name`, so this was a
pure display-layer change. Updated the one JVM test asserting the old suggested-name
pattern (`RoutineCreateViewModelTest`); `RoutineListScreenTest`/`RoutineDetailsScreenTest`
needed no changes, since neither ever asserted the site name as its own separately
displayed text node. `testDebugUnitTest`/`lintDebug`/`assembleDebug` all still pass (491
JVM tests, 0 errors/44 warnings, debug APK builds).

**The widget's own header extended to match, given a real-device photo as the reference.**
`WidgetHeader` showed only the destination next to the line badge (e.g. `"14 · Fruängen"`),
with a separate `"Slussen → Fruängen"` route line repeated below the countdown — the same
"identity spelled out twice" shape the routine list/Routine Details fix above had just
removed elsewhere, just not yet here. `ActiveRoutineContent`'s `routeText` now builds
`"{stationName} → {destinationLabel}"` (falling back to the station alone with no pinned
direction) and passes it to the header instead of the bare destination, and
`DepartureMainContent`'s own now-redundant copy of that same text was deleted outright —
the following departure's own "Next X min" line and the status row are unaffected, still
shown directly below the countdown exactly as before. `DepartureMainContent` no longer
takes a `RoutineWidgetModel` parameter at all, since removing the route line was its only
remaining use of it. `BlickRoutineWidgetTest` needed no changes — it only ever tested pure
functions (`isCompactLayout`, badge color/contrast math), never asserted on rendered
Glance text content. `testDebugUnitTest`/`lintDebug`/`assembleDebug` all still pass (491
JVM tests, 0 errors/44 warnings, debug APK builds); **stated plainly, this one was not
confirmed by a live on-device screenshot** — the physical device was mid-interaction
(the user actively typing into it) both times reinstall was attempted this session, and
sending further scripted input while someone is actively using their own phone was
judged not appropriate, so this rests on the code change plus the passing test suite
alone until it can be checked live.

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
