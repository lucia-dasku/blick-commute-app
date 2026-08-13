package se.blick.app.domain.usecase

import se.blick.app.domain.model.JourneyPlan
import java.time.Instant

/** Mirrors the backend's own defensive transferCount limit (see
 * backend/src/routes/journeys.ts's MAX_CHANGES) — enforced again on the Android side so a cached
 * or malformed backend response can't reintroduce a >2-change "fastest"/"alternative" journey. */
const val MAX_JOURNEY_CHANGES = 2

/**
 * True only when [departure] has not yet passed at [now] — a departure exactly equal to [now] is
 * still current; one even a millisecond earlier is not. The one shared building block every
 * "is this exact-destination journey still current" check in the app is built from (see
 * [JourneyPlan.isCurrentJourney]'s own doc for the full list of call sites), including
 * [se.blick.app.widget.BlickRoutineWidget]'s own final render-time check, which only has a bare
 * [Instant] to check (already resolved to the journey's own effective first departure by
 * [se.blick.app.widget.GlanceRoutineWidgetUpdater.updateWithJourneys] before it was ever
 * persisted), not a full [JourneyPlan].
 */
fun isDepartureCurrent(now: Instant, departure: Instant): Boolean = !departure.isBefore(now)

/**
 * The journey's effective first public-transport departure — [JourneyPlan.firstLeg]'s own
 * departure time when known, falling back to the journey's top-level [JourneyPlan.departureTime]
 * only when the first leg's own value is somehow missing. Every consumer — ranking, the worker's
 * notification projection, the widget, and the details screen — uses this single definition for
 * both eligibility and display; the top-level `departureTime` is never used on its own when a
 * first-leg departure is available (see normalizeJourney's own doc on the backend for why
 * WALK/transfer legs are excluded from "first").
 */
fun JourneyPlan.effectiveFirstDeparture(): Instant = firstLeg.departureTime ?: departureTime

/**
 * True only when this journey has not yet departed (its own [effectiveFirstDeparture] is not
 * before [now]) and its transfer count is within [MAX_JOURNEY_CHANGES]. The single source of
 * truth for "is this exact-destination journey still current" — used by
 * [GetRankedJourneysUseCase] (before ranking, against a `now` read only after its own repository
 * call returns), [se.blick.app.scheduling.RoutineActiveWindowWorker] (re-filtering the WHOLE
 * returned list again immediately after [GetRankedJourneysUseCase] itself returns, so a journey
 * that departed during that call's own network round-trip is still caught before it ever reaches
 * a notification or the widget), [se.blick.app.widget.GlanceRoutineWidgetUpdater] (defensively,
 * before ever persisting [se.blick.app.widget.RoutineWidgetContent.Journeys]), and
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsContent] (before rendering a journey
 * card). An already-departed journey — even by one millisecond — is never current; it must be
 * removed, never shown as an expired "0 min".
 */
fun JourneyPlan.isCurrentJourney(now: Instant): Boolean =
    isDepartureCurrent(now, effectiveFirstDeparture()) && transferCount <= MAX_JOURNEY_CHANGES

/** Filters to only the journeys [isCurrentJourney] considers still current at [now], preserving
 * order. */
fun List<JourneyPlan>.filterCurrentJourneys(now: Instant): List<JourneyPlan> = filter { it.isCurrentJourney(now) }
