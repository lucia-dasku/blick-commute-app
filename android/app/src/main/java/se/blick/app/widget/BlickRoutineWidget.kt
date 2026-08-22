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
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.ContentScale
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import se.blick.app.R
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.domain.usecase.isDepartureCurrent
import se.blick.app.locale.withAppLocale
import se.blick.app.notification.disruptionEffectLabelRes
import se.blick.app.ui.components.stringResourceId
import se.blick.app.ui.components.visuals
import java.time.format.DateTimeFormatter

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
 * via [SizeMode.Exact]/[widgetLayoutRulesFor]/[sizeTierFor] regardless of which receiver placed it.
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

private val STANDARD_MIN_WIDTH = 220.dp
private val STANDARD_MIN_HEIGHT = 110.dp
private val LARGE_MIN_WIDTH = 300.dp
private val LARGE_MIN_HEIGHT = 220.dp
private val SMALL_LABEL_MIN_HEIGHT = 120.dp
private val STANDARD_DISRUPTION_MIN_HEIGHT = 180.dp

/** Exactly the three supported widget layouts. Launchers report slightly different physical
 * bounds for the same grid cells, so [widgetLayoutRulesFor] safely falls back to the nearest
 * smaller layout whenever either dimension cannot support the next tier. */
internal enum class WidgetLayoutTier { SMALL, STANDARD, LARGE }

internal data class WidgetLayoutRules(
    val tier: WidgetLayoutTier,
    val routeMaxLines: Int,
    val showRoutineLabel: Boolean,
    val showSecondary: Boolean,
    val showJourneyTimes: Boolean,
    val showDisruption: Boolean,
)

internal fun widgetLayoutRulesFor(width: Dp, height: Dp): WidgetLayoutRules {
    val tier = when {
        width >= LARGE_MIN_WIDTH && height >= LARGE_MIN_HEIGHT -> WidgetLayoutTier.LARGE
        width < STANDARD_MIN_WIDTH || height < STANDARD_MIN_HEIGHT -> WidgetLayoutTier.SMALL
        else -> WidgetLayoutTier.STANDARD
    }
    return when (tier) {
        WidgetLayoutTier.SMALL -> WidgetLayoutRules(
            tier = tier,
            routeMaxLines = 2,
            showRoutineLabel = height >= SMALL_LABEL_MIN_HEIGHT,
            showSecondary = false,
            showJourneyTimes = false,
            showDisruption = false,
        )
        WidgetLayoutTier.STANDARD -> WidgetLayoutRules(
            tier = tier,
            routeMaxLines = 1,
            showRoutineLabel = true,
            showSecondary = true,
            showJourneyTimes = false,
            showDisruption = height >= STANDARD_DISRUPTION_MIN_HEIGHT,
        )
        WidgetLayoutTier.LARGE -> WidgetLayoutRules(
            tier = tier,
            routeMaxLines = 2,
            showRoutineLabel = true,
            showSecondary = true,
            showJourneyTimes = true,
            showDisruption = true,
        )
    }
}

/** Typography for the same three canonical layouts. */
private data class WidgetSizeTier(
    val labelSize: TextUnit,
    val labelIconSize: Dp,
    val headerSize: TextUnit,
    val badgeSize: TextUnit,
    val countdownSize: TextUnit,
    val secondarySize: TextUnit,
    val statusSize: TextUnit,
)

private val TIER_SMALL = WidgetSizeTier(
    labelSize = 10.sp, labelIconSize = 12.dp, headerSize = 12.sp, badgeSize = 10.sp,
    countdownSize = 28.sp, secondarySize = 11.sp, statusSize = 10.sp,
)
private val TIER_STANDARD = WidgetSizeTier(
    labelSize = 11.sp, labelIconSize = 14.dp, headerSize = 14.sp, badgeSize = 11.sp,
    countdownSize = 36.sp, secondarySize = 12.sp, statusSize = 10.sp,
)
private val TIER_LARGE = WidgetSizeTier(
    labelSize = 12.sp, labelIconSize = 16.dp, headerSize = 16.sp, badgeSize = 13.sp,
    countdownSize = 48.sp, secondarySize = 14.sp, statusSize = 12.sp,
)

