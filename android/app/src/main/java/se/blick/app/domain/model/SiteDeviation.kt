package se.blick.app.domain.model

data class SiteDeviationStopAreaRef(val id: Long, val name: String, val type: String?)
data class SiteDeviationStopPointRef(val id: Long, val name: String)
data class SiteDeviationLineRef(val id: Long, val designation: String, val transportMode: TransportMode?)

data class SiteDeviation(
    val id: Long,
    val importanceLevel: Int,
    val message: String,
    val affectedStopAreas: List<SiteDeviationStopAreaRef>,
    val affectedStopPoints: List<SiteDeviationStopPointRef>,
    val affectedLines: List<SiteDeviationLineRef>,
)
