package se.blick.app.domain.model

/** Mirrors `Site` in docs/api-contract.md §3 ("stops/search"). */
data class Site(
    val siteId: Long,
    val name: String,
    val note: String?,
    // Nullable: some real SL Transport sites have no coordinates at all.
    val lat: Double?,
    val lon: Double?,
    val stopAreaIds: List<Long>,
)
