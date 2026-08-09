package se.blick.app.domain.model

/**
 * Blick's own closed set of passenger-facing disruption effects — a deterministic
 * classification the backend derives locally from the SL disruption message (see
 * backend/src/normalize/classifyDisruptionEffect.ts and docs/api-contract.md, "Disruption
 * effect classification"), never an SL-provided value. Used only to pick the short, localized
 * summary line the ongoing-commute notification shows (e.g. "Delays") — Routine Details keeps
 * showing SL's own real header/details untouched regardless of this value.
 *
 * Unlike [TransportMode], every possible wire value is produced by the backend's own
 * classifier rather than passed through from an unpredictable upstream field — but the DTO
 * boundary still treats it as a tolerant, closed-with-fallback mapping (see
 * [se.blick.app.data.remote.dto.DisruptionDto.effect] and [toDisruptionEffect]) so that an
 * older Android build talking to a newer backend, or a newer Android build talking to an
 * older/misbehaving backend, never fails to parse a disruption over this one field.
 * [DISRUPTION] is the fallback for both "missing" and "not one of the values this build knows
 * about" — a generic label is always safe, never a confidently wrong one.
 */
enum class DisruptionEffect {
    DELAYS,
    NO_SERVICE,
    REDUCED_SERVICE,
    ROUTE_CHANGE,
    STOP_CHANGE,
    REPLACEMENT_SERVICE,
    STATION_ACCESS,
    ACCESSIBILITY_ISSUE,
    DISRUPTION,
}

fun String.toDisruptionEffect(): DisruptionEffect =
    runCatching { DisruptionEffect.valueOf(this) }.getOrDefault(DisruptionEffect.DISRUPTION)