private fun sizeTierFor(layoutTier: WidgetLayoutTier): WidgetSizeTier = when (layoutTier) {
    WidgetLayoutTier.SMALL -> TIER_SMALL
    WidgetLayoutTier.STANDARD -> TIER_STANDARD
    WidgetLayoutTier.LARGE -> TIER_LARGE
}

// The actual badge color values (LINE_BADGE_PINK/BLUE/RED/GREEN/GREY) and the toBadgeColor()
// conversion now live in LineBadgeColorMapping.kt (same package, no import needed) — shared with
// se.blick.app.ui.components.LineBadge's standard-Compose badge, so both renderers draw from one
// source of truth rather than duplicating these literals. Only this Glance-specific white-text
// ColorProvider stays here, since androidx.glance.unit.ColorProvider has no standard-Compose use.
private val BADGE_TEXT_WHITE = ColorProvider(Color.White)
private val INACTIVE_WIDGET_BACKGROUND = ColorProvider(Color(0xFF010C2F))
private val INACTIVE_WIDGET_PRIMARY = ColorProvider(Color.White)
private val INACTIVE_WIDGET_SECONDARY = ColorProvider(Color(0xFFC5C8CF))
private val INACTIVE_WIDGET_MINT = ColorProvider(Color(0xFF33E4A1))

/** Responsive presentation values for the branded inactive state. The skyline is intentionally
 * absent at compact sizes, and is also dropped for unusually short Standard bounds reported by
 * some launchers, so the logo and localized status can never be crowded or overlap it. */
internal data class InactiveWidgetLayout(
    val logoViewportWidth: Dp,
    val logoViewportHeight: Dp,
    val logoAssetSize: Dp,
    val brandSize: TextUnit,
    val statusSize: TextUnit,
    val logoBrandGap: Dp,
    val brandStatusGap: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val skylineHeight: Dp?,
)

internal fun inactiveWidgetLayoutFor(width: Dp, height: Dp): InactiveWidgetLayout {
    val tier = widgetLayoutRulesFor(width, height).tier
    return when {
        tier == WidgetLayoutTier.LARGE -> InactiveWidgetLayout(
            logoViewportWidth = 66.dp,
            logoViewportHeight = 80.dp,
            logoAssetSize = 156.dp,
            brandSize = 30.sp,
            statusSize = 14.sp,
            logoBrandGap = 6.dp,
            brandStatusGap = 3.dp,
            horizontalPadding = 12.dp,
            verticalPadding = 4.dp,
            skylineHeight = 54.dp,
        )
        tier == WidgetLayoutTier.STANDARD && height >= 140.dp -> InactiveWidgetLayout(
            logoViewportWidth = 44.dp,
            logoViewportHeight = 52.dp,
            logoAssetSize = 102.dp,
            brandSize = 22.sp,
            statusSize = 12.sp,
            logoBrandGap = 2.dp,
            brandStatusGap = 1.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 4.dp,
            skylineHeight = 28.dp,
        )
        tier == WidgetLayoutTier.STANDARD -> InactiveWidgetLayout(
            logoViewportWidth = 40.dp,
            logoViewportHeight = 46.dp,
            logoAssetSize = 90.dp,
            brandSize = 20.sp,
            statusSize = 11.sp,
            logoBrandGap = 2.dp,
            brandStatusGap = 1.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 4.dp,
            skylineHeight = null,
        )
        width < 140.dp || height < 96.dp -> InactiveWidgetLayout(
            logoViewportWidth = 26.dp,
            logoViewportHeight = 32.dp,
            logoAssetSize = 62.dp,
            brandSize = 16.sp,
            statusSize = 9.sp,
            logoBrandGap = 1.dp,
            brandStatusGap = 1.dp,
            horizontalPadding = 6.dp,
            verticalPadding = 2.dp,
            skylineHeight = null,
        )
        else -> InactiveWidgetLayout(
            logoViewportWidth = 40.dp,
            logoViewportHeight = 48.dp,
            logoAssetSize = 94.dp,
            brandSize = 20.sp,
            statusSize = 11.sp,
            logoBrandGap = 2.dp,
            brandStatusGap = 2.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 4.dp,
            skylineHeight = null,
        )
    }
}

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
            RoutineWidgetUiState.NoActiveCommute -> Scaffold(
                backgroundColor = INACTIVE_WIDGET_BACKGROUND,
                horizontalPadding = 0.dp,
            ) { NoActiveCommuteContent() }
            is RoutineWidgetUiState.ActiveRoutine -> ActiveRoutineContent(context, state.model, now)
        }
    }
}

