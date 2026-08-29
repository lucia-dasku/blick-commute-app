package se.blick.app.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import se.blick.app.data.repository.DisruptionRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.TransportMode
import javax.inject.Inject

/**
 * Explicit states for one routine's disruptions, mirroring [LiveDeparturesState]'s own
 * Loading/empty-is-not-a-failure/failure split (see that type's doc), minus a stale-fallback
 * variant — nothing here persists a previous successful result the way
 * [se.blick.app.data.repository.StaleSnapshotRepository] does for departures; a failed fetch
 * is always reported as [Unavailable].
 */
sealed interface DisruptionsState {
    /** Emitted immediately, before the underlying fetch completes. */
    data object Loading : DisruptionsState

    /** A successful fetch with at least one currently-relevant disruption, already
     * de-duplicated and priority-ordered by [se.blick.app.domain.model.relevantDisruptions]
     * (applied inside [se.blick.app.data.repository.RemoteDisruptionRepository]). */
    data class Loaded(val disruptions: List<Disruption>) : DisruptionsState

    /** A successful fetch with zero currently-relevant disruptions — not a failure. */
    data object NoDisruptions : DisruptionsState

    /** The fetch failed, of any kind. Disruptions are a purely additive, best-effort signal
     * (see the class doc on [GetDisruptionsUseCase]) — a raw exception is never surfaced,
     * and there is deliberately no connectivity-vs-other split the way
     * [LiveDeparturesState.Offline]/[LiveDeparturesState.Unavailable] have for departures,
     * since nothing about a disruptions failure changes what the caller should do about it. */
    data object Unavailable : DisruptionsState
}

/**
 * Fetches the current disruptions for one saved [CommuteRoutine]'s site/line/mode, delegating
 * all caching, de-duplication, expiry filtering, and priority ordering to
 * [se.blick.app.data.repository.DisruptionRepository] (in production, backed by the shared
 * [se.blick.app.data.remote.cache.DisruptionCache] — see that class's own doc for why one
 * cache shared between [se.blick.app.scheduling.RoutineActiveWindowWorker] and
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel] is what keeps concurrent
 * requests for the same routine deduplicated while respecting the backend snapshot's own source
 * age).
 *
 * A failure here is always isolated to [DisruptionsState.Unavailable] — this use case never
 * throws (besides a real [CancellationException], which always propagates unconverted, same
 * rule as [GetLiveDeparturesUseCase]) — because disruptions are a purely additive signal
 * alongside departures: a caller (the worker, in particular) must be able to treat a
 * disruptions failure as "nothing to show," never as a reason to fail, delay, or replace an
 * otherwise-successful departures fetch.
 */
class GetDisruptionsUseCase @Inject constructor(
    private val disruptionRepository: DisruptionRepository,
) {
    suspend operator fun invoke(routine: CommuteRoutine): Flow<DisruptionsState> = flow {
        emit(DisruptionsState.Loading)
        emit(fetch(routine))
    }

    private suspend fun fetch(routine: CommuteRoutine): DisruptionsState = try {
        // TransportMode.UNKNOWN is a client-side-only fallback value (see that enum's own
        // doc) -- the backend's closed set of filterable modes does not include it, so it is
        // never sent as a query filter; omitting it here just means the line/mode-based
        // narrowing of line-only disruptions doesn't apply, not a validation error.
        val transportMode = routine.transportMode.takeIf { it != TransportMode.UNKNOWN }
        val disruptions = disruptionRepository.getDisruptions(routine.siteId, routine.lineId, transportMode)
        if (disruptions.isEmpty()) DisruptionsState.NoDisruptions else DisruptionsState.Loaded(disruptions)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DisruptionsState.Unavailable
    }
}
