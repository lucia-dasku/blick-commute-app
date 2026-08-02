package se.blick.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
 * No Hilt entry point/field injection needed here — [onUpdate] only enqueues
 * [WidgetReconcileWorker] (itself `@HiltWorker`-injected by [se.blick.app.BlickApplication]'s
 * `HiltWorkerFactory` when WorkManager actually runs it), rather than resolving
 * [RoutineWidgetUpdater] directly on this receiver.
 */
class BlickRoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlickRoutineWidget()

    /** [onUpdate] fires not only on `android:updatePeriodMillis` (disabled here, see
     * [BlickRoutineWidget]'s class doc) but also, per the platform's own [AppWidgetManager]
     * contract, exactly once whenever a NEW widget instance is placed. [super.onUpdate] alone
     * renders that fresh instance from its own (empty, never-yet-written) per-instance
     * preferences — [RoutineWidgetPreferences]'s `toWidgetUiState` defaults an empty
     * [Preferences] to [RoutineWidgetUiState.NoActiveCommute] — even if a routine's window is
     * genuinely active (or notifications are unavailable) right this moment. Enqueuing
     * [WidgetReconcileWorker] here re-derives the real current state and pushes a corrected
     * render moments later, exactly like every other `reconcile()` call site — this is a
     * self-correction, not a second data source (see [RoutineWidgetUpdater.reconcile]'s own
     * doc).
     *
     * Enqueues via [WidgetReconcileWorker.enqueue] rather than launching a raw, untracked
     * coroutine — WorkManager persists this across process death and guarantees it eventually
     * runs, unlike a `CoroutineScope(...).launch { }` tied to nothing, which a process kill
     * moments after this method returns could silently drop entirely with no retry (see that
     * worker's own doc). Deliberately does NOT call [goAsync] — [GlanceAppWidgetReceiver.onUpdate]
     * (invoked via [super.onUpdate]) already calls it once for this dispatch, and calling it
     * twice for the same [android.content.BroadcastReceiver.onReceive] throws (see that class's
     * own doc: "you must not call goAsync, as it will be called by the super implementation") —
     * not a concern here anyway, since enqueueing work is itself synchronous and fast, with no
     * need to extend this receiver's own lifetime to wait on it. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetReconcileWorker.enqueue(context)
    }
}

/** Below this height, only the header (line badge + destination) plus the single big countdown
 * reliably fit without clipping (`blick_routine_widget_info.xml`'s declared `minHeight` is
 * 90dp) — the station/direction + "Next" secondary block and the live/scheduled/cancelled
 * status row are dropped rather than clipped or left to overflow the widget's bounds. */
private val COMPACT_HEIGHT_THRESHOLD = 110.dp

/** Below this width, the secondary station/direction + "Next" block (an un-weighted column
 * placed beside the big countdown — see [DepartureMainContent]) has too little room to render
 * its own text without wrapping into the countdown's own space or clipping — a narrow-but-tall
 * grid cell (e.g. a single-column placement) needs the same compact layout as a short-but-wide
 * one, not just smaller fonts. Matches [sizeTierFor]'s own smallest width tier boundary (see
 * that function's doc on why 220dp is "a realistic phone grid cell") so anything narrow enough
 * to already get the smallest font tier also drops to the compact layout, not just smaller text
 * within the full one. */
private val COMPACT_WIDTH_THRESHOLD = 220.dp

/** Whether [ActiveRoutineContent] should render the compact (header + countdown only) layout —
 * a pure function of the widget's live [LocalSize] so it can be unit-tested directly, without a
 * Glance/Robolectric composition. See [COMPACT_HEIGHT_THRESHOLD]/[COMPACT_WIDTH_THRESHOLD] for
 * why EITHER dimension being too small is enough to force it, not just height alone. */
internal fun isCompactLayout(width: Dp, height: Dp): Boolean =
    height < COMPACT_HEIGHT_THRESHOLD || width < COMPACT_WIDTH_THRESHOLD

