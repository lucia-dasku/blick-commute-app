import type { Departure } from "../models/departure.js";

/** Matches Android's bounded candidate pool; individual surfaces can display fewer rows. */
export const LIVE_COMMUTE_LINE_DEPARTURE_LIMIT = 5 as const;

export type LiveCommuteSnapshotFreshness = "FRESH" | "STALE";

interface LiveCommuteSnapshotBase {
  /** Freshness of the transit state, independent from when this projection was generated. */
  readonly freshness: LiveCommuteSnapshotFreshness;
  /** The original source acquisition time. A stale reprojection must preserve this value. */
  readonly sourceFetchedAt: string;
  /** The instant at which expiry was evaluated and this snapshot was projected. */
  readonly generatedAt: string;
}

export interface LiveCommuteLineDeparture {
  readonly departureId: string;
  readonly lineDesignation: string;
  readonly direction: string | null;
  readonly destination: string | null;
  readonly scheduledTime: string;
  readonly expectedTime: string | null;
  /** `expectedTime ?? scheduledTime`, materialized for direct client rendering. */
  readonly effectiveTime: string;
  readonly isCancelled: boolean;
  readonly state: string;
  readonly journeyState: string;
  readonly predictionState: string | null;
}

export interface LineDirectionLiveCommuteSnapshot extends LiveCommuteSnapshotBase {
  readonly kind: "LINE_DIRECTION";
  /** Five candidates are retained so an imminent departure can roll over without a thin payload. */
  readonly departures: readonly LiveCommuteLineDeparture[];
}

export type LiveCommuteJourneyRole = "PRIMARY" | "NEXT" | "ALTERNATIVE";

export interface LiveCommuteJourneyLeg {
  readonly transportMode: string;
  readonly lineDesignation: string | null;
  readonly direction: string | null;
  readonly originName: string;
  readonly destinationName: string;
  readonly departureTime: string | null;
  readonly arrivalTime: string | null;
  readonly isRealtime: boolean;
}

export interface LiveCommuteExactJourney {
  readonly journeyId: string;
  /** Assigned by the authoritative backend selection engine, never inferred here. */
  readonly role: LiveCommuteJourneyRole;
  readonly originName: string;
  readonly destinationName: string;
  readonly departureTime: string;
  /** The first public-transport departure used for expiry and countdown presentation. */
  readonly effectiveDepartureTime: string;
  readonly arrivalTime: string;
  readonly transferCount: number;
  readonly firstLeg: LiveCommuteJourneyLeg;
  readonly legs: readonly LiveCommuteJourneyLeg[];
}

export interface ExactDestinationLiveCommuteSnapshot extends LiveCommuteSnapshotBase {
  readonly kind: "EXACT_DESTINATION";
  readonly journeys: readonly LiveCommuteExactJourney[];
}

export type LiveCommuteSnapshot =
  | LineDirectionLiveCommuteSnapshot
  | ExactDestinationLiveCommuteSnapshot;

/** The final LINE_DIRECTION filters carried by a publication group. */
export interface LineDirectionSnapshotQuery {
  readonly transportMode: string;
  readonly lineId: number | null;
  readonly directionCode: number | null;
}

/** A normalized site-level departure acquisition. Extra response fields are intentionally ignored. */
export interface NormalizedDepartureSnapshotSource {
  readonly fetchedAt: string | Date;
  readonly departures: readonly Departure[];
}

/**
 * Structural subset of the existing normalized journey shape. Callers can pass a richer
 * normalized journey without coupling this projection to an HTTP response.
 */
export interface NormalizedLiveJourneySnapshotInput {
  readonly journeyId: string;
  readonly originName: string;
  readonly destinationName: string;
  readonly departureTime: string;
  readonly arrivalTime: string;
  readonly transferCount: number;
  readonly firstLeg: LiveCommuteJourneyLeg;
  readonly legs: readonly LiveCommuteJourneyLeg[];
}

/** Supports both the extracted service's role wrapper and the existing flat public shape. */
export type AuthoritativeLiveJourneySnapshotInput =
  | (NormalizedLiveJourneySnapshotInput & { readonly role: LiveCommuteJourneyRole })
  | {
      readonly role: LiveCommuteJourneyRole;
      readonly journey: NormalizedLiveJourneySnapshotInput;
    };

export interface AuthoritativeJourneySnapshotSource {
  readonly fetchedAt: string | Date;
  readonly journeys: readonly AuthoritativeLiveJourneySnapshotInput[];
}

const ABSOLUTE_TIMESTAMP_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i;

function validInstant(value: Date, field: string): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return value;
}

function timestampMillis(value: string, field: string): number {
  if (typeof value !== "string" || !ABSOLUTE_TIMESTAMP_SUFFIX.test(value)) {
    throw new RangeError(`${field} must be an absolute timestamp`);
  }
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) {
    throw new RangeError(`${field} must be an absolute timestamp`);
  }
  return millis;
}

function canonicalTimestamp(value: string, field: string): string {
  return new Date(timestampMillis(value, field)).toISOString();
}

