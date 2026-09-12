import {
  normalizedLiveActivityBindingId,
  positiveActivityKitGeneration,
} from "./deliveryModel.js";
import type {
  LiveCommuteExactJourney,
  LiveCommuteJourneyLeg,
  LiveCommuteJourneyRole,
  LiveCommuteLineDeparture,
  LiveCommuteSnapshot,
  LiveCommuteSnapshotFreshness,
} from "../snapshot.js";

export const BLICK_LIVE_ACTIVITY_SCHEMA_VERSION = 1 as const;
export const BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE =
  "BlickLiveActivityAttributes" as const;

/**
 * The Live Activity presents the current and next LINE_DIRECTION departure.
 * This is a deliberate surface-presentation bound, not payload-size fallback truncation.
 */
export const BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT = 2 as const;

export type BlickLiveActivitySchemaVersion =
  typeof BLICK_LIVE_ACTIVITY_SCHEMA_VERSION;
export type BlickLiveActivityCommuteKind = LiveCommuteSnapshot["kind"];

export interface BlickLiveActivityAttributes {
  readonly schemaVersion: BlickLiveActivitySchemaVersion;
  readonly bindingId: string;
  readonly sessionRevision: number;
  readonly commuteKind: BlickLiveActivityCommuteKind;
}

export interface CreateBlickLiveActivityAttributesInput {
  readonly bindingId: string;
  readonly sessionRevision: number;
  readonly commuteKind: BlickLiveActivityCommuteKind;
}

interface BlickLiveActivityContentStateBase {
  readonly schemaVersion: BlickLiveActivitySchemaVersion;
  readonly freshness: LiveCommuteSnapshotFreshness;
  /** Whole UNIX epoch seconds at which the underlying transit source was acquired. */
  readonly sourceFetchedAt: number;
}

export interface BlickLiveActivityLineDepartureV1 {
  readonly departureId: string;
  readonly lineDesignation: string;
  readonly direction: string | null;
  readonly destination: string | null;
  readonly scheduledAt: number;
  readonly expectedAt: number | null;
  readonly effectiveAt: number;
  readonly isCancelled: boolean;
  readonly departureState: string;
  readonly journeyState: string;
  readonly predictionState: string | null;
}

export interface BlickLiveActivityLineContentStateV1
  extends BlickLiveActivityContentStateBase {
  readonly commuteKind: "LINE_DIRECTION";
  readonly departures: readonly BlickLiveActivityLineDepartureV1[];
}

export interface BlickLiveActivityJourneyLegV1 {
  readonly transportMode: string;
  readonly lineDesignation: string | null;
  readonly direction: string | null;
  readonly originName: string;
  readonly destinationName: string;
  readonly departureAt: number | null;
  readonly arrivalAt: number | null;
  readonly isRealtime: boolean;
}

export interface BlickLiveActivityExactJourneyV1 {
  readonly journeyId: string;
  readonly role: LiveCommuteJourneyRole;
  readonly originName: string;
  readonly destinationName: string;
  /** Overall journey departure, which may precede the first public-transport leg. */
  readonly departureAt: number;
  /** First useful public-transport departure used for expiry and countdown rendering. */
  readonly effectiveDepartureAt: number;
  readonly arrivalAt: number;
  readonly transferCount: number;
  readonly firstLeg: BlickLiveActivityJourneyLegV1;
}

export interface BlickLiveActivityExactContentStateV1
  extends BlickLiveActivityContentStateBase {
  readonly commuteKind: "EXACT_DESTINATION";
  readonly journeys: readonly BlickLiveActivityExactJourneyV1[];
}

export type BlickLiveActivityContentStateV1 =
  | BlickLiveActivityLineContentStateV1
  | BlickLiveActivityExactContentStateV1;

/** Current wire content state. Advance this alias only with an intentional schema revision. */
export type BlickLiveActivityContentState = BlickLiveActivityContentStateV1;

const ABSOLUTE_TIMESTAMP_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i;

function validatedCommuteKind(value: string): BlickLiveActivityCommuteKind {
  if (value !== "LINE_DIRECTION" && value !== "EXACT_DESTINATION") {
    throw new RangeError("commuteKind is invalid");
  }
  return value;
}

function instantMillis(value: Date, field: string): number {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError(`${field} must be a valid absolute Date`);
  }
  return value.getTime();
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

function unixSecondsFromMillis(millis: number, field: string): number {
  const seconds = Math.floor(millis / 1_000);
  if (!Number.isSafeInteger(seconds)) {
    throw new RangeError(`${field} is outside the supported UNIX timestamp range`);
  }
  return seconds;
}

function unixSeconds(value: string, field: string): number {
  return unixSecondsFromMillis(timestampMillis(value, field), field);
}