@Composable
private fun NoActiveCommuteContent() {
    val context = LocalContext.current.withAppLocale()
    val size = LocalSize.current
    val layout = inactiveWidgetLayoutFor(size.width, size.height)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(
                horizontal = layout.horizontalPadding,
                vertical = layout.verticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The authoritative adaptive-icon foreground has intentional launcher-mask
                // padding. A centered oversized image inside this clipped viewport removes only
                // that transparent padding, just like BlickWordmark does in standard Compose;
                // the actual logo pixels/path are reused unchanged.
                Box(
                    modifier = GlanceModifier.size(
                        width = layout.logoViewportWidth,
                        height = layout.logoViewportHeight,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = GlanceModifier.size(layout.logoAssetSize),
                        colorFilter = ColorFilter.tint(INACTIVE_WIDGET_MINT),
                    )
                }
                Spacer(modifier = GlanceModifier.height(layout.logoBrandGap))
                Text(
                    text = context.getString(R.string.app_name),
                    maxLines = 1,
                    style = TextStyle(
                        color = INACTIVE_WIDGET_PRIMARY,
                        fontSize = layout.brandSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(layout.brandStatusGap))
                Text(
                    text = context.getString(R.string.widget_no_active_commute),
                    maxLines = 2,
                    modifier = GlanceModifier.fillMaxWidth(),
                    style = TextStyle(
                        color = INACTIVE_WIDGET_SECONDARY,
                        fontSize = layout.statusSize,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
        layout.skylineHeight?.let { skylineHeight ->
            Image(
                provider = ImageProvider(R.drawable.widget_inactive_skyline),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().height(skylineHeight),
                contentScale = ContentScale.FillBounds,
            )
        }
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
 * never in the Small tier, where there simply isn't room.
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
    val layout = widgetLayoutRulesFor(size.width, size.height)
    val tier = sizeTierFor(layout.tier)
    // "{station} → {destination}" -- matches the same pattern the ongoing notification's own
    // title and a routine's own default name both use (see RoutineNotificationBuilder.title,
    // RoutineCreateViewModel.selectDirection); falls back to the station alone when the
    // routine never pinned a specific direction.
    val routeText = model.directionLabel?.let { "${model.stationName} → $it" } ?: model.stationName
    // Only ever a Stale case's own doc for why this must be rendered as a short, ALWAYS-visible
    // header marker rather than the fuller body-text sentence WidgetContentBody's Stale branch
    // already shows in Standard/Large with a next departure -- that longer sentence is dropped
    // in Small, and dropped entirely once every stale departure has since expired
    // (WidgetContentBody falls back to a plain "no departures" body then), so without this
    // header-level marker a genuinely failed refresh could look identical to a healthy state in
    // either case.
    val isStale = model.content is RoutineWidgetContent.Stale
    val disruptionStripText = disruptionStripText(
        context = context,
        model = model,
        useFullMessage = layout.tier == WidgetLayoutTier.LARGE,
    ).takeIf { layout.showDisruption }

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
                    .padding(
                        horizontal = WIDGET_HORIZONTAL_PADDING,
                        vertical = when (layout.tier) {
                            WidgetLayoutTier.SMALL -> 10.dp
                            WidgetLayoutTier.STANDARD -> 6.dp
                            WidgetLayoutTier.LARGE -> 10.dp
                        },
                    ),
                verticalAlignment = if (layout.tier == WidgetLayoutTier.LARGE) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (layout.showRoutineLabel) {
                    model.label?.let { label ->
                        RoutineLabelChip(context, label, tier)
                        Spacer(
                            modifier = GlanceModifier.height(
                                when (layout.tier) {
                                    WidgetLayoutTier.SMALL -> 4.dp
                                    WidgetLayoutTier.STANDARD -> 3.dp
                                    WidgetLayoutTier.LARGE -> 4.dp
                                },
                            ),
                        )
                    }
                }
                WidgetHeader(tier, routeText, isStale, layout.routeMaxLines)
                Spacer(
                    modifier = GlanceModifier.height(
                        when (layout.tier) {
                            WidgetLayoutTier.SMALL -> 6.dp
                            WidgetLayoutTier.STANDARD -> 4.dp
                            WidgetLayoutTier.LARGE -> 6.dp
                        },
                    ),
                )
                WidgetContentBody(context, model, layout, tier, now)
            }
            if (disruptionStripText != null) {
                DisruptionStrip(
                    text = disruptionStripText,
                    tier = tier,
                    maxLines = if (layout.tier == WidgetLayoutTier.LARGE) 2 else 1,
                )
            }
        }
    }
}

/** The widget's disruption-strip text. At the Large tier [useFullMessage] is true, so the bottom
 * area uses the real message already present on [RoutineWidgetModel.disruptionHeadline]. Standard
 * keeps the existing short localized summary because space is constrained. Its cases are:
 * 1. [RoutineWidgetModel.disruptionUncertainLineDesignations] non-empty (`LINE_RELEVANT`: SL's
 *    line/mode scope matched but the affected segment/stop was not proven to intersect this
 *    exact journey) — a conservative "Line 11 disruption"-style label built from it, via the
 *    exact same [R.string.notification_disruption_line_relevant_single_format]/`_generic`
 *    resources [se.blick.app.notification.RoutineNotificationBuilder]'s own
 *    `lineRelevantDisruptionLabel` uses, so the widget and notification never disagree about how
 *    uncertain this same disruption is presented. The classified [RoutineWidgetModel.disruptionEffect]
 *    is deliberately NEVER rendered here, even when it names something as specific as
 *    `NO_SERVICE` — LINE_RELEVANT means that effect was never proven to apply to this exact
 *    journey, only to the line/mode in general.
 * 2. Otherwise (`CONFIRMED`/`LINE_DIRECTION`: already proven relevant) — [RoutineWidgetModel.disruptionEffect]'s
 *    own localized category via [disruptionEffectLabelRes], exactly like
 *    [se.blick.app.notification.RoutineNotificationBuilder]'s own disruption line — falling back
 *    to [DisruptionEffect.DISRUPTION]'s own generic "Disruption"/"Störning" label when
 *    [RoutineWidgetModel.disruptionEffect] is null, which happens only for state persisted by an
 *    app version predating that field: the headline is real, but its classification hasn't been
 *    persisted yet (the worker's next ~30-second tick overwrites it with a real effect).
 *
 * Null exactly when [RoutineWidgetModel.disruptionHeadline] is null. */
private fun disruptionStripText(context: Context, model: RoutineWidgetModel, useFullMessage: Boolean): String? {
    if (model.disruptionHeadline == null) return null
    if (useFullMessage) return model.disruptionHeadline
    val designations = model.disruptionUncertainLineDesignations
    return when {
        designations.size == 1 -> context.getString(R.string.notification_disruption_line_relevant_single_format, designations.single())
        designations.isNotEmpty() -> context.getString(R.string.notification_disruption_line_relevant_generic)
        else -> context.getString(disruptionEffectLabelRes(model.disruptionEffect ?: DisruptionEffect.DISRUPTION))
    }
}

@Composable
private fun WidgetHeader(tier: WidgetSizeTier, routeText: String, isStale: Boolean, routeMaxLines: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = routeText,
            maxLines = routeMaxLines,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.headerSize, color = onBackgroundColor()),
        )
        if (isStale) {
            StaleIndicator(tier)
        }
    }
}

