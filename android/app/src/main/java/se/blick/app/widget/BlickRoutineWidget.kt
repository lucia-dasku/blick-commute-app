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
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import se.blick.app.R
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.domain.usecase.isDepartureCurrent
import se.blick.app.locale.withAppLocale

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
 *
 * [BlickRoutineWidgetReceiverCompact] and [BlickRoutineWidgetReceiverLarge] are two sibling
 * receivers, each pairing a differently-sized `AppWidgetProviderInfo` (see
 * `res/xml/blick_routine_widget_info_compact.xml` / `_large.xml`) with this exact same
 * [BlickRoutineWidget] instance-per-class — giving the platform's widget picker three
 * distinctly-sized, distinctly-previewed entries (the pattern real launchers, including
 * Samsung's One UI picker, show as separate size cards, e.g. "2x1"/"2x2"/"4x2") without
 * duplicating any rendering logic: [BlickRoutineWidget] already adapts to its live placed size
 * via [SizeMode.Exact]/[isCompactLayout]/[sizeTierFor] regardless of which receiver placed it.
 * This is the officially supported way to offer multiple provider sizes for one Glance widget —
 * [GlanceAppWidgetManager.getGlanceIds] (see [RoutineWidgetUpdater.applyToAllInstances]) looks
 * up instances by [BlickRoutineWidget]'s class, not by receiver, so it already finds instances
 * placed from any of the three without needing to change at all. */
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

/** Pairs `res/xml/blick_routine_widget_info_compact.xml` with the same [BlickRoutineWidget] —
 * see [BlickRoutineWidgetReceiver]'s own doc for why this sibling-receiver pattern is safe. */
class BlickRoutineWidgetReceiverCompact : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlickRoutineWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetReconcileWorker.enqueue(context)
    }
}

/** Pairs `res/xml/blick_routine_widget_info_large.xml` with the same [BlickRoutineWidget] — see
 * [BlickRoutineWidgetReceiver]'s own doc for why this sibling-receiver pattern is safe. */
class BlickRoutineWidgetReceiverLarge : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlickRoutineWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetReconcileWorker.enqueue(context)
    }
}

/** Below this height, only the header (line badge + route) plus the single big countdown
 * reliably fit without clipping (`blick_routine_widget_info.xml`'s declared `minHeight` is
 * 90dp) — the "Next" secondary block and the live/scheduled/cancelled status row are dropped
 * rather than clipped or left to overflow the widget's bounds. */
private val COMPACT_HEIGHT_THRESHOLD = 110.dp

/** Below this width, the secondary "Next" block and status row (see [DepartureMainContent])
 * have too little room to render their own text without wrapping into the countdown's own
 * space or clipping — a narrow-but-tall grid cell (e.g. a single-column placement) needs the
 * same compact layout as a short-but-wide one, not just smaller fonts. Matches [sizeTierFor]'s
 * own smallest width tier boundary (see that function's doc on why 220dp is "a realistic phone
 * grid cell") so anything narrow enough to already get the smallest font tier also drops to
 * the compact layout, not just smaller text within the full one. */
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

// The actual badge color values (LINE_BADGE_PINK/BLUE/RED/GREEN/GREY) and the toBadgeColor()
// conversion now live in LineBadgeColorMapping.kt (same package, no import needed) — shared with
// se.blick.app.ui.components.LineBadge's standard-Compose badge, so both renderers draw from one
// source of truth rather than duplicating these literals. Only this Glance-specific white-text
// ColorProvider stays here, since androidx.glance.unit.ColorProvider has no standard-Compose use.
private val BADGE_TEXT_WHITE = ColorProvider(Color.White)

/** [ActiveRoutineContent] builds its own chrome (background/corner radius) instead of using the
 * shared [Scaffold] — see that function's own doc for why: a present disruption needs its own
 * full-bleed strip along the very bottom edge, which [Scaffold]'s single uniformly-padded
 * `content` slot cannot represent. [NoActiveCommuteContent] has no such need, so it keeps using
 * [Scaffold] exactly as before.
 *
 * `internal`, not `private` — this is the exact composable [BlickRoutineWidget.provideGlance]
 * calls (through [currentState]/[toWidgetUiState]), so it's also the one [BlickRoutineWidgetRenderTest]
 * renders through Glance's own real unit-test pipeline to prove [ActiveRoutineContent] truly
 * calls [resolveEffectiveModel] and that the [GlanceTheme] wrapper every color lookup below
 * depends on is present, rather than reproducing this tree's selection logic in the test.
 *
 * [now] defaults to [java.time.Instant.now] for the one real production call site
 * ([BlickRoutineWidget.provideGlance]). It's an explicit parameter rather than a bare internal
 * read specifically so a test can supply one fixed instant here and have it flow, unread again,
 * all the way through [ActiveRoutineContent]'s journey resolution/eligibility/countdown -- see
 * [BlickRoutineWidgetRenderTest]'s own doc for the race a second, independent clock read used to
 * cause. */
