package se.blick.app.data.repository

import javax.inject.Inject

class LiveDeparturesDirectionOptionsSource @Inject constructor(
    private val departureRepository: DepartureRepository,
) : DirectionOptionsSource {

    override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> {
        val result = departureRepository.getDepartures(siteId)
        return result.departures
            .map { departure ->
                DirectionOption(
                    lineId = departure.line.id,
                    lineDesignation = departure.line.designation,
                    transportMode = departure.line.transportMode,
                    directionCode = departure.directionCode,
                    destinationLabel = departure.destination,
                )
            }
            .distinctBy { Triple(it.lineId, it.directionCode, it.transportMode) }
    }
}