/** Compact label identity shared with the app: same localized name, icon resource, and
 * light/dark accent/container colors. The icon is decorative because its text sits beside it. */
@Composable
private fun RoutineLabelChip(context: Context, label: RoutineLabel, tier: WidgetSizeTier) {
    val light = label.visuals(darkTheme = false)
    val dark = label.visuals(darkTheme = true)
    val accent = androidx.glance.color.ColorProvider(day = light.accent, night = dark.accent)
    val container = androidx.glance.color.ColorProvider(day = light.container, night = dark.container)
    Box(
        modifier = GlanceModifier
            .background(container)
            .cornerRadius(7.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(light.iconResourceId),
                contentDescription = null,
                modifier = GlanceModifier.size(tier.labelIconSize),
                colorFilter = ColorFilter.tint(accent),
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = context.getString(label.stringResourceId()),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.labelSize, color = accent),
            )
        }
    }
}

/**
 * [disruptionStripText]'s size-appropriate text as a distinct, full-bleed band along the very
 * bottom edge, outside the
 * main content [Column]'s own padding (see [ActiveRoutineContent]), so it touches the widget's
 * left/right/bottom edges directly rather than sitting inset like the rest of the content.
 * [GlanceTheme.colors.errorContainer]/`onErrorContainer` — the same theme-adaptive "muted red"
 * role `RoutineDetailsScreen`'s own disruption cards use via
 * `MaterialTheme.colorScheme.errorContainer`/`onErrorContainer` — rather than a hardcoded color,
 * so this looks correct in both light and dark theme, like everything else in this file. A
 * trailing "›" is a tap-for-more affordance only — tapping anywhere on the widget (including
 * this strip) already opens Routine Details via [ActiveRoutineContent]'s own [clickable], where
 * the disruption's full original SL text is shown; this is not a second, separate tap target.
 */
