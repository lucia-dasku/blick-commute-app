package se.blick.app.domain.usecase

import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TripDeviation
import java.time.Instant

/**
 * Display-ready projection of a [se.blick.app.domain.model.Departure]: filtered to one
 * saved routine and countdown-annotated for one specific instant.
 *
 * Deliberately a separate model from [se.blick.app.domain.model.Departure] rather than an
 * extra field bolted onto it — see that class's doc comment on why `minutesRemaining` must
 * never live on the raw domain model: it is only valid at the instant it was computed, and
 * caching it on the shared/raw departure would let it silently go stale between polls.
 */
data class PreparedDeparture(
    val departureId: String,
    val lineDesignation: String,
    val direction: String?,
    val destination: String?,
    val scheduledTime: Instant,
    val expectedTime: Instant?,
    val effectiveTime: Instant,
    val minutesRemaining: Long,
    val isRealTime: Boolean,
    val isCancelled: Boolean,
    val state: String,
    val journeyState: String,
    val predictionState: String?,
    val tripDeviations: List<TripDeviation>,
    /** Backend-authoritative (see [JourneyRole]'s own doc) — populated ONLY by the
     * exact-destination journey path (see
     * [se.blick.app.scheduling.toExactJourneyNotificationProjection]'s own `toPreparedDeparture`);
     * always `null` for a real [se.blick.app.domain.model.Departure]-derived row (the
     * LINE_DIRECTION path), which has no such concept. Exists purely so
     * [se.blick.app.notification.RoutineNotificationMapper] can carry it through into
     * [se.blick.app.notification.NotificationDepartureRow] without this generic, widely-shared
     * type itself gaining any journey-specific behaviour beyond storing the value. */
    val journeyRole: JourneyRole? = null,
)

/**
 * A successful, prepared set of departures as of [fetchedAt]. This is the unit both a
 * live result and a stale fallback are built from — see [LiveDeparturesState.Stale].
 */
data class LiveDeparturesSnapshot(
    val departures: List<PreparedDeparture>,
    val fetchedAt: Instant,
)

/**
 * Explicit states for the live-departures feature, returned by [GetLiveDeparturesUseCase].
 * "No matching departures right now" is deliberately not the same state as a fetch
 * failure — the same empty-vs-failure split already used for direction loading in
 * RoutineCreateViewModel.
 */
sealed interface LiveDeparturesState {

    /** Emitted immediately, before the underlying fetch completes. */
    data object Loading : LiveDeparturesState

    /** A successful fetch with at least one matching upcoming departure. */
    data class Live(val snapshot: LiveDeparturesSnapshot) : LiveDeparturesState

    /** A successful fetch with zero matching upcoming departures — not a failure. */
    data class NoUpcomingDepartures(val fetchedAt: Instant) : LiveDeparturesState

    /** The fetch failed with a connectivity-shaped error (see [java.io.IOException]) and
     * no previous successful data exists to fall back on. */
    data object Offline : LiveDeparturesState

    /**
     * The fetch failed — of any kind — but the caller supplied a previous successful
     * [LiveDeparturesSnapshot], so that snapshot is shown instead of an error.
     *
     * This milestone does not persist this snapshot anywhere: the caller (a future
     * ViewModel) is responsible for holding the last successful result in memory and
     * passing it back in as `previous` on the next call. Durable stale/offline storage is
     * explicitly out of scope here and will be implemented later.
     */
    data class Stale(val snapshot: LiveDeparturesSnapshot) : LiveDeparturesState

    /** The fetch failed and no previous data exists to fall back on. */
    data object Unavailable : LiveDeparturesState
}
