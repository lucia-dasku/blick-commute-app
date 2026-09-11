import type { JourneyChangesPreference } from "../services/candidateCollector.js";
import {
  journeyTransportModes,
  type JourneyTransportMode,
} from "../models/common.js";

export const LIVE_EXACT_DESTINATION_SEARCH_MODE = "NOW" as const;
export const LIVE_EXACT_DESTINATION_LATER_JOURNEY_COUNT = 0 as const;

/**
 * The current Android line-routine identity. `lineId` and `directionCode` deliberately
 * preserve `null`: today that means the corresponding filter is not pinned, which is
 * materially different from any concrete value.
 */
export interface LineDirectionLiveQueryInput {
  readonly kind: "LINE_DIRECTION";
  readonly siteId: number;
  readonly transportMode: string;
  readonly lineId: number | null;
  readonly directionCode: number | null;
}

export interface ExactDestinationLiveQueryInput {
  readonly kind: "EXACT_DESTINATION";
  readonly originId: string;
  readonly destinationId: string;
  readonly transportModes: readonly JourneyTransportMode[];
  readonly changesPreference: JourneyChangesPreference;
  /** Bounds the backend's targeted PRIMARY/NEXT/ALTERNATIVE acquisition. */
  readonly searchUntil: Date;
}

export type LiveCommuteQueryInput = LineDirectionLiveQueryInput | ExactDestinationLiveQueryInput;

export type LineDirectionLiveQuery = LineDirectionLiveQueryInput;

/**
 * A live exact-destination request is always the background routine contract: NOW with
 * no foreground-only supplemental rows. Keeping those constants in the normalized model
 * makes it impossible for a future acquisition implementation to vary them without also
 * revisiting canonical grouping.
 */
export interface ExactDestinationLiveQuery extends ExactDestinationLiveQueryInput {
  readonly transportModes: readonly JourneyTransportMode[];
  readonly searchMode: typeof LIVE_EXACT_DESTINATION_SEARCH_MODE;
  readonly laterJourneyCount: typeof LIVE_EXACT_DESTINATION_LATER_JOURNEY_COUNT;
}

export type LiveCommuteQuery = LineDirectionLiveQuery | ExactDestinationLiveQuery;

export interface LiveCommuteSessionInput {
  readonly sessionId: string;
  readonly installationId: string;
  readonly routineId: string;
  readonly startsAt: Date;
  readonly endsAt: Date;
  readonly query: LiveCommuteQueryInput;
}

export interface LiveCommuteSession {
  readonly sessionId: string;
  readonly installationId: string;
  readonly routineId: string;
  readonly startsAt: Date;
  readonly endsAt: Date;
  readonly query: LiveCommuteQuery;
}

export interface CanonicalLineDirectionPublicationQuery {
  readonly kind: "LINE_DIRECTION";
  readonly siteId: number;
  readonly transportMode: string;
  readonly lineId: number | null;
  readonly directionCode: number | null;
}

export interface CanonicalLineDirectionAcquisitionQuery {
  readonly kind: "LINE_DIRECTION";
  readonly siteId: number;
}

export interface CanonicalExactDestinationQuery {
  readonly kind: "EXACT_DESTINATION";
  readonly originId: string;
  readonly destinationId: string;
  readonly transportModes: readonly JourneyTransportMode[];
  readonly changesPreference: JourneyChangesPreference;
  readonly searchUntil: string;
  readonly searchMode: typeof LIVE_EXACT_DESTINATION_SEARCH_MODE;
  readonly laterJourneyCount: typeof LIVE_EXACT_DESTINATION_LATER_JOURNEY_COUNT;
}

export type CanonicalAcquisitionQuery =
  | CanonicalLineDirectionAcquisitionQuery
  | CanonicalExactDestinationQuery;

export type CanonicalPublicationQuery =
  | CanonicalLineDirectionPublicationQuery
  | CanonicalExactDestinationQuery;