/** Font sizes for one responsive breakpoint — chosen by [sizeTierFor] from the widget's live
 * [LocalSize] width on every resize (`SizeMode.Exact`, see [BlickRoutineWidget]'s own doc). The
 * "Design 1" reference mock was captured on a tablet-sized placement, whose widget grid cells
 * are physically much larger than a typical phone's — using those same absolute point sizes
 * unconditionally would overflow or clip badly on an ordinary phone-sized placement, so the
 * countdown/badge/secondary text sizes scale down through [TIER_COMPACT]/[TIER_MEDIUM] for
 * realistic phone widths and only reach the mock's own large sizes at [TIER_EXTRA_LARGE]. */
private data class WidgetSizeTier(
    val headerSize: TextUnit,
    val badgeSize: TextUnit,
    val countdownSize: TextUnit,
    val secondarySize: TextUnit,
    val statusSize: TextUnit,
)

private val TIER_COMPACT = WidgetSizeTier(headerSize = 12.sp, badgeSize = 10.sp, countdownSize = 24.sp, secondarySize = 11.sp, statusSize = 10.sp)
private val TIER_MEDIUM = WidgetSizeTier(headerSize = 13.sp, badgeSize = 11.sp, countdownSize = 32.sp, secondarySize = 12.sp, statusSize = 11.sp)
private val TIER_LARGE = WidgetSizeTier(headerSize = 15.sp, badgeSize = 13.sp, countdownSize = 44.sp, secondarySize = 14.sp, statusSize = 12.sp)
private val TIER_EXTRA_LARGE = WidgetSizeTier(headerSize = 17.sp, badgeSize = 14.sp, countdownSize = 58.sp, secondarySize = 16.sp, statusSize = 13.sp)

/** Phone home-screen widget grid cells are typically well under 220dp per placed instance;
 * beyond ~480dp is realistically only reachable on a tablet-class launcher grid (see
 * `blick_routine_widget_info.xml`'s own `minWidth`/`maxResizeWidth`, and the manual on-device
 * verification note in `android/README.md`'s Full verification pass section for a real
 * measurement of how large this got on an actual tablet). */
private fun sizeTierFor(width: Dp): WidgetSizeTier = when {
    width < 220.dp -> TIER_COMPACT
    width < 320.dp -> TIER_MEDIUM
    width < 480.dp -> TIER_LARGE
    else -> TIER_EXTRA_LARGE
}

// Darkened from SL's own brighter line-family colors specifically so white badge text stays at
// or above the WCAG AA 4.5:1 contrast minimum for normal-size text — the original, brighter
// values measured at only 3.11 (pink), 4.17 (red), and 2.46 (green) against white, all below
// 4.5, with green badly so. Blue (4.54) and grey (4.83) already passed but blue's own margin was
// razor-thin, so it got a small nudge too, for a safer margin against real-device subpixel/
// anti-aliasing variance rather than a paper-thin pass. Hue is preserved (each channel scaled by
// the same factor toward black) so the SL line-family color is still recognizably the same
// family, just deep enough to stay readable. Exact contrast ratios are asserted directly against
// these literal values in LineBadgeColorMappingTest, so a future edit here that regresses
// contrast fails a test rather than shipping unnoticed.
private val BADGE_PINK = Color(0xFFC73981)
private val BADGE_BLUE = Color(0xFF1676B8)
private val BADGE_RED = Color(0xFFDB2925)
private val BADGE_GREEN = Color(0xFF38803F)
private val BADGE_GREY = Color(0xFF6B7280)
private val BADGE_TEXT_WHITE = ColorProvider(Color.White)

internal fun LineBadgeColor.toBadgeColor(): Color = when (this) {
    LineBadgeColor.Pink -> BADGE_PINK
    LineBadgeColor.Blue -> BADGE_BLUE
    LineBadgeColor.Red -> BADGE_RED
    LineBadgeColor.Green -> BADGE_GREEN
    LineBadgeColor.Unknown -> BADGE_GREY
}

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
    val tier = sizeTierFor(LocalSize.current.width)
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyText(context.getString(R.string.widget_no_active_commute), tier)
    }
}

/** "Design 1": a routine-name label, a line badge + destination header, a large next-departure
 * countdown with a smaller station/direction + following-departure block beside it, and a
 * live/scheduled/cancelled status row — see [WidgetContentBody]/[DepartureMainContent]/
 * [StatusFooter]. */
