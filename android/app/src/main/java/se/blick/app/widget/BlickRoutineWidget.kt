package se.blick.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import se.blick.app.R

/**
 * The home-screen widget's [GlanceAppWidget]. Deliberately holds no departure-fetching, timing,
 * or scheduling logic of its own — it only ever renders whatever [RoutineWidgetUiState] was most
 * recently persisted for this instance by [RoutineWidgetUpdater], which is itself only ever
 * driven by [se.blick.app.scheduling.RoutineActiveWindowWorker]'s existing ~30-second loop and
 * the routine-lifecycle call sites listed on [RoutineWidgetUpdater.reconcile]. There is
 * deliberately no periodic self-refresh: [BlickRoutineWidgetReceiver]'s provider XML sets
 * `android:updatePeriodMillis="0"`, so Android's own widget-update scheduler never fires here —
 * see `res/xml/blick_routine_widget_info.xml`.
 *
 * `stateDefinition = PreferencesGlanceStateDefinition` (Glance's built-in DataStore-backed
 * implementation) is the ONLY state this widget reads or writes — see
 * `RoutineWidgetPreferences.kt` for the exact keys, and [RoutineWidgetUpdater] for the only
 * writer.
 *
 * Because every update above is entirely push-driven, and is only ever pushed from
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s loop (never in response to the widget
 * itself being resized, redisplayed, or tapped), live content updates depend on the same
 * [se.blick.app.notification.NotificationAvailabilityChecker] gate that loop depends on — see
 * [RoutineWidgetContent.NotificationsUnavailable]'s own doc for exactly how that dependency is
 * represented honestly instead of leaving the widget stuck on stale content.
 */
class BlickRoutineWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    /** [GlanceAppWidget]'s own default is [SizeMode.Single] — one composition sized for the
     * provider-declared size, which never recomposes while the widget is being resized on the
     * launcher. Overridden here so the layout below reads the live exact size from [LocalSize]
     * on every resize and adapts instead of clipping or leaving dead space. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            BlickWidgetContent(prefs.toWidgetUiState())
        }
    }
}

/** [BlickRoutineWidgetReceiver.glanceAppWidget]'s counterpart in `AndroidManifest.xml` — see
 * that receiver's own `<intent-filter>`/`<meta-data>` and `res/xml/blick_routine_widget_info.xml`.
 *
 * `@AndroidEntryPoint` field-injects [RoutineWidgetUpdater] like every other class in this
 * codebase (see [se.blick.app.notification.StopRoutineNotificationReceiver] for the identical
 * pattern on a plain [android.content.BroadcastReceiver]) so [onUpdate] can call
 * [RoutineWidgetUpdater.reconcile].
 */
@AndroidEntryPoint
class BlickRoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlickRoutineWidget()

    @Inject lateinit var routineWidgetUpdater: RoutineWidgetUpdater

    /** [onUpdate] fires not only on `android:updatePeriodMillis` (disabled here, see
     * [BlickRoutineWidget]'s class doc) but also, per the platform's own [AppWidgetManager]
     * contract, exactly once whenever a NEW widget instance is placed. [super.onUpdate] alone
     * renders that fresh instance from its own (empty, never-yet-written) per-instance
     * preferences — [RoutineWidgetPreferences]'s `toWidgetUiState` defaults an empty
     * [Preferences] to [RoutineWidgetUiState.NoActiveCommute] — even if a routine's window is
     * genuinely active (or notifications are unavailable) right this moment. Calling
     * [RoutineWidgetUpdater.reconcile] here re-derives the real current state and pushes a
     * corrected render moments later, exactly like every other `reconcile()` call site — this
     * is a self-correction, not a second data source (see that method's own doc).
     *
     * Deliberately does NOT call [goAsync] itself — [GlanceAppWidgetReceiver.onUpdate] (invoked
     * via [super.onUpdate]) already calls it once for this dispatch, and calling it twice for
     * the same [android.content.BroadcastReceiver.onReceive] throws (see that class's own doc:
     * "you must not call goAsync, as it will be called by the super implementation"). This is a
     * fast, best-effort correction: if the process is killed before this plain coroutine
     * completes, the next reconcile()-triggering lifecycle event (or the worker's own next
     * tick) still self-corrects it, exactly as for every other missed reconcile(). */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            routineWidgetUpdater.reconcile()
        }
    }
}