@Composable
internal fun BlickWidgetContent(state: RoutineWidgetUiState, now: java.time.Instant = java.time.Instant.now()) {
    // .withAppLocale() -- this Context flows down into every Blick-owned string lookup below
    // (ActiveRoutineContent and everything under it already take context as a plain parameter,
    // see this file's own doc), resolving against Blick's own selected app language rather than
    // whatever the device's system locale happens to be. SL-derived text in [state] itself
    // (station/destination/line/disruption headline) is untouched either way -- it was never a
    // string resource to begin with.
    val context = LocalContext.current.withAppLocale()
    GlanceTheme {
        when (state) {
            RoutineWidgetUiState.NoActiveCommute -> Scaffold { NoActiveCommuteContent() }
            is RoutineWidgetUiState.ActiveRoutine -> ActiveRoutineContent(context, state.model, now)
        }
    }
}

@Composable
private fun NoActiveCommuteContent() {
    val context = LocalContext.current.withAppLocale()
    val tier = sizeTierFor(LocalSize.current.width)
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyText(context.getString(R.string.widget_no_active_commute), tier)
    }
}

/** Corner radius for [ActiveRoutineContent]'s own hand-built chrome — matches [Scaffold]'s own
 * look closely enough that [NoActiveCommuteContent] (which still uses [Scaffold]) and this look
 * like the same widget shell. */
private val WIDGET_CORNER_RADIUS = 16.dp

/** Horizontal inset for the main content column — matches [Scaffold]'s own default
 * `horizontalPadding` (16dp) so this hand-built chrome reads identically to [Scaffold]'s. */
private val WIDGET_HORIZONTAL_PADDING = 16.dp

/**
 * A clean, left-aligned vertical stack: a line badge + "{station} → {destination}" route
 * header, a large next-departure countdown, the following departure's own countdown, and a
 * live/scheduled/cancelled status row — see [WidgetHeader]/[WidgetContentBody]/
 * [DepartureMainContent]/[StatusFooter]. The route is shown once, in the header — never
 * repeated below the countdown, matching the same "identity shown once, next to the badge"
 * shape the ongoing notification's own title and a routine's own default name both use. A
 * relevant disruption, if any, is shown as a distinct, full-bleed muted-red strip along the
 * very bottom edge (see [DisruptionStrip]) — never inline with the rest of the content, and
 * never in [compact] mode, where there simply isn't room.
 *
 * Builds its own chrome (background + corner radius via [androidx.glance.appwidget.appWidgetBackground]/
 * [androidx.glance.appwidget.cornerRadius]) instead of delegating to the shared [Scaffold], which
 * only ever offers one uniformly-padded content slot — insufficient for [DisruptionStrip]'s own
 * full-bleed requirement. The main content column is still padded exactly like [Scaffold] would
 * (see [WIDGET_HORIZONTAL_PADDING]), so the common (no disruption) case looks unchanged.
 */
