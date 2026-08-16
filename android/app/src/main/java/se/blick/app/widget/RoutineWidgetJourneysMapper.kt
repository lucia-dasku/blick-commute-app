package se.blick.app.widget

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.usecase.effectiveFirstDeparture
import se.blick.app.domain.usecase.filterCurrentJourneys
import java.time.Instant

/**
 * Pure "what should the widget persist for this exact-destination routine's journeys" decision —
 * the widget's journeys counterpart to [RoutineWidgetMapper] (which plays the identical role for
 * [se.blick.app.domain.usecase.LiveDeparturesState]-based routines). Called from
 * [RoutineWidgetUpdater.updateWithJourneys].
 *
 * Defensively re-filters [journeys] against [now] via
 * [se.blick.app.domain.usecase.filterCurrentJourneys] even though every current caller (see
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own doc on re-filtering immediately after
 * [se.blick.app.domain.usecase.GetRankedJourneysUseCase] returns) already filters before calling
 * this — an expired journey must never be persisted as [RoutineWidgetContent.Journeys] purely
 * because some future/other caller forgot to filter first. [WidgetJourneyRow.departureTime] is
 * always the journey's own [effectiveFirstDeparture] — never the raw top-level
 * [JourneyPlan.departureTime] on its own, which does not necessarily reflect the first genuine
 * public-transport leg.
 *
 * [fetchFailed] distinguishes "the search genuinely failed" from "the search succeeded and
 * genuinely found nothing" — the same empty-vs-failure split
 * [se.blick.app.domain.usecase.LiveDeparturesState] already documents for the plain-departures
 * path (its own [se.blick.app.domain.usecase.LiveDeparturesState.NoUpcomingDepartures] vs
 * [se.blick.app.domain.usecase.LiveDeparturesState.Unavailable]). Before this parameter existed,
 * an empty [journeys] list (a perfectly normal outcome — no eligible route right now, or none
 * within the configured change limit) always fell to [RoutineWidgetContent.Unavailable], whose
 * own copy ("Couldn't load departures right now. Will try again soon.") wrongly told the user
 * something was broken and would retry, when the search had actually already completed
 * successfully and found nothing to show. Defaults to `false` (empty-but-not-failed) — the
 * common case, and the one every pre-existing test/fake exercises. */
internal fun decideJourneysWidgetState(
    routine: CommuteRoutine,
    journeys: List<JourneyPlan>,
    now: Instant,
    fetchFailed: Boolean = false,
    /** The current PRIMARY journey's own disruption presentation, already derived this same
     * worker tick from the same [journeys] this call already carries — see
     * [RoutineWidgetUpdater.updateWithJourneys]'s own doc. Only ever attached to the
     * [RoutineWidgetContent.Journeys] branch below (there is no PRIMARY to attach a disruption to
     * when [journeys] has nothing current). Its [DisruptionPresentation.headline]/`.effect`/
     * `.uncertainLineDesignations` are carried onto the model as three separate fields — see
     * [RoutineWidgetModel.disruptionHeadline]/`.disruptionEffect`/`.disruptionUncertainLineDesignations`'
     * own docs for what each one actually drives at render time. */
    disruption: DisruptionPresentation? = null,
): RoutineWidgetUiState {
    val rows = journeys.filterCurrentJourneys(now).take(2).map { journey ->
        WidgetJourneyRow(
            journey.firstLeg.lineDesignation,
            journey.firstLeg.transportMode,
            journey.effectiveFirstDeparture(),
            journey.arrivalTime,
            journey.transferCount,
            journey.firstLeg.isRealtime,
            journey.role,
            journey.legs.mapNotNull { leg -> leg.lineDesignation?.let { WidgetJourneyLegBadge(it, leg.transportMode) } },
        )
    }
    val primary = rows.firstOrNull() ?: return RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routine.id, routine.name, routine.journeyOriginName ?: routine.siteName,
            routine.journeyDestinationName,
            if (fetchFailed) RoutineWidgetContent.Unavailable else RoutineWidgetContent.NoUpcomingDepartures(now),
        ),
    )
    return RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routine.id, routine.name, routine.journeyOriginName ?: routine.siteName,
            routine.journeyDestinationName,
            RoutineWidgetContent.Journeys(primary, rows.getOrNull(1), routine.changesPreference),
            primary.lineDesignation, primary.transportMode,
            disruptionHeadline = disruption?.headline,
            disruptionUncertainLineDesignations = disruption?.uncertainLineDesignations ?: emptyList(),
            disruptionEffect = disruption?.effect,
        ),
    )
}