declare const acquisitionKeyBrand: unique symbol;
declare const publicationKeyBrand: unique symbol;

/** Identity for one safely shareable upstream transit acquisition. */
export type AcquisitionKey = string & { readonly [acquisitionKeyBrand]: true };

/** Identity for sessions that can share the same final dynamic commute state. */
export type PublicationKey = string & { readonly [publicationKeyBrand]: true };

const JOURNEY_CHANGES_PREFERENCES: readonly JourneyChangesPreference[] = [
  "DIRECT_ONLY",
  "BOTH",
  "WITH_CHANGES_ONLY",
];

function normalizedIdentifier(value: string, field: string, maxLength?: number): string {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  const normalized = value.trim();
  if (normalized.length === 0 || (maxLength != null && normalized.length > maxLength)) {
    throw new RangeError(`${field} is invalid`);
  }
  return normalized;
}

function positiveSafeInteger(value: number, field: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${field} must be a positive safe integer`);
  }
  return value;
}

function nullableSafeInteger(
  value: number | null,
  field: string,
  requirePositive: boolean,
): number | null {
  if (value === null) return null;
  if (!Number.isSafeInteger(value) || (requirePositive && value <= 0)) {
    throw new RangeError(
      requirePositive ? `${field} must be null or a positive safe integer` : `${field} must be null or a safe integer`,
    );
  }
  return value;
}

function clonedInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return new Date(value.getTime());
}

function normalizedLineTransportMode(value: string): string {
  return normalizedIdentifier(value, "query.transportMode").toUpperCase();
}

function normalizedJourneyTransportModes(
  values: readonly JourneyTransportMode[],
): readonly JourneyTransportMode[] {
  if (!Array.isArray(values) || values.length === 0) {
    throw new RangeError("query.transportModes must contain at least one supported mode");
  }

  const requested = new Set<JourneyTransportMode>();
  for (const value of values) {
    if (typeof value !== "string") {
      throw new RangeError("query.transportModes contains an unsupported mode");
    }
    const normalized = value.trim().toUpperCase();
    if (!(journeyTransportModes as readonly string[]).includes(normalized)) {
      throw new RangeError("query.transportModes contains an unsupported mode");
    }
    requested.add(normalized as JourneyTransportMode);
  }

  return Object.freeze(journeyTransportModes.filter((mode) => requested.has(mode)));
}

function normalizedChangesPreference(value: JourneyChangesPreference): JourneyChangesPreference {
  if (typeof value !== "string") throw new RangeError("query.changesPreference is invalid");
  const normalized = value.trim().toUpperCase() as JourneyChangesPreference;
  if (!JOURNEY_CHANGES_PREFERENCES.includes(normalized)) {
    throw new RangeError("query.changesPreference is invalid");
  }
  return normalized;
}

export function normalizeLiveCommuteQuery(query: LiveCommuteQueryInput): LiveCommuteQuery {
  if (query == null || typeof query !== "object") {
    throw new TypeError("query must be an object");
  }

  switch (query.kind) {
    case "LINE_DIRECTION":
      return Object.freeze({
        kind: "LINE_DIRECTION",
        siteId: positiveSafeInteger(query.siteId, "query.siteId"),
        transportMode: normalizedLineTransportMode(query.transportMode),
        lineId: nullableSafeInteger(query.lineId, "query.lineId", true),
        directionCode: nullableSafeInteger(query.directionCode, "query.directionCode", false),
      });
    case "EXACT_DESTINATION": {
      const originId = normalizedIdentifier(query.originId, "query.originId", 128);
      const destinationId = normalizedIdentifier(query.destinationId, "query.destinationId", 128);
      if (originId === destinationId) throw new RangeError("query origin and destination must differ");
      return Object.freeze({
        kind: "EXACT_DESTINATION",
        originId,
        destinationId,
        transportModes: normalizedJourneyTransportModes(query.transportModes),
        changesPreference: normalizedChangesPreference(query.changesPreference),
        searchUntil: clonedInstant(query.searchUntil, "query.searchUntil"),
        searchMode: LIVE_EXACT_DESTINATION_SEARCH_MODE,
        laterJourneyCount: LIVE_EXACT_DESTINATION_LATER_JOURNEY_COUNT,
      });
    }
    default:
      throw new RangeError("query.kind is unsupported");
  }
}

/**
 * Validates a concrete live session and snapshots every mutable Date supplied by the
 * caller. The planner repeats this boundary validation so structurally forged values
 * cannot make an invalid window look active.
 */
export function createLiveCommuteSession(input: LiveCommuteSessionInput): LiveCommuteSession {
  if (input == null || typeof input !== "object") throw new TypeError("session must be an object");

  const startsAt = clonedInstant(input.startsAt, "startsAt");
  const endsAt = clonedInstant(input.endsAt, "endsAt");
  if (endsAt.getTime() <= startsAt.getTime()) {
    throw new RangeError("endsAt must be later than startsAt");
  }

  return Object.freeze({
    sessionId: normalizedIdentifier(input.sessionId, "sessionId"),
    installationId: normalizedIdentifier(input.installationId, "installationId"),
    routineId: normalizedIdentifier(input.routineId, "routineId"),
    startsAt,
    endsAt,
    query: normalizeLiveCommuteQuery(input.query),
  });
}

/**
 * A stable representation of the fields that affect one upstream acquisition. A LINE
 * acquisition is site-wide because SL Transport returns the raw departures for a site;
 * mode, line, and direction are applied only during publication projection.
 */
export function canonicalizeAcquisitionQuery(
  query: LiveCommuteQueryInput,
): CanonicalAcquisitionQuery {
  const normalized = normalizeLiveCommuteQuery(query);
  if (normalized.kind === "LINE_DIRECTION") {
    return Object.freeze({
      kind: "LINE_DIRECTION",
      siteId: normalized.siteId,
    });
  }

  return Object.freeze({
    kind: "EXACT_DESTINATION",
    originId: normalized.originId,
    destinationId: normalized.destinationId,
    transportModes: normalized.transportModes,
    changesPreference: normalized.changesPreference,
    searchUntil: normalized.searchUntil.toISOString(),
    searchMode: normalized.searchMode,
    laterJourneyCount: normalized.laterJourneyCount,
  });
}

/**
 * A stable representation of the fields that affect final dynamic commute content.
 * LINE publication retains nullable wildcard filters even though acquisition does not.
 */
export function canonicalizePublicationQuery(
  query: LiveCommuteQueryInput,
): CanonicalPublicationQuery {
  const normalized = normalizeLiveCommuteQuery(query);
  if (normalized.kind === "LINE_DIRECTION") {
    return Object.freeze({
      kind: "LINE_DIRECTION",
      siteId: normalized.siteId,
      transportMode: normalized.transportMode,
      lineId: normalized.lineId,
      directionCode: normalized.directionCode,
    });
  }

  return Object.freeze({
    kind: "EXACT_DESTINATION",
    originId: normalized.originId,
    destinationId: normalized.destinationId,
    transportModes: normalized.transportModes,
    changesPreference: normalized.changesPreference,
    searchUntil: normalized.searchUntil.toISOString(),
    searchMode: normalized.searchMode,
    laterJourneyCount: normalized.laterJourneyCount,
  });
}

/** Readable deterministic JSON keeps every equality rule explicit and collision-safe. */
export function liveCommuteAcquisitionKey(query: LiveCommuteQueryInput): AcquisitionKey {
  return JSON.stringify(canonicalizeAcquisitionQuery(query)) as AcquisitionKey;
}

/** Readable deterministic JSON for final-state sharing, distinct from acquisition identity. */
export function liveCommutePublicationKey(query: LiveCommuteQueryInput): PublicationKey {
  return JSON.stringify(canonicalizePublicationQuery(query)) as PublicationKey;
}
