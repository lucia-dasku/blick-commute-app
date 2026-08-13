package se.blick.app.domain.usecase

import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import javax.inject.Inject

class GetRankedJourneysUseCase @Inject constructor(
    private val repository: JourneyRepository,
    private val clock: Clock,
) {
    /**
     * `now` is captured AFTER [repository.getJourneys] returns, never before it's called: the
     * network round-trip itself can take long enough that a journey departing while the request
     * is in flight would otherwise still read as "upcoming" against a now-stale pre-request
     * timestamp — e.g. the request starts at 22:11:59.8, the transport departs at 22:12:00, and
     * the response only arrives at 22:12:00.5; comparing against a `now` read at 22:11:59.8 would
     * wrongly let that journey survive. Filtering (removing anything no longer [isCurrentJourney]
     * at that post-response `now`) happens before ranking, exactly like the backend's own
     * equivalent filter (see backend/src/routes/journeys.ts) — a stale cached response or a
     * future backend regression must never be able to resurface an already-departed or
     * over-the-change-limit journey as "fastest"/"alternative" on this side either.
     */
    suspend operator fun invoke(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
    ): List<JourneyPlan> {
        val journeys = repository.getJourneys(originId, destinationId, allowedTransportModes)
        val now = clock.instant()
        return rank(journeys.filterCurrentJourneys(now))
    }

    companion object {
        /** Product-defining rule: final arrival is the primary key. Departure time is only a
         * deterministic tie-breaker and can never make a slower-arriving journey rank first.
         * Assumes its input is already eligible (not expired, not over the change limit — see
         * [invoke]'s own defensive filter) and performs no filtering of its own, so it stays a
         * plain, pure ranking function callers (and existing tests) can exercise directly. */
        fun rank(journeys: List<JourneyPlan>): List<JourneyPlan> {
            val ranked = journeys.sortedWith(compareBy<JourneyPlan> { it.arrivalTime }.thenBy { it.departureTime })
            val fastest = ranked.firstOrNull() ?: return emptyList()
            val fastestModes = fastest.publicTransportModes()
            val alternative = ranked.drop(1).firstOrNull { it.publicTransportModes() != fastestModes }
            return listOfNotNull(fastest, alternative)
        }

        private fun JourneyPlan.publicTransportModes(): Set<TransportMode> =
            legs.map { it.transportMode }.filter { it != TransportMode.UNKNOWN }.toSet()
    }
}
