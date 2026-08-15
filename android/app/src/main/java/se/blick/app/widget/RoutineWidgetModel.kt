package se.blick.app.widget

import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * The widget's top-level rendering state. [NoActiveCommute] is the exact "outside the active
 * window" case (the product doc's "No active commute." requirement) — deliberately a distinct
 * case from [RoutineWidgetContent], since it isn't a departures state at all, it's the absence
 * of any routine currently being tracked by [se.blick.app.scheduling.RoutineActiveWindowWorker].
 */
sealed interface RoutineWidgetUiState {
    data object NoActiveCommute : RoutineWidgetUiState
    data class ActiveRoutine(val model: RoutineWidgetModel) : RoutineWidgetUiState
}

/** Mirrors [se.blick.app.notification.RoutineNotificationModel]'s shape — same routine identity
 * fields, same [RoutineWidgetContent] states as [se.blick.app.notification.RoutineNotificationContent] —
 * since both are produced from the exact same [se.blick.app.domain.usecase.LiveDeparturesState]
 * by [RoutineWidgetMapper], the widget's sibling to [se.blick.app.notification.RoutineNotificationMapper]. */
data class RoutineWidgetModel(
    val routineId: String,
    val routineName: String,
    val stationName: String,
    val directionLabel: String?,
    val content: RoutineWidgetContent,
    /** The routine's own pinned line — unlike [WidgetDepartureRow.lineDesignation] (only
     * present once a departure has actually been fetched), this is available in every
     * [RoutineWidgetContent] state, including [RoutineWidgetContent.Loading] and every other
     * state with no departure data at all, so the header line badge can render identically
     * regardless of content state. Null only for a routine somehow saved without a line
     * (defensive — [se.blick.app.domain.model.CommuteRoutine.lineDesignation] is nullable in
     * the type system even though setup never actually completes without one), in which case
     * no badge is rendered rather than one showing nothing meaningful. */
    val lineDesignation: String? = null,
    /** Drives [LineBadgeColorMapping.colorFor] for the widget's line-number badge, alongside
     * [lineDesignation] — every departure shown for a single routine shares this same mode,
     * since a [se.blick.app.domain.model.CommuteRoutine] tracks exactly one line. Defaults to
     * [TransportMode.UNKNOWN] (resolving to a grey badge) purely so existing positional test
     * construction and decoding an older, pre-this-field persisted
     * [Preferences][androidx.datastore.preferences.core.Preferences] instance both stay safe;
     * every real production call site ([RoutineWidgetMapper]) always passes the routine's own
     * mode explicitly. */
    val transportMode: TransportMode = TransportMode.UNKNOWN,
    /** The highest-priority currently-relevant disruption's header, or null if none was
     * fetched/available — mirrors [se.blick.app.notification.RoutineNotificationModel.disruptionHeadline]
     * exactly (see [RoutineWidgetMapper.map]'s `topDisruption` parameter), but the widget only
     * ever shows the short header, never the longer body text: [BlickRoutineWidget]'s own
     * disruption strip is a small, tap-for-more affordance, not a substitute for the full
     * disruption text Routine Details already shows. Null in every state produced without fresh
     * disruption data in hand (e.g. [RoutineWidgetReconciler]'s reconciliation paths), exactly
     * like [lineDesignation] being null is handled — never shown, rather than shown empty. */
    val disruptionHeadline: String? = null,
)

/** Mirrors [se.blick.app.notification.RoutineNotificationContent] one-for-one, except [Live] and
 * [Stale] expose a fixed "next"/"following" pair (the product doc's exact wording) rather than a
 * list — at most two departures are ever shown either way, so this is the same data, just typed
 * to match the requirement precisely. A cancelled departure is represented via
 * [WidgetDepartureRow.isCancelled] on an otherwise-ordinary row, not a separate top-level case —
 * exactly how the notification models it too. */
sealed interface RoutineWidgetContent {
    data object Loading : RoutineWidgetContent
    data class Live(val next: WidgetDepartureRow, val following: WidgetDepartureRow?) : RoutineWidgetContent

    /** Unlike an expired [Live] snapshot (re-reported as [NoUpcomingDepartures] — see
     * [RoutineWidgetMapper]), a [Stale] snapshot stays [Stale] even once every one of its own
     * departures has since expired too ([next]/[following] both null in that case) — it must
     * keep communicating "the last refresh failed," never silently claim the data is current. */
    data class Stale(val next: WidgetDepartureRow?, val following: WidgetDepartureRow?, val lastCheckedAt: Instant) : RoutineWidgetContent
    data class NoUpcomingDepartures(val lastCheckedAt: Instant) : RoutineWidgetContent
    data object Offline : RoutineWidgetContent
    data object Unavailable : RoutineWidgetContent

