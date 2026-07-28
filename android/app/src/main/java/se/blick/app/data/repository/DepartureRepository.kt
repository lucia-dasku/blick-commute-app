package se.blick.app.data.repository

import se.blick.app.domain.model.DeparturesResult

interface DepartureRepository {
    suspend fun getDepartures(siteId: Long): DeparturesResult
}
