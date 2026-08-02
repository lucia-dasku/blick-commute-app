package se.blick.app.widget

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.scheduling.NextOccurrence
import se.blick.app.scheduling.NextOccurrenceCalculator
import java.time.ZonedDateTime

/**
 * Pure "what should the widget show right now, outside the worker's own 30-second loop" decision
 * — reused by [RoutineWidgetUpdater.reconcile] for every trigger that isn't the loop itself
 * (routine created/edited, enabled/disabled, paused/resumed, deleted, and boot/timezone/process-
 * start reconciliation via [se.blick.app.scheduling.RoutineScheduleReconciler]). Reuses
 * [NextOccurrenceCalculator] — the exact same active-window calculation
 * [se.blick.app.scheduling.WorkManagerRoutineScheduler] and
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] already use — rather than introducing any
 * separate notion of "is a window active."
 *
 * If a window is active AND [notificationsAvailable], this only ever returns a
 * [RoutineWidgetContent.Loading] placeholder: the worker's own already-scheduled tick (its
 * `initialDelay` is [java.time.Duration.ZERO] for an [NextOccurrence.ActiveNow] occurrence, see
 * [se.blick.app.scheduling.WorkManagerRoutineScheduler]) fires within moments and immediately
 * replaces it with real departure data via [RoutineWidgetUpdater.updateWithDepartures] — this
 * function never fetches departures itself.
 *
 * If a window is active but NOT [notificationsAvailable], the worker's loop (this widget's only
 * data source — see [RoutineWidgetUpdater]) either never starts or has already stopped, so a
 * [RoutineWidgetContent.Loading] placeholder here would never self-correct; this function returns
 * [RoutineWidgetContent.NotificationsUnavailable] instead — see that case's own doc. Callers (only
 * [RoutineWidgetUpdater.reconcile]) pass whatever
 * [se.blick.app.notification.NotificationAvailabilityChecker] reports at reconcile time; this
 * function itself stays a plain boolean parameter so it remains pure and Android-independent.
 */
internal fun decideReconciledWidgetState(
    routines: List<CommuteRoutine>,
    now: ZonedDateTime,
    notificationsAvailable: Boolean = true,
): RoutineWidgetUiState {
    val routine = routines.firstOrNull { it.enabled } ?: return RoutineWidgetUiState.NoActiveCommute
    val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, now, excludedDate = routine.pausedDate)
    if (occurrence !is NextOccurrence.ActiveNow) return RoutineWidgetUiState.NoActiveCommute
    return if (notificationsAvailable) {
        RoutineWidgetUiState.ActiveRoutine(RoutineWidgetMapper.map(routine, LiveDeparturesState.Loading, now.toInstant()))
    } else {
        RoutineWidgetUiState.ActiveRoutine(RoutineWidgetMapper.notificationsUnavailable(routine))
    }
}
