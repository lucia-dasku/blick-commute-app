package se.blick.app.data.remote

import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto

/**
 * Talks to the Blick backend only — never directly to SL Transport/SL Deviations
 * (see docs/api-contract.md §1). Kept as an interface so a fake/in-memory implementation
 * can back ViewModel tests without a network dependency.
 */
interface BlickApiClient {
    suspend fun searchStops(query: String): StopSearchResponseDto
    suspend fun getDepartures(siteId: Long): DeparturesResponseDto
    suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto
}