@Composable
private fun ActiveRoutineContent(context: Context, model: RoutineWidgetModel, now: java.time.Instant) {
    // [now] is BlickWidgetContent's own single Instant for this whole render (see that
    // function's own doc) -- never read independently here. Threaded through render-time
    // journey resolution (header selection AND eligibility) below, and further into
    // WidgetContentBody/JourneyMainContent's own countdown calculation, so every part of one
    // composition agrees on exactly the same "now".
    // See resolveEffectiveModel's own doc: for a Journeys routine, this re-resolves fastest/
    // alternative against `now` (promoting the alternative into the primary slot if the original
    // fastest has since departed, or falling back to Unavailable if both have) and keeps the
    // header's own line badge in agreement with whatever it resolves to -- for every other
    // RoutineWidgetContent, `model` is returned completely unchanged. Used for EVERYTHING below,
    // not just the body, so the header can never disagree with what the body actually renders.
    val model = resolveEffectiveModel(model, now)
    val clickAction = actionStartActivity(routineDetailsTapIntent(context, model.routineId))
    // Read once per composition: SizeMode.Exact recomposes this whole tree on every resize, so
    // every use below always reflects the widget's current on-screen size.
    val size = LocalSize.current
    val compact = isCompactLayout(size.width, size.height)
    val tier = sizeTierFor(size.width)
    // "{station} → {destination}" -- matches the same pattern the ongoing notification's own
    // title and a routine's own default name both use (see RoutineNotificationBuilder.title,
    // RoutineCreateViewModel.selectDirection); falls back to the station alone when the
    // routine never pinned a specific direction.
    val routeText = model.directionLabel?.let { "${model.stationName} → $it" } ?: model.stationName
    // Only ever a Stale case's own doc for why this must be rendered as a short, ALWAYS-visible
    // header marker rather than the fuller body-text sentence WidgetContentBody's Stale branch
    // already shows in non-compact mode with a next departure -- that longer sentence is dropped
    // in compact mode, and dropped entirely once every stale departure has since expired
    // (WidgetContentBody falls back to a plain "no departures" body then), so without this
    // header-level marker a genuinely failed refresh could look identical to a healthy state in
    // either case.
    val isStale = model.content is RoutineWidgetContent.Stale
    // No room for the disruption strip in compact mode -- same reasoning as dropping the
    // secondary station/next-departure block and status row there (see WidgetContentBody).
    val disruptionHeadline = model.disruptionHeadline?.takeIf { !compact }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WIDGET_CORNER_RADIUS)
            .clickable(clickAction),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // defaultWeight() so this main block always fills whatever height the (optional)
            // disruption strip below doesn't need -- see this composable's own doc on why
            // "content fills the widget instead of being cramped at the top" is achieved by
            // centering this block in the space available, not merely by adding fixed padding.
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .padding(horizontal = WIDGET_HORIZONTAL_PADDING, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The routine's own user-given name -- distinct from the station/destination
                // text the header already shows -- is deliberately NOT repeated here: with the
                // default auto-suggested name (see RoutineCreateViewModel.selectDirection), it's
                // exactly "{line} → {destination}", i.e. the same information the header below
                // already shows via the line badge + destination text, duplicated as plain text
                // right above it. Dropped entirely rather than only in compact mode.
                WidgetHeader(model, tier, routeText, isStale)
                Spacer(modifier = GlanceModifier.height(if (compact) 6.dp else 12.dp))
                WidgetContentBody(context, model, compact, tier, now)
            }
            if (disruptionHeadline != null) {
                DisruptionStrip(disruptionHeadline, tier)
            }
        }
    }
}

@Composable
private fun WidgetHeader(model: RoutineWidgetModel, tier: WidgetSizeTier, routeText: String, isStale: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        model.lineDesignation?.let { line ->
            LineBadge(line, LineBadgeColorMapping.colorFor(model.transportMode, line), tier.badgeSize)
            Spacer(modifier = GlanceModifier.width(8.dp))
        }
        Text(
            text = routeText,
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.headerSize, color = onBackgroundColor()),
        )
        if (isStale) {
            StaleIndicator(tier)
        }
    }
}

/**
 * A relevant disruption's headline as a distinct, full-bleed band along the very bottom edge —
 * outside the main content [Column]'s own padding (see [ActiveRoutineContent]), so it touches
 * the widget's left/right/bottom edges directly rather than sitting inset like the rest of the
 * content. [GlanceTheme.colors.errorContainer]/`onErrorContainer` — the same theme-adaptive
 * "muted red" role `RoutineDetailsScreen`'s own disruption cards use via
 * `MaterialTheme.colorScheme.errorContainer`/`onErrorContainer` — rather than a hardcoded color,
 * so this looks correct in both light and dark theme, like everything else in this file. A
 * trailing "›" is a tap-for-more affordance only — tapping anywhere on the widget (including
 * this strip) already opens Routine Details via [ActiveRoutineContent]'s own [clickable], where
 * the disruption's full text is shown; this is not a second, separate tap target.
 */
@Composable
private fun DisruptionStrip(headline: String, tier: WidgetSizeTier) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.errorContainer)
            .padding(horizontal = WIDGET_HORIZONTAL_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headline,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = tier.statusSize, color = GlanceTheme.colors.onErrorContainer),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = "›",
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.statusSize, color = GlanceTheme.colors.onErrorContainer),
        )
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
    val context = LocalContext.current.withAppLocale()
    Text(
        text = "  " + context.getString(R.string.widget_stale_indicator),
        maxLines = 1,
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.statusSize, color = GlanceTheme.colors.tertiary),
    )
}

