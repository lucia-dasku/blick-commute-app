package se.blick.app.domain.model

/**
 * Mirrors the backend's `TransportMode` union (see backend/src/models/common.ts and
 * docs/api-contract.md). Kept open-ended (an [UNKNOWN] fallback) rather than a strict
 * enum that would fail to deserialize if SL adds a mode this app doesn't know about yet.
 */
enum class TransportMode {
    BUS,
    METRO,
    TRAIN,
    TRAM,
    SHIP,
    FERRY,
    TAXI,
    UNKNOWN,
}

/** Modes users can include in exact-destination journey planning. Walking transfer legs are
 * always allowed and therefore deliberately not represented here. */
val JOURNEY_TRANSPORT_MODE_OPTIONS: List<TransportMode> = listOf(
    TransportMode.METRO,
    TransportMode.TRAIN,
    TransportMode.BUS,
    TransportMode.TRAM,
    TransportMode.FERRY,
)

val DEFAULT_JOURNEY_TRANSPORT_MODES: Set<TransportMode> = JOURNEY_TRANSPORT_MODE_OPTIONS.toSet()

fun String.toTransportMode(): TransportMode =
    runCatching { TransportMode.valueOf(this) }.getOrDefault(TransportMode.UNKNOWN)