@Composable
private fun ActiveRoutineContent(context: Context, model: RoutineWidgetModel) {
    val clickAction = actionStartActivity(routineDetailsTapIntent(context, model.routineId))
    // Read once per composition: SizeMode.Exact recomposes this whole tree on every resize, so
    // every use below always reflects the widget's current on-screen size.
    val size = LocalSize.current
    val compact = isCompactLayout(size.width, size.height)
    val tier = sizeTierFor(size.width)
    val destination = model.directionLabel ?: model.stationName
    // Only ever a Stale case's own doc for why this must be rendered as a short, ALWAYS-visible
    // header marker rather than the fuller body-text sentence WidgetContentBody's Stale branch
    // already shows in non-compact mode with a next departure -- that longer sentence is dropped
    // in compact mode, and dropped entirely once every stale departure has since expired
    // (WidgetContentBody falls back to a plain "no departures" body then), so without this
    // header-level marker a genuinely failed refresh could look identical to a healthy state in
    // either case.
    val isStale = model.content is RoutineWidgetContent.Stale
    Column(modifier = GlanceModifier.fillMaxSize().clickable(clickAction)) {
        // The routine's own user-given name -- distinct from the station/destination text the
        // header already shows -- dropped in compact mode along with the rest of the secondary
        // context (see WidgetContentBody's own compact handling) to protect the tight
        // header+countdown-only space budget that mode is built around.
        if (!compact) {
            Text(
                text = model.routineName,
                maxLines = 1,
                style = TextStyle(fontSize = tier.statusSize, color = onSurfaceVariantColor()),
            )
        }
        WidgetHeader(model, tier, destination, isStale)
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
            WidgetContentBody(context, model, compact, tier)
        }
    }
}

@Composable
private fun WidgetHeader(model: RoutineWidgetModel, tier: WidgetSizeTier, destination: String, isStale: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        model.lineDesignation?.let { line ->
            LineBadge(line, LineBadgeColorMapping.colorFor(model.transportMode, line), tier.badgeSize)
            Text(
                text = "  •  ",
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.headerSize, color = onBackgroundColor()),
            )
        }
        Text(
            text = destination,
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.headerSize, color = onBackgroundColor()),
        )
        if (isStale) {
            StaleIndicator(tier)
        }
    }
}

/** A short, fixed marker that this content is stale (the last successful refresh, not a live
 * one) — rendered as part of [WidgetHeader], which is shown identically regardless of
 * [isCompactLayout] or whether any departure is still upcoming, so this is the one place a
 * stale-data warning is GUARANTEED visible in every layout this widget can render, unlike the
 * fuller body-text sentence [WidgetContentBody]'s `Stale` branch only shows in non-compact mode
 * when a departure is still upcoming. Uses [GlanceTheme.colors.tertiary] — the same
 * "attention, not alarm" role the Routine Details screen's own stale warning uses via
 * `MaterialTheme.colorScheme.tertiary` (see `R.string.routine_details_stale_warning`'s call
 * site). */
@Composable
private fun StaleIndicator(tier: WidgetSizeTier) {
    val context = LocalContext.current
    Text(
        text = "  " + context.getString(R.string.widget_stale_indicator),
        maxLines = 1,
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.statusSize, color = GlanceTheme.colors.tertiary),
    )
}

/** A small rounded badge with the real line number, colored by [LineBadgeColorMapping] — bold
 * white text on every color (including [BADGE_GREY] for an unmapped line), for reliable
 * contrast regardless of which family color is picked. */
@Composable
private fun LineBadge(text: String, color: LineBadgeColor, textSize: TextUnit) {
    Box(
        modifier = GlanceModifier
            .background(color.toBadgeColor())
            .cornerRadius(6.dp)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, maxLines = 1, style = TextStyle(color = BADGE_TEXT_WHITE, fontWeight = FontWeight.Bold, fontSize = textSize))
    }
}

