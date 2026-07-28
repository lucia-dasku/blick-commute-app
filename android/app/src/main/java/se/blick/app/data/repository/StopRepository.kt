package se.blick.app.data.repository

import se.blick.app.domain.model.Site

/** Backs the stop-search step of routine setup (`GET /api/v1/stops/search`, see docs/api-contract.md §3). */
interface StopRepository {
    suspend fun searchStops(query: String): List<Site>
}