@Composable
private fun DisruptionStrip(text: String, tier: WidgetSizeTier, maxLines: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.errorContainer)
            .padding(horizontal = WIDGET_HORIZONTAL_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            maxLines = maxLines,
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
 * layout tier or whether any departure is still upcoming, so this is the one place a
 * stale-data warning is GUARANTEED visible in every layout this widget can render, unlike the
 * fuller body-text sentence [WidgetContentBody]'s `Stale` branch only shows outside the Small tier
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
 * Evaluates [RoutineWidgetContent.Journeys.primary] and [RoutineWidgetContent.Journeys.secondary]
 * in that existing order via [isDepartureCurrent]:
 * - both current: [model] is returned unchanged.
 * - only [RoutineWidgetContent.Journeys.primary] current: the secondary row is dropped (becomes
 *   null) — never rendered as a second journey once it's no longer genuinely valid.
 * - only [RoutineWidgetContent.Journeys.secondary] current: it is PROMOTED into the `primary`
 *   slot — used for the primary countdown/arrival AND for [RoutineWidgetModel.lineDesignation]/
 *   [RoutineWidgetModel.transportMode] (the header's own line badge), which otherwise still
 *   describe the original, now-expired primary row (they are set once, at
 *   [decideJourneysWidgetState]'s own write time, and never independently re-derived by anything
 *   downstream) — and is never ALSO shown a second time as its own secondary row. Its own
 *   [WidgetJourneyRow.role] (NEXT or ALTERNATIVE) travels with it unchanged — promotion never
 *   rewrites a journey's real backend meaning to PRIMARY.
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
    val current = listOfNotNull(content.primary, content.secondary).filter { isDepartureCurrent(now, it.departureTime) }
    val primary = current.firstOrNull()
        ?: return model.copy(content = RoutineWidgetContent.Unavailable, lineDesignation = null)
    return model.copy(
        // content.changesPreference carried through unchanged -- omitting it here would silently
        // fall back to its own default (BOTH) on every single render, discarding whatever the
        // routine's real persisted preference was regardless of what was actually written.
        content = RoutineWidgetContent.Journeys(primary, current.getOrNull(1), content.changesPreference),
        lineDesignation = primary.lineDesignation,
        transportMode = primary.transportMode,
    )
}

@Composable
private fun WidgetContentBody(
    context: Context,
    model: RoutineWidgetModel,
    layout: WidgetLayoutRules,
    tier: WidgetSizeTier,
    now: java.time.Instant,
) {
    when (val content = model.content) {
        RoutineWidgetContent.Loading -> BodyText(context.getString(R.string.notification_loading), tier)
        is RoutineWidgetContent.Live -> DepartureMainContent(context, model, content.next, content.following, layout, tier)
        is RoutineWidgetContent.Stale -> {
            val next = content.next
            if (next != null) {
                if (layout.tier != WidgetLayoutTier.SMALL) BodyText(context.getString(R.string.notification_stale_warning), tier)
                DepartureMainContent(context, model, next, content.following, layout, tier)
            } else {
                BodyText(context.getString(R.string.notification_no_departures), tier)
            }
        }
        is RoutineWidgetContent.NoUpcomingDepartures -> BodyText(context.getString(R.string.notification_no_departures), tier)
        RoutineWidgetContent.Offline -> BodyText(context.getString(R.string.notification_offline), tier)
        RoutineWidgetContent.Unavailable -> BodyText(context.getString(R.string.notification_unavailable), tier)
        RoutineWidgetContent.NotificationsUnavailable -> BodyText(context.getString(R.string.widget_notifications_unavailable), tier)
        is RoutineWidgetContent.Journeys -> JourneyMainContent(context, content, layout, tier, now)
    }
}

/**
 * Renders one of three layouts, switched ONLY on [RoutineWidgetContent.Journeys.changesPreference]
 * — the routine's own persisted Direct/Both/With-changes choice — never inferred from
 * [content.primary][RoutineWidgetContent.Journeys.primary]'s own [WidgetJourneyRow.transferCount]:
 * a [ExactDestinationChangesPreference.BOTH] routine and a
 * [ExactDestinationChangesPreference.WITH_CHANGES_ONLY] one can both be showing a journey with the
 * exact same transfer count, and only the stored preference tells them apart (see that field's own
 * doc). [JourneyCompositionRow] additionally reads [WidgetJourneyRow.transferCount] itself, but
 * only to decide "Direct" vs. "Arrive HH:mm · N change(s)" WORDING for the ACTUAL journey shown —
 * under [ExactDestinationChangesPreference.DIRECT_ONLY]/[ExactDestinationChangesPreference.WITH_CHANGES_ONLY]
 * this is never ambiguous, since the backend never returns a journey outside that preference's own
 * eligible set in the first place (see `backend/src/services/candidateCollector.ts`'s own
 * `JourneyChangesPreference` doc) — it only ever matters for [ExactDestinationChangesPreference.BOTH],
 * where a direct PRIMARY still reads "Direct" rather than the arguably-nonsensical "0 changes".
 *
 * A left-aligned vertical stack: the big countdown, then [JourneyCompositionRow] (line badge(s),
 * "Direct" or "Arrive HH:mm · N change(s)", and — [ExactDestinationChangesPreference.WITH_CHANGES_ONLY]
 * only — the small green "With changes" label), then — when [WidgetLayoutRules.showSecondary]
 * allows it, exactly
 * like the plain-departures [DepartureMainContent]'s own secondary row — a [WidgetDivider] and
 * [NextJourneyRow]. The disruption strip stays entirely outside this function (see
 * [ActiveRoutineContent]'s own [DisruptionStrip]), unaffected by any of this.
 */
@Composable
private fun JourneyMainContent(
    context: Context,
    content: RoutineWidgetContent.Journeys,
    layout: WidgetLayoutRules,
    tier: WidgetSizeTier,
    now: java.time.Instant,
) {
    // Final render-time eligibility check, using the same isDepartureCurrent building block every
    // other exact-journey consumer shares (see that function's own doc), against the SAME `now`
    // ActiveRoutineContent already resolved this exact row with (see resolveEffectiveModel) --
    // never re-read here. In normal operation `content.primary` is therefore already guaranteed
    // current by construction; this stays a defensive backstop rather than assuming that
    // guarantee holds regardless of caller. An expired primary row is never rendered as "0 min" --
    // it falls back to the same "unavailable" body text RoutineWidgetContent.Unavailable already
    // uses elsewhere in this file.
    if (!isDepartureCurrent(now, content.primary.departureTime)) {
        BodyText(context.getString(R.string.notification_unavailable), tier)
        return
    }
    // countdownMinutes, never a floor-based Duration.toMinutes().coerceAtLeast(0) -- see
    // RoutineDetailsScreen's identical JourneyComparisonSection comment for why. Safe to call
    // unconditionally now: the guard above already refused to reach this line for an expired
    // primary departure.
    val primaryMinutes = countdownMinutes(now, content.primary.departureTime)
    val arrivalFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            context.getString(R.string.widget_countdown_minutes_format, primaryMinutes),
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.countdownSize, color = onBackgroundColor()),
        )
        Spacer(
            modifier = GlanceModifier.height(
                when (layout.tier) {
                    WidgetLayoutTier.SMALL -> 4.dp
                    WidgetLayoutTier.STANDARD -> 2.dp
                    WidgetLayoutTier.LARGE -> 4.dp
                },
            ),
        )
        JourneyCompositionRow(
            context = context,
            primary = content.primary,
            changesPreference = content.changesPreference,
            arrivalFormatter = arrivalFormatter,
            includeArrivalInComposition = !layout.showJourneyTimes,
            tier = tier,
        )

        if (layout.showJourneyTimes) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            JourneyTimesRow(context, content.primary, arrivalFormatter, tier)
        }

        // Same final render-time check applied to the secondary row independently -- an expired
        // one is simply omitted (the primary row above is unaffected), never shown as 0 min. Never
        // shown in the Small tier -- see DepartureMainContent's own identical rule for why there's
        // no room for it (or its own divider) there.
        val secondary = if (!layout.showSecondary) null else content.secondary?.takeIf { isDepartureCurrent(now, it.departureTime) }
        if (secondary != null) {
            val sectionGap = if (layout.tier == WidgetLayoutTier.LARGE) 6.dp else 4.dp
            Spacer(modifier = GlanceModifier.height(sectionGap))
            WidgetDivider()
            Spacer(modifier = GlanceModifier.height(sectionGap))
            NextJourneyRow(context, secondary, now, tier)
        }
    }
}

