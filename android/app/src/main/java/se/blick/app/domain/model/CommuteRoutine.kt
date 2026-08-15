package se.blick.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDate
import java.util.UUID

enum class RoutineType { LINE_DIRECTION, EXACT_DESTINATION }

/**
 * An exact-destination routine's persisted choice of which exact-destination journeys are
 * eligible at all — [DIRECT_ONLY] (zero-change journeys only), [BOTH] (the pre-existing,
 * unfiltered behavior — every eligible journey within the backend's own MAX_CHANGES ceiling),
 * or [WITH_CHANGES_ONLY] (journeys requiring at least one change only). Meaningless for a
 * [RoutineType.LINE_DIRECTION] routine, which has no concept of "changes" at all.
 *
 * The single source of truth for which of [se.blick.app.widget.BlickRoutineWidget]'s three
 * exact-destination layouts renders (see that file's own doc) — never inferred from a fetched
 * journey's own [JourneyPlan.transferCount], which cannot by itself distinguish [BOTH] (showing
 * a with-changes journey because that's what the backend ranked highest) from
 * [WITH_CHANGES_ONLY] (showing one because direct journeys are not even eligible). Threaded
 * unchanged from this field, through [se.blick.app.domain.usecase.GetRankedJourneysUseCase] and
 * `backend/src/routes/journeys.ts`'s own `changesPreference` query parameter (see
 * `backend/src/services/candidateCollector.ts`'s own `JourneyChangesPreference`), to the
 * backend's candidate pool itself — PRIMARY/NEXT/ALTERNATIVE are always selected from an
 * already-preference-narrowed pool, never re-ranked or re-filtered independently on Android
 * (see that use case's own doc).
 */
enum class ExactDestinationChangesPreference { DIRECT_ONLY, BOTH, WITH_CHANGES_ONLY }

/** Defensive, fail-safe parse of a persisted/serialized preference value — an unrecognized or
 * absent value (a routine saved before this field existed, or datastore corruption) defaults to
 * [ExactDestinationChangesPreference.BOTH], the pre-existing unfiltered behavior, exactly like
 * [toTransportMode]'s own [TransportMode.UNKNOWN] fallback and [allowedJourneyTransportModes]'s
 * own [DEFAULT_JOURNEY_TRANSPORT_MODES] fallback — never a decode failure. */
fun String?.toExactDestinationChangesPreference(): ExactDestinationChangesPreference =
    this?.let { runCatching { ExactDestinationChangesPreference.valueOf(it) }.getOrNull() }
        ?: ExactDestinationChangesPreference.BOTH

/**
 * A saved commute routine. Deliberately platform-neutral identity fields
 * (siteId/lineId/transportMode/directionCode) per docs/api-contract.md §10 — destination
 * text is display-only and is never the sole identity for a direction, since the live
 * departures feed that currently supplies direction options only reflects routes running
 * within its current forecast window (see [se.blick.app.data.repository.DirectionOptionsSource]).
 *
 * The Room-backed schema (see data/local/room/RoutineEntity.kt) supports any number of
 * these, even though the first-version UI only offers creating one at a time.
 */
data class CommuteRoutine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val siteId: Long,
    val siteName: String,
    val transportMode: TransportMode,
    val lineId: Long?,
    val lineDesignation: String?,
    val directionCode: Int?,
    val destinationLabel: String?,
    val activeDays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val enabled: Boolean = true,
    val pausedDate: LocalDate? = null,
    val type: RoutineType = RoutineType.LINE_DIRECTION,
    /** SL Journey Planner global identifiers. They are intentionally separate from SL
     * Transport's numeric site id and from the display-only direction label. */
    val journeyOriginId: String? = null,
    val journeyOriginName: String? = null,
    val journeyDestinationId: String? = null,
    val journeyDestinationName: String? = null,
    /** Public-transport modes SL may use when planning an exact-destination journey. */
    val allowedJourneyTransportModes: Set<TransportMode> = DEFAULT_JOURNEY_TRANSPORT_MODES,
    /** See [ExactDestinationChangesPreference]'s own doc. Defaults to [ExactDestinationChangesPreference.BOTH]
     * — every existing exact-destination routine (saved before this field existed) and every
     * [RoutineType.LINE_DIRECTION] routine (which never reads this field at all) is therefore
     * unaffected by this field's mere existence. */
    val changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
)
