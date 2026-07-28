package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.toDomain
import se.blick.app.domain.model.Site
import javax.inject.Inject

class RemoteStopRepository @Inject constructor(
    private val apiClient: BlickApiClient,
) : StopRepository {
    override suspend fun searchStops(query: String): List<Site> =
        apiClient.searchStops(query).sites.map { it.toDomain() }
}
