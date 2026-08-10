package se.blick.app.data.remote

import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.data.remote.dto.PurchaseVerificationResponseDto
import se.blick.app.data.remote.dto.JourneyLocationSearchDto
import se.blick.app.data.remote.dto.JourneysResponseDto

/**
 * Talks to the Blick backend only — never directly to SL Transport/SL Deviations
 * (see docs/api-contract.md §1). Kept as an interface so a fake/in-memory implementation
 * can back ViewModel tests without a network dependency.
 */
interface BlickApiClient {
    suspend fun searchStops(query: String): StopSearchResponseDto
    suspend fun getDepartures(siteId: Long, forecastMinutes: Int? = null): DeparturesResponseDto
    suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto
    suspend fun verifyPurchase(productId: String, purchaseToken: String): PurchaseVerificationResponseDto =
        throw UnsupportedOperationException("Purchase verification is not implemented by this test client")
    suspend fun searchJourneyLocations(query: String): JourneyLocationSearchDto =
        throw UnsupportedOperationException("Journey location search is not implemented by this test client")
    suspend fun getJourneys(originId: String, destinationId: String, transportModes: String): JourneysResponseDto =
        throw UnsupportedOperationException("Journey search is not implemented by this test client")
}
