package se.blick.app.data.repository

import se.blick.app.domain.model.DeparturesResult

interface DepartureRepository {
    /** [forecastMinutes], when supplied, requests a longer SL Transport forecast window
     * than the backend's own short default (see `docs/api-contract.md`'s departures
     * endpoint) — used by [LiveDeparturesDirectionOptionsSource] during routine setup, not
     * by the live routine-details/notification polling paths, which both rely on the
     * default window and never pass this. */
    suspend fun getDepartures(siteId: Long, forecastMinutes: Int? = null): DeparturesResult
}
