package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SiteDto(
    val siteId: Long,
    val name: String,
    val note: String? = null,
    // Nullable: some real SL Transport sites have no coordinates at all (see
    // backend/src/services/upstreamTypes.ts RawSlSiteSchema and docs/api-contract.md §3).
    val lat: Double? = null,
    val lon: Double? = null,
    val stopAreaIds: List<Long> = emptyList(),
)

@Serializable
data class StopSearchResponseDto(
    val query: String,
    val sites: List<SiteDto>,
)
