package se.blick.app.widget

import se.blick.app.domain.model.CommuteRoutine
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
 */
internal fun decideJourneysWidgetState(routine: CommuteRoutine, journeys: List<JourneyPlan>, now: Instant): RoutineWidgetUiState {
    val rows = journeys.filterCurrentJourneys(now).take(2).map { journey ->
        WidgetJourneyRow(
            journey.firstLeg.lineDesignation,
            journey.firstLeg.transportMode,
            journey.effectiveFirstDeparture(),
            journey.arrivalTime,
            journey.transferCount,
            journey.firstLeg.isRealtime,
        )
    }
    val fastest = rows.firstOrNull() ?: return RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routine.id, routine.name, routine.journeyOriginName ?: routine.siteName,
            routine.journeyDestinationName, RoutineWidgetContent.Unavailable,
        ),
    )
    return RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routine.id, routine.name, routine.journeyOriginName ?: routine.siteName,
            routine.journeyDestinationName, RoutineWidgetContent.Journeys(fastest, rows.getOrNull(1)),
            fastest.lineDesignation, fastest.transportMode,
        ),
    )
}
