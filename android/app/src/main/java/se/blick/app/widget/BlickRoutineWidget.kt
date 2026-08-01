package se.blick.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
 */
class BlickRoutineWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            BlickWidgetContent(prefs.toWidgetUiState())
        }
    }
}

/** [BlickRoutineWidgetReceiver.glanceAppWidget]'s counterpart in `AndroidManifest.xml` — see
 * that receiver's own `<intent-filter>`/`<meta-data>` and `res/xml/blick_routine_widget_info.xml`. */
class BlickRoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlickRoutineWidget()
}

@Composable
private fun BlickWidgetContent(state: RoutineWidgetUiState) {
    val context = LocalContext.current
    when (state) {
        RoutineWidgetUiState.NoActiveCommute -> NoActiveCommuteContent()
        is RoutineWidgetUiState.ActiveRoutine -> ActiveRoutineContent(context, state.model)
    }
}

@Composable
private fun NoActiveCommuteContent() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = context.getString(R.string.widget_no_active_commute))
    }
}

@Composable
private fun ActiveRoutineContent(context: Context, model: RoutineWidgetModel) {
    val clickAction = actionStartActivity(routineDetailsTapIntent(context, model.routineId))
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp).clickable(clickAction),
    ) {
        Text(text = model.routineName, style = TextStyle(fontWeight = FontWeight.Bold))
        val subtitle = model.directionLabel?.let { "${model.stationName} → $it" } ?: model.stationName
        Text(text = subtitle)
        Column(modifier = GlanceModifier.padding(top = 8.dp)) {
            WidgetContentBody(context, model.content)
        }
    }
}

@Composable
private fun WidgetContentBody(context: Context, content: RoutineWidgetContent) {
    when (content) {
        RoutineWidgetContent.Loading -> Text(text = context.getString(R.string.notification_loading))
        is RoutineWidgetContent.Live -> {
            DepartureRowText(context, content.next)
            content.following?.let { DepartureRowText(context, it) }
        }
        is RoutineWidgetContent.Stale -> {
            Text(text = context.getString(R.string.notification_stale_warning))
            content.next?.let { DepartureRowText(context, it) }
            content.following?.let { DepartureRowText(context, it) }
            if (content.next == null) {
                Text(text = context.getString(R.string.notification_no_departures))
            }
        }
        is RoutineWidgetContent.NoUpcomingDepartures -> Text(text = context.getString(R.string.notification_no_departures))
        RoutineWidgetContent.Offline -> Text(text = context.getString(R.string.notification_offline))
        RoutineWidgetContent.Unavailable -> Text(text = context.getString(R.string.notification_unavailable))
    }
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
    Text(text = text)
}
