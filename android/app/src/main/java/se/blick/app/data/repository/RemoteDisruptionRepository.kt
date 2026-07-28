package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.toDomain
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.TransportMode
import javax.inject.Inject

class RemoteDisruptionRepository @Inject constructor(
    private val apiClient: BlickApiClient,
) : DisruptionRepository {
    override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> =
        apiClient.getDisruptions(siteId, lineId, transportMode?.name).toDomain()
}
