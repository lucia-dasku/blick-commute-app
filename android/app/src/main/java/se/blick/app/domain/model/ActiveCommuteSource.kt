package se.blick.app.domain.model

/**
 * Explicit identity of the saved intention behind Blick's single active commute surface.
 * Source type is never inferred from an identifier or repository lookup.
 */
sealed interface ActiveCommuteSource {
    val id: String

    data class Routine(override val id: String) : ActiveCommuteSource
    data class OneTimeEvent(override val id: String) : ActiveCommuteSource
}

enum class ActiveCommuteSourceType {
    ROUTINE,
    ONE_TIME_EVENT,
}

val ActiveCommuteSource.type: ActiveCommuteSourceType
    get() = when (this) {
        is ActiveCommuteSource.Routine -> ActiveCommuteSourceType.ROUTINE
        is ActiveCommuteSource.OneTimeEvent -> ActiveCommuteSourceType.ONE_TIME_EVENT
    }

fun activeCommuteSource(type: ActiveCommuteSourceType, id: String): ActiveCommuteSource = when (type) {
    ActiveCommuteSourceType.ROUTINE -> ActiveCommuteSource.Routine(id)
    ActiveCommuteSourceType.ONE_TIME_EVENT -> ActiveCommuteSource.OneTimeEvent(id)
}
