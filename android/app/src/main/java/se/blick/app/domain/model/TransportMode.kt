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

fun String.toTransportMode(): TransportMode =
    runCatching { TransportMode.valueOf(this) }.getOrDefault(TransportMode.UNKNOWN)
