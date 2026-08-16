package se.blick.app.data.remote

import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.data.remote.dto.PurchaseVerificationResponseDto
import se.blick.app.data.remote.dto.JourneyLocationSearchDto
import se.blick.app.data.remote.dto.JourneysResponseDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceRequestDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceResponseDto

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
    /** [searchUntil] is an ISO-8601 instant (see [java.time.Instant.toString]) bounding how far
     * forward the backend's own targeted NEXT/ALTERNATIVE acquisition may search — see
     * [se.blick.app.domain.usecase.GetRankedJourneysUseCase]'s own doc. Null when the caller has
     * no genuine routine-occurrence boundary to offer; the backend then answers from its initial
     * acquisition alone rather than inventing a search horizon. [changesPreference] is one of
     * [se.blick.app.domain.model.ExactDestinationChangesPreference]'s own names
     * ("DIRECT_ONLY"/"BOTH"/"WITH_CHANGES_ONLY"), defaulting to "BOTH" — see
     * `backend/src/routes/journeys.ts`'s own `changesPreference` query parameter doc. */
    suspend fun getJourneys(
        originId: String,
        destinationId: String,
        transportModes: String,
        searchUntil: String? = null,
        changesPreference: String = "BOTH",
    ): JourneysResponseDto =
        throw UnsupportedOperationException("Journey search is not implemented by this test client")

    /** See `backend/src/routes/journeyDisruptions.ts`'s own doc — the backend's single
     * authoritative disruption-relevance resolver. */
    suspend fun getJourneyDisruptionRelevance(
        request: JourneyDisruptionRelevanceRequestDto,
    ): JourneyDisruptionRelevanceResponseDto =
        throw UnsupportedOperationException("Journey disruption relevance lookup is not implemented by this test client")
}