/** [primary]'s own composition: one badge per public-transport leg (see [legBadgesOrFallback]),
 * side by side, then either the fixed "Direct" label (a zero-change journey) or the journey's own
 * arrival time plus change count, correctly pluralized ("1 change"/"2 changes") -- see this
 * function's own caller doc for exactly why this wording is driven by [primary]'s own
 * [WidgetJourneyRow.transferCount] while the green "With changes" label below it is driven ONLY by
 * [changesPreference]. */
@Composable
private fun JourneyCompositionRow(
    context: Context,
    primary: WidgetJourneyRow,
    changesPreference: ExactDestinationChangesPreference,
    arrivalFormatter: DateTimeFormatter,
    includeArrivalInComposition: Boolean,
    tier: WidgetSizeTier,
) {
    val badges = primary.legBadgesOrFallback()
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            badges.forEachIndexed { index, badge ->
                LineBadge(badge.lineDesignation, LineBadgeColorMapping.colorFor(badge.transportMode, badge.lineDesignation), tier.badgeSize)
                if (index != badges.lastIndex) Spacer(modifier = GlanceModifier.width(4.dp))
            }
            if (primary.transferCount == 0) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    context.getString(R.string.journey_direct),
                    maxLines = 1,
                    style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
                )
            }
        }
        if (primary.transferCount > 0) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            val text = if (includeArrivalInComposition) {
                context.resources.getQuantityString(
                    R.plurals.widget_journey_arrive_with_changes,
                    primary.transferCount,
                    arrivalFormatter.format(primary.arrivalTime),
                    primary.transferCount,
                )
            } else {
                context.resources.getQuantityString(
                    R.plurals.widget_journey_changes,
                    primary.transferCount,
                    primary.transferCount,
                )
            }
            Text(
                text,
                maxLines = 1,
                style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
            )
        }
        if (changesPreference == ExactDestinationChangesPreference.WITH_CHANGES_ONLY) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                context.getString(R.string.journey_with_changes),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.statusSize, color = ColorProvider(LINE_BADGE_GREEN)),
            )
        }
    }
}