function validatedSourceTimestamp(value: string | Date): string {
  if (value instanceof Date) return validInstant(value, "sourceFetchedAt").toISOString();
  timestampMillis(value, "sourceFetchedAt");
  return value;
}

function freezeLineDeparture(departure: LiveCommuteLineDeparture): LiveCommuteLineDeparture {
  return Object.freeze(departure);
}

function projectLineDeparture(departure: Departure): {
  readonly snapshot: LiveCommuteLineDeparture;
  readonly effectiveMillis: number;
} {
  const scheduledTime = canonicalTimestamp(
    departure.scheduledTime,
    "departure.scheduledTime",
  );
  const expectedTime =
    departure.expectedTime == null
      ? null
      : canonicalTimestamp(departure.expectedTime, "departure.expectedTime");
  const effectiveTime = expectedTime ?? scheduledTime;
  return {
    snapshot: freezeLineDeparture({
      departureId: departure.departureId,
      lineDesignation: departure.line.designation,
      direction: departure.direction,
      destination: departure.destination,
      scheduledTime,
      expectedTime,
      effectiveTime,
      isCancelled: departure.isCancelled,
      state: departure.state,
      journeyState: departure.journey.state,
      predictionState: departure.journey.predictionState,
    }),
    effectiveMillis: timestampMillis(effectiveTime, "departure.effectiveTime"),
  };
}

/**
 * Projects one shared site acquisition into the full mode/line/direction publication query.
 * Filtering, expiry, sorting and the five-row bound mirror the existing Android processor.
 */
export function buildLineDirectionLiveCommuteSnapshot(
  query: LineDirectionSnapshotQuery,
  source: NormalizedDepartureSnapshotSource,
  generatedAt: Date,
  freshness: LiveCommuteSnapshotFreshness = "FRESH",
): LineDirectionLiveCommuteSnapshot {
  const generationTime = validInstant(generatedAt, "generatedAt");
  const generationMillis = generationTime.getTime();
  const departures = source.departures
    .filter((departure) => departure.line.transportMode === query.transportMode)
    .filter((departure) => query.lineId == null || departure.line.id === query.lineId)
    .filter((departure) => query.directionCode == null || departure.directionCode === query.directionCode)
    .map(projectLineDeparture)
    .filter((departure) => departure.effectiveMillis >= generationMillis)
    .sort((left, right) => left.effectiveMillis - right.effectiveMillis)
    .slice(0, LIVE_COMMUTE_LINE_DEPARTURE_LIMIT)
    .map((departure) => departure.snapshot);

  return Object.freeze({
    kind: "LINE_DIRECTION",
    freshness,
    sourceFetchedAt: validatedSourceTimestamp(source.fetchedAt),
    generatedAt: generationTime.toISOString(),
    departures: Object.freeze(departures),
  });
}

function freezeJourneyLeg(leg: LiveCommuteJourneyLeg): LiveCommuteJourneyLeg {
  return Object.freeze({
    transportMode: leg.transportMode,
    lineDesignation: leg.lineDesignation,
    direction: leg.direction,
    originName: leg.originName,
    destinationName: leg.destinationName,
    departureTime:
      leg.departureTime == null
        ? null
        : canonicalTimestamp(leg.departureTime, "journey.leg.departureTime"),
    arrivalTime:
      leg.arrivalTime == null
        ? null
        : canonicalTimestamp(leg.arrivalTime, "journey.leg.arrivalTime"),
    isRealtime: leg.isRealtime,
  });
}

function projectExactJourney(input: AuthoritativeLiveJourneySnapshotInput): {
  readonly snapshot: LiveCommuteExactJourney;
  readonly effectiveDepartureMillis: number;
} {
  const role = input.role;
  const journey = "journey" in input ? input.journey : input;
  const departureTime = canonicalTimestamp(
    journey.departureTime,
    "journey.departureTime",
  );
  const arrivalTime = canonicalTimestamp(journey.arrivalTime, "journey.arrivalTime");
  const firstLeg = freezeJourneyLeg(journey.firstLeg);
  const effectiveDepartureTime = firstLeg.departureTime ?? departureTime;
  const legs = journey.legs.map(freezeJourneyLeg);

  return {
    snapshot: Object.freeze({
      journeyId: journey.journeyId,
      role,
      originName: journey.originName,
      destinationName: journey.destinationName,
      departureTime,
      effectiveDepartureTime,
      arrivalTime,
      transferCount: journey.transferCount,
      firstLeg,
      legs: Object.freeze(legs),
    }),
    effectiveDepartureMillis: timestampMillis(
      effectiveDepartureTime,
      "journey.effectiveDepartureTime",
    ),
  };
}

/**
 * Projects already-authoritative PRIMARY/NEXT/ALTERNATIVE results without selecting,
 * reordering or relabeling them. Expiry uses the effective first transit departure.
 */