    /**
     * No [se.blick.app.domain.usecase.LiveDeparturesState] counterpart — this is a widget-only
     * case, distinct from [Unavailable] (a departure *fetch* failure). It means the routine's
     * active window is genuinely open right now, but [se.blick.app.scheduling.RoutineActiveWindowWorker]
     * never even started (or stopped) its departure-fetching loop because notifications are
     * unavailable (permission missing, app disabled, or the Blick channel disabled) — see that
     * worker's own doc. Live widget updates currently depend on notification availability,
     * because the worker's loop (the widget's only data source — see [RoutineWidgetUpdater]) is
     * itself gated on it; this state exists specifically so the widget says so honestly instead
     * of being left showing [Loading] forever.
     */
    data object NotificationsUnavailable : RoutineWidgetContent

    /** [primary] is always shown in the main countdown slot; [secondary], when present, is the
     * second departure line below it — semantically NEXT (the same route family's own next
     * departure) or ALTERNATIVE (a different, genuinely useful route), never assumed to be one
     * or the other from its mere presence here. Named `primary`/`secondary` rather than the
     * previous `fastest`/`alternative`: [secondary] is frequently NEXT, not an alternative at
     * all, and calling it that internally regardless of its real [WidgetJourneyRow.role] was
     * itself misleading. See [WidgetJourneyRow.role] — backend-authoritative, carried alongside
     * every other field and never re-derived from list position.
     *
     * [changesPreference] is the routine's own persisted [ExactDestinationChangesPreference] at
     * the moment this content was written — the single source of truth [BlickRoutineWidget]
     * switches its Direct/Both/With-changes layout on. Deliberately carried here rather than
     * re-derived from [primary]'s own [WidgetJourneyRow.transferCount]: a
     * [ExactDestinationChangesPreference.BOTH] routine showing a with-changes journey and a
     * [ExactDestinationChangesPreference.WITH_CHANGES_ONLY] one showing the very same journey
     * render IDENTICALLY except for the small green "With changes" label — that distinction only
     * exists in the stored preference, never in the journey data itself. Defaults to
     * [ExactDestinationChangesPreference.BOTH] (the pre-existing, unfiltered layout) so state
     * persisted by a version predating this field decodes safely — see
     * [se.blick.app.domain.model.toExactDestinationChangesPreference]'s own doc, which
     * [RoutineWidgetPreferences.kt]'s decode reuses directly. */
    data class Journeys(
        val primary: WidgetJourneyRow,
        val secondary: WidgetJourneyRow?,
        val changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
    ) : RoutineWidgetContent
}

/** One leg's own line badge — [lineDesignation] is never null here (unlike
 * [WidgetJourneyRow.lineDesignation], the first leg's own designation kept for the header badge):
 * [RoutineWidgetJourneysMapper] only ever includes a leg that has one, silently dropping a walking
 * transfer leg (which has none) rather than rendering an empty/meaningless badge for it — see that
 * mapper's own doc. */
data class WidgetJourneyLegBadge(val lineDesignation: String, val transportMode: TransportMode)

data class WidgetJourneyRow(
    val lineDesignation: String?,
    val transportMode: TransportMode,
    val departureTime: Instant,
    val arrivalTime: Instant,
    val transferCount: Int,
    val isRealtime: Boolean,
    /** Backend-authoritative (see [se.blick.app.domain.model.JourneyRole]'s own doc) — never
     * inferred from this row's position within [RoutineWidgetContent.Journeys]. Travels
     * unchanged through [resolveEffectiveModel]'s own render-time promotion (a NEXT or
     * ALTERNATIVE row promoted into the [RoutineWidgetContent.Journeys.primary] slot keeps its
     * own real role rather than being silently treated as PRIMARY), so a consumer that cares
     * (e.g. debugging, or a future primary-slot label) always sees this journey's genuine
     * backend meaning. */
    val role: JourneyRole,
    /** One badge per public-transport leg, in journey order — e.g. `["14", "40"]` for a
     * one-change journey — for the Both/With-changes layouts' own "relevant line badge(s)" row
     * (see [BlickRoutineWidget]'s own doc). Empty for state persisted by a version predating this
     * field; render-time falls back to a single badge built from [lineDesignation]/[transportMode]
     * in that case — see [legBadgesOrFallback]'s own doc — never zero badges for a journey that
     * plainly has a line. */
    val legBadges: List<WidgetJourneyLegBadge> = emptyList(),
)

data class WidgetDepartureRow(
    val lineDesignation: String,
    val destinationLabel: String?,
    val minutesRemaining: Long,
    val isRealTime: Boolean,
    val isCancelled: Boolean,
)
