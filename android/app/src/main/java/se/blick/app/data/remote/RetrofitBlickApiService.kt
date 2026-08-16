package se.blick.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Body
import retrofit2.http.POST
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.data.remote.dto.SuccessEnvelopeDto
import se.blick.app.data.remote.dto.PurchaseVerificationRequestDto
import se.blick.app.data.remote.dto.PurchaseVerificationResponseDto
import se.blick.app.data.remote.dto.JourneyLocationSearchDto
import se.blick.app.data.remote.dto.JourneysResponseDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceRequestDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceResponseDto

/**
 * Retrofit service definition. Every response is wrapped in the success envelope
 * (docs/api-contract.md §2); error envelopes are surfaced as HTTP error codes and
 * handled by unwrapping/mapping at the [BlickApiClient] implementation, not here.
 */
interface RetrofitBlickApiService {
    @GET("api/v1/stops/search")
    suspend fun searchStops(@Query("query") query: String): SuccessEnvelopeDto<StopSearchResponseDto>

    @GET("api/v1/departures")
    suspend fun getDepartures(
        @Query("siteId") siteId: Long,
        @Query("forecast") forecastMinutes: Int?,
    ): SuccessEnvelopeDto<DeparturesResponseDto>

    @GET("api/v1/disruptions")
    suspend fun getDisruptions(
        @Query("siteId") siteId: Long,
        @Query("lineId") lineId: Long?,
        @Query("transportMode") transportMode: String?,
    ): SuccessEnvelopeDto<DisruptionsResponseDto>

    @POST("api/v1/billing/verify")
    suspend fun verifyPurchase(
        @Body request: PurchaseVerificationRequestDto,
    ): SuccessEnvelopeDto<PurchaseVerificationResponseDto>

    @GET("api/v1/journeys/locations/search")
    suspend fun searchJourneyLocations(@Query("query") query: String): SuccessEnvelopeDto<JourneyLocationSearchDto>

    @GET("api/v1/journeys")
    suspend fun getJourneys(
        @Query("originId") originId: String,
        @Query("destinationId") destinationId: String,
        @Query("transportModes") transportModes: String,
        @Query("searchUntil") searchUntil: String?,
        @Query("changesPreference") changesPreference: String,
    ): SuccessEnvelopeDto<JourneysResponseDto>

    /** See `backend/src/routes/journeyDisruptions.ts`'s own doc — a POST (not GET) specifically
     * because [request] carries an arbitrary-length list of Journey Planner notices, not just a
     * few scalar query parameters. The response is the backend's own fully resolved, deduplicated
     * disruption list — this app performs no relevance inference of its own. */
    @POST("api/v1/journeys/disruptions")
    suspend fun getJourneyDisruptionRelevance(
        @Body request: JourneyDisruptionRelevanceRequestDto,
    ): SuccessEnvelopeDto<JourneyDisruptionRelevanceResponseDto>
}

class RetrofitBlickApiClient(
    private val service: RetrofitBlickApiService,
) : BlickApiClient {
    override suspend fun searchStops(query: String) = service.searchStops(query).data
    override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?) =
        service.getDepartures(siteId, forecastMinutes).data
    override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?) =
        service.getDisruptions(siteId, lineId, transportMode).data
    override suspend fun verifyPurchase(productId: String, purchaseToken: String) =
        service.verifyPurchase(PurchaseVerificationRequestDto(productId, purchaseToken)).data
    override suspend fun searchJourneyLocations(query: String) = service.searchJourneyLocations(query).data
    override suspend fun getJourneys(originId: String, destinationId: String, transportModes: String, searchUntil: String?, changesPreference: String) =
        service.getJourneys(originId, destinationId, transportModes, searchUntil, changesPreference).data
    override suspend fun getJourneyDisruptionRelevance(request: JourneyDisruptionRelevanceRequestDto) =
        service.getJourneyDisruptionRelevance(request).data
}
