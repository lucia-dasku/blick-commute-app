package se.blick.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.data.remote.dto.SuccessEnvelopeDto

/**
 * Retrofit service definition. Every response is wrapped in the success envelope
 * (docs/api-contract.md §2); error envelopes are surfaced as HTTP error codes and
 * handled by unwrapping/mapping at the [BlickApiClient] implementation, not here.
 */
interface RetrofitBlickApiService {
    @GET("api/v1/stops/search")
    suspend fun searchStops(@Query("query") query: String): SuccessEnvelopeDto<StopSearchResponseDto>

    @GET("api/v1/departures")
    suspend fun getDepartures(@Query("siteId") siteId: Long): SuccessEnvelopeDto<DeparturesResponseDto>

    @GET("api/v1/disruptions")
    suspend fun getDisruptions(
        @Query("siteId") siteId: Long,
        @Query("lineId") lineId: Long?,
        @Query("transportMode") transportMode: String?,
    ): SuccessEnvelopeDto<DisruptionsResponseDto>
}

class RetrofitBlickApiClient(
    private val service: RetrofitBlickApiService,
) : BlickApiClient {
    override suspend fun searchStops(query: String) = service.searchStops(query).data
    override suspend fun getDepartures(siteId: Long) = service.getDepartures(siteId).data
    override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?) =
        service.getDisruptions(siteId, lineId, transportMode).data
}
