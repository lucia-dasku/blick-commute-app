package se.blick.app.data.repository

import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.TransportMode

interface DisruptionRepository {
    suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption>
}