/** A small rounded badge with the real line number, colored by [LineBadgeColorMapping] — bold
 * white text on every color (including [LINE_BADGE_GREY] for an unmapped line), for reliable
 * contrast regardless of which family color is picked. See [se.blick.app.ui.components.LineBadge]
 * for the same badge rendered outside the widget, elsewhere in the app. */
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

/**
 * Resolves what this widget should actually render for [model] at [now] — the render-time
 * counterpart to [decideJourneysWidgetState]'s own write-time filter (see that function's own
 * doc): a [RoutineWidgetContent.Journeys] row persisted as still-current at UPDATE time can have
 * since departed by the time Glance actually composes it, since this widget has no self-refresh
 * of its own between the worker's ~30-second pushes (see [BlickRoutineWidget]'s own class doc).
 * For any [RoutineWidgetContent] other than [RoutineWidgetContent.Journeys] (a LINE_DIRECTION
 * routine, or no active routine at all), [model] is returned completely unchanged.
 *
 * Evaluates [RoutineWidgetContent.Journeys.fastest] and [RoutineWidgetContent.Journeys.alternative]
 * in that existing order via [isDepartureCurrent]:
 * - both current: [model] is returned unchanged.
 * - only [RoutineWidgetContent.Journeys.fastest] current: the alternative is dropped (becomes
 *   null) — never rendered as a secondary journey once it's no longer genuinely valid.
 * - only [RoutineWidgetContent.Journeys.alternative] current: it is PROMOTED into the `fastest`
 *   slot — used for the primary countdown/arrival AND for [RoutineWidgetModel.lineDesignation]/
 *   [RoutineWidgetModel.transportMode] (the header's own line badge), which otherwise still
 *   describe the original, now-expired fastest row (they are set once, at
 *   [decideJourneysWidgetState]'s own write time, and never independently re-derived by anything
 *   downstream) — and is never ALSO shown a second time as its own alternative.
 * - neither current: falls back to [RoutineWidgetContent.Unavailable], with
 *   [RoutineWidgetModel.lineDesignation] cleared to `null` so the header stops rendering a line
 *   badge at all rather than keep showing an expired journey's one over an "unavailable" body.
 *
 * A single small pure function (no Glance/Android dependency) precisely so it can be unit-tested
 * directly — see `BlickRoutineWidgetTest` — independent of the real Glance rendering pipeline,
 * which [ActiveRoutineContent] separately proves actually calls this.
 */
internal fun resolveEffectiveModel(model: RoutineWidgetModel, now: java.time.Instant): RoutineWidgetModel {
    val content = model.content
    if (content !is RoutineWidgetContent.Journeys) return model
    val current = listOfNotNull(content.fastest, content.alternative).filter { isDepartureCurrent(now, it.departureTime) }
    val primary = current.firstOrNull()
        ?: return model.copy(content = RoutineWidgetContent.Unavailable, lineDesignation = null)
    return model.copy(
        content = RoutineWidgetContent.Journeys(primary, current.getOrNull(1)),
        lineDesignation = primary.lineDesignation,
        transportMode = primary.transportMode,
    )
}

@Composable
private fun WidgetContentBody(context: Context, model: RoutineWidgetModel, compact: Boolean, tier: WidgetSizeTier, now: java.time.Instant) {
    when (val content = model.content) {
        RoutineWidgetContent.Loading -> BodyText(context.getString(R.string.notification_loading), tier)
        is RoutineWidgetContent.Live -> DepartureMainContent(context, content.next, content.following, compact, tier)
        is RoutineWidgetContent.Stale -> {
            val next = content.next
            if (next != null) {
                if (!compact) BodyText(context.getString(R.string.notification_stale_warning), tier)
                DepartureMainContent(context, next, content.following, compact, tier)
            } else {
                BodyText(context.getString(R.string.notification_no_departures), tier)
            }
        }
        is RoutineWidgetContent.NoUpcomingDepartures -> BodyText(context.getString(R.string.notification_no_departures), tier)
        RoutineWidgetContent.Offline -> BodyText(context.getString(R.string.notification_offline), tier)
        RoutineWidgetContent.Unavailable -> BodyText(context.getString(R.string.notification_unavailable), tier)
        RoutineWidgetContent.NotificationsUnavailable -> BodyText(context.getString(R.string.widget_notifications_unavailable), tier)
        is RoutineWidgetContent.Journeys -> JourneyMainContent(context, content, compact, tier, now)
    }
}

