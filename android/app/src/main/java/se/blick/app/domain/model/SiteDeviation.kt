package se.blick.app.domain.model

/**
 * Shared with [Disruption.affectedStopAreas] — this is the one survivor of the older,
 * now-removed embedded per-departure "site deviation" domain model (`SiteDeviation`,
 * `SiteDeviationLineRef`, `SiteDeviationStopPointRef`), superseded by the standalone SL
 * Deviations disruption flow. Kept because the standalone [Disruption] model reuses this
 * exact shape for its own affected-stop-areas list.
 */
data class SiteDeviationStopAreaRef(val id: Long, val name: String, val type: String?)
