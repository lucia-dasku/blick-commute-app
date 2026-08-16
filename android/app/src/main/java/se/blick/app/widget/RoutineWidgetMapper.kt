package se.blick.app.widget

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.usecase.countdownMinutes
import java.time.Instant

/**
 * Pure mapper from a routine + the live-departures engine's state + the current instant to a
 * [RoutineWidgetModel] — the widget's exact counterpart to
 * [se.blick.app.notification.RoutineNotificationMapper], reusing the same
 * [se.blick.app.domain.usecase.LiveDeparturesState] input and the same [countdownMinutes]
 * function so the widget's countdown is always recomputed against [now], never trusting
 * [PreparedDeparture.minutesRemaining] (a value captured at fetch time, which the render-time
 * countdown must never rely on — see [countdownMinutes]'s own doc). No new departure-fetching,
 * filtering, or countdown logic is introduced here.
 */
object RoutineWidgetMapper {
    private const val MAX_DEPARTURES = 2

    /**
     * [topDisruption] mirrors [se.blick.app.notification.RoutineNotificationMapper.map]'s own
     * parameter exactly — the single currently-relevant disruption presentation for this
     * routine, if any was fetched successfully this tick (see
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own doc on why departures are always
     * posted before disruptions are ever awaited). Defaults to null for every call site that has
     * no fresh disruption data in hand (reconciliation paths — see [RoutineWidgetReconciler]).
     * [topDisruption]'s [DisruptionPresentation.headline] is carried onto the model for Routine
     * Details' own full-text display, but — exactly like the notification — [BlickRoutineWidget]'s
     * own compact disruption strip renders [DisruptionPresentation.effect]'s classified label, not
     * this raw headline; see [RoutineWidgetModel.disruptionEffect]'s own doc.
     */
    fun map(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant, topDisruption: DisruptionPresentation? = null): RoutineWidgetModel =
        RoutineWidgetModel(
            routineId = routine.id,
            routineName = routine.name,
            stationName = routine.siteName,
            directionLabel = routine.destinationLabel,
            content = departuresState.toWidgetContent(now),
            lineDesignation = routine.lineDesignation,
            transportMode = routine.transportMode,
            disruptionHeadline = topDisruption?.headline,
            disruptionUncertainLineDesignations = topDisruption?.uncertainLineDesignations ?: emptyList(),
            disruptionEffect = topDisruption?.effect,
        )

    /** No [LiveDeparturesState] counterpart exists for this case — see
     * [RoutineWidgetContent.NotificationsUnavailable]'s own doc. Still routed through the same
     * routine-identity mapping as [map] so the routine name/station/direction stay visible
     * alongside the honest explanation, rather than falling back to a blank screen. */
    fun notificationsUnavailable(routine: CommuteRoutine): RoutineWidgetModel =
        RoutineWidgetModel(
            routineId = routine.id,
            routineName = routine.name,
            stationName = routine.siteName,
            directionLabel = routine.destinationLabel,
            content = RoutineWidgetContent.NotificationsUnavailable,
            lineDesignation = routine.lineDesignation,
            transportMode = routine.transportMode,
        )

    private fun LiveDeparturesState.toWidgetContent(now: Instant): RoutineWidgetContent = when (this) {
        is LiveDeparturesState.Loading -> RoutineWidgetContent.Loading
        is LiveDeparturesState.Live -> {
            val rows = snapshot.toRows(now)
            if (rows.isEmpty()) {
                RoutineWidgetContent.NoUpcomingDepartures(lastCheckedAt = snapshot.fetchedAt)
            } else {
                RoutineWidgetContent.Live(next = rows[0], following = rows.getOrNull(1))
            }
        }
        is LiveDeparturesState.Stale -> {
            val rows = snapshot.toRows(now)
            RoutineWidgetContent.Stale(next = rows.getOrNull(0), following = rows.getOrNull(1), lastCheckedAt = snapshot.fetchedAt)
        }
        is LiveDeparturesState.NoUpcomingDepartures -> RoutineWidgetContent.NoUpcomingDepartures(lastCheckedAt = fetchedAt)
        is LiveDeparturesState.Offline -> RoutineWidgetContent.Offline
        is LiveDeparturesState.Unavailable -> RoutineWidgetContent.Unavailable
    }

    /** Re-filters expired departures at RENDER time (against [now]), exactly like
     * [se.blick.app.notification.RoutineNotificationMapper]'s identical private `toRows` — a
     * departure that was still upcoming when fetched can have since departed by the time this
     * runs, and must never be shown as current. */
    private fun LiveDeparturesSnapshot.toRows(now: Instant): List<WidgetDepartureRow> =
        departures
            .asSequence()
            .filter { !it.effectiveTime.isBefore(now) }
            .sortedBy { it.effectiveTime }
            .take(MAX_DEPARTURES)
            .map { it.toWidgetRow(now) }
            .toList()

    private fun PreparedDeparture.toWidgetRow(now: Instant): WidgetDepartureRow = WidgetDepartureRow(
        lineDesignation = lineDesignation,
        destinationLabel = destination ?: direction,
        minutesRemaining = countdownMinutes(now, effectiveTime),
        isRealTime = isRealTime,
        isCancelled = isCancelled,
    )
}
