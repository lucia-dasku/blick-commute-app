package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.toDomain
import se.blick.app.domain.model.DeparturesResult
import javax.inject.Inject

class RemoteDepartureRepository @Inject constructor(
    private val apiClient: BlickApiClient,
) : DepartureRepository {
    override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult =
        apiClient.getDepartures(siteId, forecastMinutes).toDomain()
}