/** Large-only departure/arrival detail, using timestamps already carried on the primary journey. */
@Composable
private fun JourneyTimesRow(
    context: Context,
    primary: WidgetJourneyRow,
    timeFormatter: DateTimeFormatter,
    tier: WidgetSizeTier,
) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Start) {
            Text(
                context.getString(R.string.widget_journey_depart_label),
                maxLines = 1,
                style = TextStyle(fontSize = tier.statusSize, color = onSurfaceVariantColor()),
            )
            Text(
                timeFormatter.format(primary.departureTime),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.secondarySize, color = onBackgroundColor()),
            )
        }
        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.End) {
            Text(
                context.getString(R.string.widget_journey_arrive_label),
                maxLines = 1,
                style = TextStyle(fontSize = tier.statusSize, color = onSurfaceVariantColor()),
            )
            Text(
                timeFormatter.format(primary.arrivalTime),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = tier.secondarySize, color = onBackgroundColor()),
            )
        }
    }
}

/** The second journey row: a two-column "Next"/"Alternative" label and a plain countdown value
 * (reusing [R.string.widget_countdown_minutes_format], the exact same format the primary countdown
 * above uses). Backend-authoritative role decides the label -- NEXT (the same route family's own
 * next departure) reads as a plain continuation of the primary row, while ALTERNATIVE visibly says
 * so, since it's a genuinely different way to travel, not just "another one of these" -- never
 * assumed from list position. */
