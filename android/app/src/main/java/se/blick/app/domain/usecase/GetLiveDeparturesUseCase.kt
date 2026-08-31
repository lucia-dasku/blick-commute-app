package se.blick.app.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.domain.model.CommuteRoutine
import java.io.IOException
import java.time.Clock
import javax.inject.Inject

/**
 * Fetches, filters, and prepares the next relevant departures for a single saved
 * [CommuteRoutine].
 *
 * Always emits [LiveDeparturesState.Loading] first, then exactly one terminal state —
 * this makes "loading is represented before the result" directly assertable in tests
 * without any ViewModel or UI involved, and gives future callers (the live-preview screen,
 * a notification refresh worker) a single, simple contract to collect.
 *
 * This milestone intentionally does not persist anything. If a caller wants stale-data
 * fallback on a failed refresh, it must hold its own last-successful [LiveDeparturesSnapshot]
 * in memory and pass it back in as [previous]; a future milestone may move that
 * responsibility into a persistent cache (see [LiveDeparturesState.Stale]'s doc comment).
 */
class GetLiveDeparturesUseCase @Inject constructor(
    private val departureRepository: DepartureRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        routine: CommuteRoutine,
        previous: LiveDeparturesSnapshot? = null,
        maxDepartures: Int = DEFAULT_MAX_DEPARTURES,
    ): Flow<LiveDeparturesState> {
        require(maxDepartures > 0) { "maxDepartures must be positive" }
        return flow {
            emit(LiveDeparturesState.Loading)
            emit(fetch(routine, previous, maxDepartures))
        }
    }

    private suspend fun fetch(
        routine: CommuteRoutine,
        previous: LiveDeparturesSnapshot?,
        maxDepartures: Int,
    ): LiveDeparturesState {
        val now = clock.instant()
        return try {
            val result = departureRepository.getDepartures(routine.siteId)
            val prepared = LiveDeparturesProcessor.prepare(result, routine, now, maxDepartures)
            if (prepared.isEmpty()) {
                LiveDeparturesState.NoUpcomingDepartures(fetchedAt = result.fetchedAt)
            } else {
                LiveDeparturesState.Live(LiveDeparturesSnapshot(prepared, result.fetchedAt))
            }
        } catch (e: CancellationException) {
            // Never convert a real coroutine cancellation into a failure state — it must
            // propagate so structured concurrency (and the caller's own scope) behaves
            // correctly. Same rule already enforced throughout RoutineCreateViewModel.
            throw e
        } catch (e: IOException) {
            // Connectivity-shaped failure (no route to host, timeout, DNS failure, etc.)
            previous?.let { LiveDeparturesState.Stale(it) } ?: LiveDeparturesState.Offline
        } catch (e: Exception) {
            // Any other failure (a malformed/unexpected backend response, for example).
            // Raw exception details are intentionally not carried into the state — see
            // LiveDeparturesState's doc comments; only the caller-visible previous snapshot,
            // if any, and the fact that this attempt failed are exposed.
            previous?.let { LiveDeparturesState.Stale(it) } ?: LiveDeparturesState.Unavailable
        }
    }
}