export function buildExactDestinationLiveCommuteSnapshot(
  source: AuthoritativeJourneySnapshotSource,
  generatedAt: Date,
  freshness: LiveCommuteSnapshotFreshness = "FRESH",
): ExactDestinationLiveCommuteSnapshot {
  const generationTime = validInstant(generatedAt, "generatedAt");
  const generationMillis = generationTime.getTime();
  const journeys = source.journeys
    .map(projectExactJourney)
    .filter((journey) => journey.effectiveDepartureMillis >= generationMillis)
    .map((journey) => journey.snapshot);

  return Object.freeze({
    kind: "EXACT_DESTINATION",
    freshness,
    sourceFetchedAt: validatedSourceTimestamp(source.fetchedAt),
    generatedAt: generationTime.toISOString(),
    journeys: Object.freeze(journeys),
  });
}

/**
 * Re-evaluates an optional previous snapshot at the current clock without manufacturing
 * new state. Exact-destination roles are preserved even when PRIMARY has expired.
 */
export function reprojectStaleLiveCommuteSnapshot(
  previous: LiveCommuteSnapshot,
  generatedAt: Date,
): LiveCommuteSnapshot {
  const generationTime = validInstant(generatedAt, "generatedAt");
  const generationMillis = generationTime.getTime();
  const sourceFetchedAt = validatedSourceTimestamp(previous.sourceFetchedAt);

  if (previous.kind === "LINE_DIRECTION") {
    const departures = previous.departures
      .map((departure) => ({
        departure,
        effectiveMillis: timestampMillis(departure.effectiveTime, "departure.effectiveTime"),
      }))
      .filter((departure) => departure.effectiveMillis >= generationMillis)
      .sort((left, right) => left.effectiveMillis - right.effectiveMillis)
      .slice(0, LIVE_COMMUTE_LINE_DEPARTURE_LIMIT)
      .map((departure) => departure.departure);
    return Object.freeze({
      kind: "LINE_DIRECTION",
      freshness: "STALE",
      sourceFetchedAt,
      generatedAt: generationTime.toISOString(),
      departures: Object.freeze(departures),
    });
  }

  const journeys = previous.journeys.filter(
    (journey) =>
      timestampMillis(journey.effectiveDepartureTime, "journey.effectiveDepartureTime") >=
      generationMillis,
  );
  return Object.freeze({
    kind: "EXACT_DESTINATION",
    freshness: "STALE",
    sourceFetchedAt,
    generatedAt: generationTime.toISOString(),
    journeys: Object.freeze(journeys),
  });
}

/**
 * Stable semantic content only. Acquisition/generation timestamps are deliberately absent,
 * so an identical refetch is not confused with a user-visible transit-state change.
 */
export function liveCommuteSnapshotFingerprint(snapshot: LiveCommuteSnapshot): string {
  if (snapshot.kind === "LINE_DIRECTION") {
    return JSON.stringify({
      kind: snapshot.kind,
      freshness: snapshot.freshness,
      departures: snapshot.departures.map((departure) => ({
        departureId: departure.departureId,
        lineDesignation: departure.lineDesignation,
        direction: departure.direction,
        destination: departure.destination,
        scheduledTime: canonicalTimestamp(
          departure.scheduledTime,
          "departure.scheduledTime",
        ),
        expectedTime:
          departure.expectedTime == null
            ? null
            : canonicalTimestamp(departure.expectedTime, "departure.expectedTime"),
        effectiveTime: canonicalTimestamp(
          departure.effectiveTime,
          "departure.effectiveTime",
        ),
        isCancelled: departure.isCancelled,
        state: departure.state,
        journeyState: departure.journeyState,
        predictionState: departure.predictionState,
      })),
    });
  }

  const semanticLeg = (leg: LiveCommuteJourneyLeg) => ({
    transportMode: leg.transportMode,
    lineDesignation: leg.lineDesignation,
    direction: leg.direction,
    originName: leg.originName,
    destinationName: leg.destinationName,
    departureTime:
      leg.departureTime == null
        ? null
        : canonicalTimestamp(leg.departureTime, "journey.leg.departureTime"),
    arrivalTime:
      leg.arrivalTime == null
        ? null
        : canonicalTimestamp(leg.arrivalTime, "journey.leg.arrivalTime"),
    isRealtime: leg.isRealtime,
  });

  return JSON.stringify({
    kind: snapshot.kind,
    freshness: snapshot.freshness,
    journeys: snapshot.journeys.map((journey) => ({
      journeyId: journey.journeyId,
      role: journey.role,
      originName: journey.originName,
      destinationName: journey.destinationName,
      departureTime: canonicalTimestamp(
        journey.departureTime,
        "journey.departureTime",
      ),
      effectiveDepartureTime: canonicalTimestamp(
        journey.effectiveDepartureTime,
        "journey.effectiveDepartureTime",
      ),
      arrivalTime: canonicalTimestamp(journey.arrivalTime, "journey.arrivalTime"),
      transferCount: journey.transferCount,
      firstLeg: semanticLeg(journey.firstLeg),
      legs: journey.legs.map(semanticLeg),
    })),
  });
}

/** `true` means semantic snapshot content differs; delivery/heartbeat policy is separate. */
export function liveCommuteSnapshotContentChanged(
  previous: LiveCommuteSnapshot | null | undefined,
  next: LiveCommuteSnapshot,
): boolean {
  return previous == null || liveCommuteSnapshotFingerprint(previous) !== liveCommuteSnapshotFingerprint(next);
}