@Composable
private fun NextJourneyRow(context: Context, secondary: WidgetJourneyRow, now: java.time.Instant, tier: WidgetSizeTier) {
    val secondaryMinutes = countdownMinutes(now, secondary.departureTime)
    val labelRes = if (secondary.role == JourneyRole.ALTERNATIVE) {
        R.string.widget_journey_alternative_label
    } else {
        R.string.widget_journey_next_label
    }
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            context.getString(labelRes),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
        )
        Text(
            context.getString(R.string.widget_countdown_minutes_format, secondaryMinutes),
            maxLines = 1,
            style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
        )
    }
}

/** A thin, full-width hairline separating [JourneyCompositionRow] from [NextJourneyRow] — see the
 * supplied design. [GlanceTheme.colors.outline] rather than a lower-contrast "variant" role:
 * Glance's own [androidx.glance.color.ColorProviders] has no `outlineVariant` accessor (unlike
 * full Material3 [androidx.compose.material3.ColorScheme]), so this reuses the same outline color
 * [StatusFooter]'s own "Scheduled" dot already does — at 1dp height, still reads as a subtle
 * separator, not a heavy rule. */
@Composable
private fun WidgetDivider() {
    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(GlanceTheme.colors.outline)) {}
}

/** [WidgetJourneyRow.legBadges] when non-empty, falling back to a single badge built from
 * [WidgetJourneyRow.lineDesignation]/[WidgetJourneyRow.transportMode] for state persisted by a
 * version predating [WidgetJourneyRow.legBadges] — never zero badges for a journey that plainly
 * has a line. Empty only when [WidgetJourneyRow.lineDesignation] itself is null, matching how a
 * missing header line badge is already handled elsewhere in this file (see [WidgetHeader]'s own
 * `model.lineDesignation?.let { ... }`). `internal`, not `private`, so it's directly unit-testable
 * — see `BlickRoutineWidgetTest`. */
internal fun WidgetJourneyRow.legBadgesOrFallback(): List<WidgetJourneyLegBadge> =
    legBadges.ifEmpty { listOfNotNull(lineDesignation?.let { WidgetJourneyLegBadge(it, transportMode) }) }

/** A clean, left-aligned vertical stack: the big "6 min" countdown, then — outside the Small
 * tier, where there simply isn't room — the following departure's own countdown and
 * the live/scheduled/cancelled status row. The station → direction route is shown once, in
 * [WidgetHeader] next to the line badge, not repeated here (see this function's own comment
 * below). Previously a side-by-side layout (countdown on the left, station/next-departure
 * column on the right); the fully vertical stack instead matches how every other section of
 * this widget (and the rest of the app) presents a line/route/status sequence, and reads less
 * cramped at ordinary widget sizes. */
@Composable
private fun DepartureMainContent(
    context: Context,
    model: RoutineWidgetModel,
    next: WidgetDepartureRow,
    following: WidgetDepartureRow?,
    layout: WidgetLayoutRules,
    tier: WidgetSizeTier,
) {
    Column(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        CountdownText(context, next, tier)
        Spacer(modifier = GlanceModifier.height(if (layout.tier == WidgetLayoutTier.STANDARD) 2.dp else 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            model.lineDesignation?.let { line ->
                LineBadge(line, LineBadgeColorMapping.colorFor(model.transportMode, line), tier.badgeSize)
                Spacer(modifier = GlanceModifier.width(8.dp))
            }
            StatusFooter(context, next, tier)
        }
        if (layout.showSecondary) {
            following?.let { row ->
                val sectionGap = if (layout.tier == WidgetLayoutTier.LARGE) 6.dp else 4.dp
                Spacer(modifier = GlanceModifier.height(sectionGap))
                WidgetDivider()
                Spacer(modifier = GlanceModifier.height(sectionGap))
                NextDepartureRow(context, row, tier)
            }
        }
    }
}

@Composable
private fun NextDepartureRow(context: Context, row: WidgetDepartureRow, tier: WidgetSizeTier) {
    val value = if (row.isCancelled) {
        context.getString(R.string.routine_details_departure_cancelled)
    } else {
        context.getString(R.string.widget_countdown_minutes_format, row.minutesRemaining)
    }
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            context.getString(R.string.widget_journey_next_label),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
        )
        Text(
            value,
            maxLines = 1,
            style = TextStyle(fontSize = tier.secondarySize, color = onSurfaceVariantColor()),
        )
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