@Composable
private fun WidgetContentBody(context: Context, model: RoutineWidgetModel, compact: Boolean, tier: WidgetSizeTier) {
    when (val content = model.content) {
        RoutineWidgetContent.Loading -> BodyText(context.getString(R.string.notification_loading), tier)
        is RoutineWidgetContent.Live -> DepartureMainContent(context, model, content.next, content.following, compact, tier)
        is RoutineWidgetContent.Stale -> {
            val next = content.next
            if (next != null) {
                if (!compact) BodyText(context.getString(R.string.notification_stale_warning), tier)
                DepartureMainContent(context, model, next, content.following, compact, tier)
            } else {
                BodyText(context.getString(R.string.notification_no_departures), tier)
            }
        }
        is RoutineWidgetContent.NoUpcomingDepartures -> BodyText(context.getString(R.string.notification_no_departures), tier)
        RoutineWidgetContent.Offline -> BodyText(context.getString(R.string.notification_offline), tier)
        RoutineWidgetContent.Unavailable -> BodyText(context.getString(R.string.notification_unavailable), tier)
        RoutineWidgetContent.NotificationsUnavailable -> BodyText(context.getString(R.string.widget_notifications_unavailable), tier)
    }
}

/** The "6 min" big countdown on the left, and — outside [compact] heights only — the
 * station → direction line plus the following departure's own smaller countdown on the right,
 * and the live/scheduled/cancelled status row underneath. */
@Composable
private fun DepartureMainContent(
    context: Context,
    model: RoutineWidgetModel,
    next: WidgetDepartureRow,
    following: WidgetDepartureRow?,
    compact: Boolean,
    tier: WidgetSizeTier,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.CenterStart) {
                CountdownText(context, next, tier)
            }
            if (!compact) {
                Column(horizontalAlignment = Alignment.End) {
                    val subtitle = model.directionLabel?.let { "${model.stationName} → $it" } ?: model.stationName
                    Text(text = subtitle, maxLines = 1, style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()))
                    following?.let { row ->
                        val nextLabel = context.getString(R.string.widget_next_departure_label)
                        val minutesText = context.getString(R.string.widget_countdown_minutes_format, row.minutesRemaining)
                        Text(text = "$nextLabel  $minutesText", maxLines = 1, style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()))
                    }
                }
            }
        }
        if (!compact) {
            StatusFooter(context, next, tier)
        }
    }
}

@Composable
private fun CountdownText(context: Context, row: WidgetDepartureRow, tier: WidgetSizeTier) {
    val text = if (row.isCancelled) {
        context.getString(R.string.routine_details_departure_cancelled)
    } else {
        context.getString(R.string.widget_countdown_minutes_format, row.minutesRemaining)
    }
    Text(text = text, maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.countdownSize, color = onBackgroundColor()))
}

/** A small colored dot plus a "Live"/"Scheduled"/"Cancelled" label — green+"Live" for a
 * real-time departure (reusing [BADGE_GREEN], the same green given for the line-badge family,
 * as this widget's one shared "positive/live" color), a theme-neutral outline dot for a merely
 * scheduled one, and [GlanceTheme.colors.error] for a cancelled one. */
@Composable
private fun StatusFooter(context: Context, next: WidgetDepartureRow, tier: WidgetSizeTier) {
    val dotColor: ColorProvider
    val label: String
    when {
        next.isCancelled -> {
            dotColor = GlanceTheme.colors.error
            label = context.getString(R.string.routine_details_departure_cancelled)
        }
        next.isRealTime -> {
            dotColor = ColorProvider(BADGE_GREEN)
            label = context.getString(R.string.routine_details_departure_live)
        }
        else -> {
            dotColor = GlanceTheme.colors.outline
            label = context.getString(R.string.routine_details_departure_scheduled)
        }
    }
    Row(modifier = GlanceModifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = GlanceModifier.size(8.dp).background(dotColor).cornerRadius(4.dp)) {}
        Text(text = "  $label", maxLines = 1, style = TextStyle(fontSize = tier.statusSize, color = onSurfaceVariantColor()))
    }
}

@Composable
private fun BodyText(text: String, tier: WidgetSizeTier) {
    Text(text = text, maxLines = 2, style = TextStyle(fontSize = tier.secondarySize, color = onBackgroundColor()))
}

// [androidx.glance.text.TextStyle]'s own default color is a fixed Color.Black (see its own
// KDoc), never theme-aware — left at that default, text would be unreadable against
// GlanceTheme.colors.widgetBackground in dark mode. Every Text above sets one of these two
// explicitly instead, which is the other half of "add a readable widget background".
@Composable
private fun onBackgroundColor(): ColorProvider = GlanceTheme.colors.onBackground

@Composable
private fun onSurfaceVariantColor(): ColorProvider = GlanceTheme.colors.onSurfaceVariant