/** Below this height, only the routine identity plus a single content line reliably fit without
 * clipping (`blick_routine_widget_info.xml`'s declared `minHeight` is 90dp) — the "following"
 * departure row, and the stale/no-upcoming explanatory line, are dropped rather than clipped or
 * left to overflow the widget's bounds. */
private val COMPACT_HEIGHT_THRESHOLD = 110.dp

@Composable
private fun BlickWidgetContent(state: RoutineWidgetUiState) {
    val context = LocalContext.current
    GlanceTheme {
        Scaffold {
            when (state) {
                RoutineWidgetUiState.NoActiveCommute -> NoActiveCommuteContent()
                is RoutineWidgetUiState.ActiveRoutine -> ActiveRoutineContent(context, state.model)
            }
        }
    }
}

@Composable
private fun NoActiveCommuteContent() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyText(context.getString(R.string.widget_no_active_commute))
    }
}

@Composable
private fun ActiveRoutineContent(context: Context, model: RoutineWidgetModel) {
    val clickAction = actionStartActivity(routineDetailsTapIntent(context, model.routineId))
    // Read once per composition: SizeMode.Exact recomposes this whole tree on every resize, so
    // every use below always reflects the widget's current on-screen size.
    val compact = LocalSize.current.height < COMPACT_HEIGHT_THRESHOLD
    Column(modifier = GlanceModifier.fillMaxSize().clickable(clickAction)) {
        Text(
            text = model.routineName,
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, color = onBackgroundColor()),
        )
        val subtitle = model.directionLabel?.let { "${model.stationName} → $it" } ?: model.stationName
        Text(text = subtitle, maxLines = 1, style = TextStyle(color = onSurfaceVariantColor()))
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            WidgetContentBody(context, model.content, compact)
        }
    }
}

@Composable
private fun WidgetContentBody(context: Context, content: RoutineWidgetContent, compact: Boolean) {
    when (content) {
        RoutineWidgetContent.Loading -> BodyText(context.getString(R.string.notification_loading))
        is RoutineWidgetContent.Live -> {
            DepartureRowText(context, content.next)
            if (!compact) content.following?.let { DepartureRowText(context, it) }
        }
        is RoutineWidgetContent.Stale -> {
            if (!compact) BodyText(context.getString(R.string.notification_stale_warning))
            content.next?.let { DepartureRowText(context, it) }
            if (!compact) content.following?.let { DepartureRowText(context, it) }
            if (content.next == null) {
                BodyText(context.getString(R.string.notification_no_departures))
            }
        }
        is RoutineWidgetContent.NoUpcomingDepartures -> BodyText(context.getString(R.string.notification_no_departures))
        RoutineWidgetContent.Offline -> BodyText(context.getString(R.string.notification_offline))
        RoutineWidgetContent.Unavailable -> BodyText(context.getString(R.string.notification_unavailable))
        RoutineWidgetContent.NotificationsUnavailable -> BodyText(context.getString(R.string.widget_notifications_unavailable))
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, maxLines = 2, style = TextStyle(color = onBackgroundColor()))
}

@Composable
private fun DepartureRowText(context: Context, row: WidgetDepartureRow) {
    val destination = row.destinationLabel ?: context.getString(R.string.direction_unknown_destination)
    val text = if (row.isCancelled) {
        context.getString(
            R.string.notification_row_cancelled_format,
            context.getString(R.string.routine_details_departure_cancelled),
            row.lineDesignation,
            destination,
        )
    } else {
        val statusText = context.getString(
            if (row.isRealTime) R.string.routine_details_departure_live else R.string.routine_details_departure_scheduled,
        )
        context.getString(R.string.notification_row_format, row.minutesRemaining, statusText, row.lineDesignation, destination)
    }
    Text(text = text, maxLines = 1, style = TextStyle(color = onBackgroundColor()))
}

// [androidx.glance.text.TextStyle]'s own default color is a fixed Color.Black (see its own
// KDoc), never theme-aware — left at that default, text would be unreadable against
// GlanceTheme.colors.widgetBackground in dark mode. Every Text above sets one of these two
// explicitly instead, which is the other half of "add a readable widget background".
@Composable
private fun onBackgroundColor(): ColorProvider = GlanceTheme.colors.onBackground

@Composable
private fun onSurfaceVariantColor(): ColorProvider = GlanceTheme.colors.onSurfaceVariant
