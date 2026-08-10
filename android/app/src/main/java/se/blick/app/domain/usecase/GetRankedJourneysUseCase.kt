package se.blick.app.domain.usecase

import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.TransportMode
import javax.inject.Inject

class GetRankedJourneysUseCase @Inject constructor(private val repository: JourneyRepository) {
    suspend operator fun invoke(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
    ): List<JourneyPlan> = rank(repository.getJourneys(originId, destinationId, allowedTransportModes))

    companion object {
        /** Product-defining rule: final arrival is the primary key. Departure time is only a
         * deterministic tie-breaker and can never make a slower-arriving journey rank first. */
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
