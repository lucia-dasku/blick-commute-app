package se.blick.app.data.repository

import se.blick.app.domain.model.ActiveCommuteSource

data class ActiveCommuteOwnership(
    val source: ActiveCommuteSource,
    val ownerRunId: String,
)

/** Durable ownership for Blick's single global active commute presentation. */
interface ActiveCommuteOwnershipRepository {
    /** Replaces any previous source/run owner before the caller posts active content. */
    suspend fun claim(source: ActiveCommuteSource, ownerRunId: String)

    suspend fun currentOwner(): ActiveCommuteOwnership?

    suspend fun isOwner(source: ActiveCommuteSource, ownerRunId: String): Boolean =
        currentOwner() == ActiveCommuteOwnership(source, ownerRunId)

    /** Removes the lease only when the exact source/run still owns it. */
    suspend fun releaseIfOwner(source: ActiveCommuteSource, ownerRunId: String): Boolean
}
