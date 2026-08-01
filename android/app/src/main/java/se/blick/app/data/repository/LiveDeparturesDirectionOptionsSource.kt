package se.blick.app.data.repository

import javax.inject.Inject

/** The real, empirically-confirmed upper bound SL Transport's own `forecast` query
 * parameter accepts (see `docs/api-contract.md`'s departures endpoint) — values above this
 * silently return zero departures rather than an error, so this is not a guess. Requesting
 * the maximum during setup surfaces lines/directions running anytime in the next 20 hours,
 * not just those running in the next ~60 minutes (the backend's default when this parameter
 * is omitted, which every other caller of [DepartureRepository.getDepartures] still uses). */
internal const val DIRECTION_DISCOVERY_FORECAST_MINUTES = 1200

class LiveDeparturesDirectionOptionsSource @Inject constructor(
    private val departureRepository: DepartureRepository,
) : DirectionOptionsSource {

    override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> {
        val result = departureRepository.getDepartures(siteId, DIRECTION_DISCOVERY_FORECAST_MINUTES)
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