function mapLineDeparture(
  departure: LiveCommuteLineDeparture,
): BlickLiveActivityLineDepartureV1 {
  return Object.freeze({
    departureId: departure.departureId,
    lineDesignation: departure.lineDesignation,
    direction: departure.direction,
    destination: departure.destination,
    scheduledAt: unixSeconds(
      departure.scheduledTime,
      "departure.scheduledTime",
    ),
    expectedAt:
      departure.expectedTime == null
        ? null
        : unixSeconds(departure.expectedTime, "departure.expectedTime"),
    effectiveAt: unixSeconds(
      departure.effectiveTime,
      "departure.effectiveTime",
    ),
    isCancelled: departure.isCancelled,
    departureState: departure.state,
    journeyState: departure.journeyState,
    predictionState: departure.predictionState,
  });
}

function selectLinePresentationDepartures(
  departures: readonly LiveCommuteLineDeparture[],
): readonly LiveCommuteLineDeparture[] {
  const first = departures[0];
  if (first == null) return [];
  const nextBoardable = departures
    .slice(1)
    .find((departure) => !departure.isCancelled);
  return nextBoardable == null
    ? departures.slice(0, BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT)
    : [first, nextBoardable];
}

function mapJourneyLeg(
  leg: LiveCommuteJourneyLeg,
): BlickLiveActivityJourneyLegV1 {
  return Object.freeze({
    transportMode: leg.transportMode,
    lineDesignation: leg.lineDesignation,
    direction: leg.direction,
    originName: leg.originName,
    destinationName: leg.destinationName,
    departureAt:
      leg.departureTime == null
        ? null
        : unixSeconds(leg.departureTime, "journey.firstLeg.departureTime"),
    arrivalAt:
      leg.arrivalTime == null
        ? null
        : unixSeconds(leg.arrivalTime, "journey.firstLeg.arrivalTime"),
    isRealtime: leg.isRealtime,
  });
}

function mapExactJourney(
  journey: LiveCommuteExactJourney,
): BlickLiveActivityExactJourneyV1 {
  return Object.freeze({
    journeyId: journey.journeyId,
    role: journey.role,
    originName: journey.originName,
    destinationName: journey.destinationName,
    departureAt: unixSeconds(journey.departureTime, "journey.departureTime"),
    effectiveDepartureAt: unixSeconds(
      journey.effectiveDepartureTime,
      "journey.effectiveDepartureTime",
    ),
    arrivalAt: unixSeconds(journey.arrivalTime, "journey.arrivalTime"),
    transferCount: journey.transferCount,
    firstLeg: mapJourneyLeg(journey.firstLeg),
  });
}

export function createBlickLiveActivityAttributes(
  input: CreateBlickLiveActivityAttributesInput,
): BlickLiveActivityAttributes {
  return Object.freeze({
    schemaVersion: BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
    bindingId: normalizedLiveActivityBindingId(input.bindingId),
    sessionRevision: positiveActivityKitGeneration(
      input.sessionRevision,
      "sessionRevision",
    ),
    commuteKind: validatedCommuteKind(input.commuteKind),
  });
}

/**
 * Projects an accepted snapshot at an explicit instant. Expiry is evaluated at the
 * source timestamp's full precision before the wire representation is reduced to seconds.
 */
export function mapLiveCommuteSnapshotToContentState(
  snapshot: LiveCommuteSnapshot,
  projectionAt: Date,
): BlickLiveActivityContentStateV1 {
  const projectionMillis = instantMillis(projectionAt, "projectionAt");
  const sourceFetchedAt = unixSeconds(
    snapshot.sourceFetchedAt,
    "sourceFetchedAt",
  );

  if (snapshot.kind === "LINE_DIRECTION") {
    const futureDepartures = snapshot.departures.filter(
      (departure) =>
        timestampMillis(departure.effectiveTime, "departure.effectiveTime") >=
        projectionMillis,
    );
    const departures = selectLinePresentationDepartures(futureDepartures).map(
      mapLineDeparture,
    );

    return Object.freeze({
      schemaVersion: BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
      commuteKind: "LINE_DIRECTION",
      freshness: snapshot.freshness,
      sourceFetchedAt,
      departures: Object.freeze(departures),
    });
  }

  const journeys = snapshot.journeys
    .filter(
      (journey) =>
        timestampMillis(
          journey.effectiveDepartureTime,
          "journey.effectiveDepartureTime",
        ) >= projectionMillis,
    )
    .map(mapExactJourney);

  return Object.freeze({
    schemaVersion: BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
    commuteKind: "EXACT_DESTINATION",
    freshness: snapshot.freshness,
    sourceFetchedAt,
    journeys: Object.freeze(journeys),
  });
}
