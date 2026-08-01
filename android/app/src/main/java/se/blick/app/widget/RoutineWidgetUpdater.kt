package se.blick.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.scheduling.DeviceZoneProvider
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes [RoutineWidgetUiState] to every placed [BlickRoutineWidget] instance. Never fetches
 * departures, never schedules work, never runs a timer of its own — every call site already has
 * (or can cheaply derive) what it needs to call one of these three methods; see each method's
 * own doc for exactly which call sites use it.
 */
interface RoutineWidgetUpdater {
    /** Called once per [se.blick.app.scheduling.RoutineActiveWindowWorker] loop tick, right
     * after [se.blick.app.notification.RoutineNotifier.showOrUpdate] — reuses the exact
     * [routine]/[departuresState]/[now] already fetched for the notification, via
     * [RoutineWidgetMapper]. No separate fetch, no separate 30-second timer. */
    suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant)

    /** Called only from the worker's own `finally` block, mirroring
     * [se.blick.app.notification.RoutineNotifier.remove] exactly — the active window has just
     * ended (normally, on a handled failure, or because it's no longer eligible), so the widget
     * goes back to [RoutineWidgetUiState.NoActiveCommute] unconditionally, with no extra lookup. */
    suspend fun clear()

    /** Called from every routine-lifecycle mutation site that happens OUTSIDE the worker's loop
     * (create/edit save, enable/disable, pause/resume, delete) and from
     * [se.blick.app.scheduling.RoutineScheduleReconciler.reconcileAll] (covering process start,
     * device-timezone change, and reboot via [se.blick.app.scheduling.BootCompletedReceiver]) —
     * re-derives the correct widget state from scratch via [decideReconciledWidgetState], since
     * none of those call sites have fresh departure data in hand the way the worker's loop does. */
    suspend fun reconcile()
}

@Singleton
class GlanceRoutineWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routineRepository: RoutineRepository,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) : RoutineWidgetUpdater {

    override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
        applyToAllInstances(RoutineWidgetUiState.ActiveRoutine(RoutineWidgetMapper.map(routine, departuresState, now)))
    }

    override suspend fun clear() {
        applyToAllInstances(RoutineWidgetUiState.NoActiveCommute)
    }

    override suspend fun reconcile() {
        val now = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())
        val routines = routineRepository.observeAll().first()
        applyToAllInstances(decideReconciledWidgetState(routines, now))
    }

    private suspend fun applyToAllInstances(state: RoutineWidgetUiState) {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(BlickRoutineWidget::class.java)
        if (ids.isEmpty()) return
        ids.forEach { id -> updateAppWidgetState(context, id) { prefs -> state.writeInto(prefs) } }
        BlickRoutineWidget().updateAll(context)
    }
}