@Composable
private fun JourneyMainContent(
    context: Context,
    content: RoutineWidgetContent.Journeys,
    compact: Boolean,
    tier: WidgetSizeTier,
    now: java.time.Instant,
) {
    // Final render-time eligibility check, using the same isDepartureCurrent building block every
    // other exact-journey consumer shares (see that function's own doc), against the SAME `now`
    // ActiveRoutineContent already resolved this exact row with (see resolveEffectiveModel) --
    // never re-read here. In normal operation `content.fastest` is therefore already guaranteed
    // current by construction; this stays a defensive backstop rather than assuming that
    // guarantee holds regardless of caller. An expired fastest row is never rendered as "0 min" --
    // it falls back to the same "unavailable" body text RoutineWidgetContent.Unavailable already
    // uses elsewhere in this file.
    if (!isDepartureCurrent(now, content.fastest.departureTime)) {
        BodyText(context.getString(R.string.notification_unavailable), tier)
        return
    }
    // countdownMinutes, never a floor-based Duration.toMinutes().coerceAtLeast(0) -- see
    // RoutineDetailsScreen's identical JourneyComparisonSection comment for why. Safe to call
    // unconditionally now: the guard above already refused to reach this line for an expired
    // fastest departure.
    val fastestMinutes = countdownMinutes(now, content.fastest.departureTime)
    val arrivalFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            context.getString(R.string.widget_countdown_minutes_format, fastestMinutes),
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.countdownSize, color = onBackgroundColor()),
        )
        Text(
            context.getString(R.string.widget_journey_arrival, arrivalFormatter.format(content.fastest.arrivalTime)),
            maxLines = 1,
            style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
        )
        // Same final render-time check applied to the alternative independently -- an expired
        // alternative is simply omitted (the fastest row above is unaffected), never shown as 0 min.
        if (!compact) content.alternative
            ?.takeIf { isDepartureCurrent(now, it.departureTime) }
            ?.let { alternative ->
                val alternativeMinutes = countdownMinutes(now, alternative.departureTime)
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    context.getString(R.string.widget_journey_alternative, alternative.lineDesignation.orEmpty(), alternativeMinutes,
                        arrivalFormatter.format(alternative.arrivalTime)),
                    maxLines = 1,
                    style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
                )
            }
    }
}

/** A clean, left-aligned vertical stack: the big "6 min" countdown, then — outside [compact]
 * heights only, where there simply isn't room — the following departure's own countdown and
 * the live/scheduled/cancelled status row. The station → direction route is shown once, in
 * [WidgetHeader] next to the line badge, not repeated here (see this function's own comment
 * below). Previously a side-by-side layout (countdown on the left, station/next-departure
 * column on the right); the fully vertical stack instead matches how every other section of
 * this widget (and the rest of the app) presents a line/route/status sequence, and reads less
 * cramped at ordinary widget sizes. */
@Composable
private fun DepartureMainContent(
    context: Context,
    next: WidgetDepartureRow,
    following: WidgetDepartureRow?,
    compact: Boolean,
    tier: WidgetSizeTier,
) {
    Column(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        CountdownText(context, next, tier)
        if (!compact) {
            // The station → direction route is deliberately NOT repeated here -- WidgetHeader
            // already shows it next to the line badge (see ActiveRoutineContent's own
            // routeText), so a second copy directly below the countdown would just duplicate it.
            following?.let { row ->
                Spacer(modifier = GlanceModifier.height(10.dp))
                val text = context.getString(R.string.widget_next_departure_format, row.minutesRemaining)
                Text(text = text, maxLines = 1, style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()))
            }
            Spacer(modifier = GlanceModifier.height(10.dp))
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
 * real-time departure (reusing [LINE_BADGE_GREEN], the same green given for the line-badge
 * family, as this widget's one shared "positive/live" color), a theme-neutral outline dot for a
 * merely scheduled one, and [GlanceTheme.colors.error] for a cancelled one. */
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
            dotColor = ColorProvider(LINE_BADGE_GREEN)
            label = context.getString(R.string.routine_details_departure_live)
        }
        else -> {
            dotColor = GlanceTheme.colors.outline
            label = context.getString(R.string.routine_details_departure_scheduled)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
