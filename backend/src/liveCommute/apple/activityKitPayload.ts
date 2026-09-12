import {
  BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
  BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT,
  BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
  createBlickLiveActivityAttributes,
  type BlickLiveActivityAttributes,
  type BlickLiveActivityContentState,
} from "./liveActivityWireContract.js";
import { LIVE_ACTIVITY_GENERATION_MAX } from "./deliveryModel.js";

/** Conservative bound for the complete JSON body passed to a future APNs transport. */
export const ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES = 4_096 as const;

export interface ActivityKitAlert {
  readonly title: string;
  readonly body: string;
  readonly sound?: string;
}

export type ActivityKitStartMode =
  | {
      readonly kind: "DIRECT_LEGACY";
      readonly channelId?: never;
    }
  | {
      readonly kind: "DIRECT_IOS_18";
      readonly channelId?: never;
    }
  | {
      readonly kind: "BROADCAST_CHANNEL";
      readonly channelId: string;
    };

interface ActivityKitApsBase {
  readonly timestamp: number;
  readonly "content-state": BlickLiveActivityContentState;
}

export interface ActivityKitStartAps extends ActivityKitApsBase {
  readonly event: "start";
  readonly "attributes-type": typeof BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE;
  readonly attributes: BlickLiveActivityAttributes;
  readonly alert: ActivityKitAlert;
  readonly "input-push-token"?: 1;
  readonly "input-push-channel"?: string;
}

export interface ActivityKitUpdateAps extends ActivityKitApsBase {
  readonly event: "update";
  readonly "stale-date"?: number;
  readonly alert?: ActivityKitAlert;
}

export interface ActivityKitEndAps extends ActivityKitApsBase {
  readonly event: "end";
  readonly "dismissal-date"?: number;
  readonly alert?: ActivityKitAlert;
}

export type ActivityKitPayload =
  | { readonly aps: ActivityKitStartAps }
  | { readonly aps: ActivityKitUpdateAps }
  | { readonly aps: ActivityKitEndAps };

/**
 * The JSON string is the authoritative transport body. `value` is retained only so callers
 * and tests can inspect the exact wire object without reparsing it.
 */
export interface BuiltActivityKitPayload<
  TPayload extends ActivityKitPayload = ActivityKitPayload,
> {
  readonly value: TPayload;
  readonly serialized: string;
  readonly utf8ByteLength: number;
  /** Non-wire context used to enforce ActivityKit's combined static/dynamic 4 KB limit. */
  readonly activityData: {
    readonly attributes: BlickLiveActivityAttributes;
    readonly "content-state": BlickLiveActivityContentState;
  };
  readonly activityDataUtf8ByteLength: number;
  /** V1 ceiling reserves the longest valid revision for shared broadcast subscribers. */
  readonly activityDataSizeCeilingUtf8ByteLength: number;
}

export interface BuildActivityKitStartPayloadInput {
  readonly generatedAt: Date | number;
  readonly contentState: BlickLiveActivityContentState;
  readonly attributes: BlickLiveActivityAttributes;
  readonly alert: ActivityKitAlert;
  readonly mode: ActivityKitStartMode;
}

export interface BuildActivityKitUpdatePayloadInput {
  readonly generatedAt: Date | number;
  readonly contentState: BlickLiveActivityContentState;
  /** Static activity data is not resent, but is required for combined-size enforcement. */
  readonly attributes: BlickLiveActivityAttributes;
  readonly staleAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export interface BuildActivityKitEndPayloadInput {
  readonly generatedAt: Date | number;
  readonly contentState: BlickLiveActivityContentState;
  /** Static activity data is not resent, but is required for combined-size enforcement. */
  readonly attributes: BlickLiveActivityAttributes;
  readonly dismissalAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

function deepFreeze<T>(value: T): T {
  if (value != null && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

function nonemptyString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new RangeError(`${field} must be a nonempty string`);
  }
  return value;
}

function stringValue(value: unknown, field: string): string {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  return value;
}

function nullableString(value: unknown, field: string): string | null {
  return value == null ? null : stringValue(value, field);
}

function nonnegativeInteger(value: unknown, field: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new RangeError(`${field} must be a nonnegative integer`);
  }
  return value as number;
}

function optionalEpochSeconds(value: unknown, field: string): number | null {
  return value == null ? null : nonnegativeInteger(value, field);
}

function booleanValue(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") throw new TypeError(`${field} must be a boolean`);
  return value;
}

function normalizedAlert(value: ActivityKitAlert, field: string): ActivityKitAlert {
  if (value == null || typeof value !== "object") {
    throw new TypeError(`${field} is required`);
  }
  const alert: ActivityKitAlert = {
    title: nonemptyString(value.title, `${field}.title`),
    body: nonemptyString(value.body, `${field}.body`),
    ...(value.sound == null
      ? {}
      : { sound: nonemptyString(value.sound, `${field}.sound`) }),
  };
  return Object.freeze(alert);
}

/** Converts an explicit absolute instant to the whole epoch seconds ActivityKit expects. */
export function activityKitEpochSeconds(
  value: Date | number,
  field = "timestamp",
): number {
  if (value instanceof Date) {
    const milliseconds = value.getTime();
    if (!Number.isFinite(milliseconds) || milliseconds < 0) {
      throw new RangeError(`${field} must be a valid absolute instant`);
    }
    return Math.floor(milliseconds / 1_000);
  }
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${field} must be whole nonnegative UNIX epoch seconds`);
  }
  return value;
}

function validatedInputPushChannel(value: unknown): string {
  const channelId = nonemptyString(value, "mode.channelId");
  if (
    channelId.length > 2_048 ||
    channelId.trim() !== channelId ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(channelId)
  ) {
    throw new RangeError("mode.channelId must be an opaque base64 APNs channel identifier");
  }
  const canonical = Buffer.from(channelId, "base64").toString("base64");
  const matchesCanonical = channelId.includes("=")
    ? channelId === canonical
    : channelId === canonical.replace(/=+$/, "");
  if (!matchesCanonical) {
    throw new RangeError("mode.channelId must be an opaque base64 APNs channel identifier");
  }
  return channelId;
}

function startModeFields(
  mode: ActivityKitStartMode,
): Pick<ActivityKitStartAps, "input-push-token" | "input-push-channel"> {
  if (mode == null || typeof mode !== "object") {
    throw new TypeError("start mode is required");
  }
  if (mode.kind === "DIRECT_LEGACY") {
    if ("channelId" in mode) {
      throw new RangeError("legacy direct start cannot include an input push channel");
    }
    return Object.freeze({});
  }
  if (mode.kind === "DIRECT_IOS_18") {
    if ("channelId" in mode) {
      throw new RangeError("direct-token start cannot include an input push channel");
    }
    return Object.freeze({ "input-push-token": 1 });
  }
  if (mode.kind === "BROADCAST_CHANNEL") {
    if ("inputPushToken" in mode) {
      throw new RangeError("broadcast start cannot also request a direct update token");
    }
    return Object.freeze({
      "input-push-channel": validatedInputPushChannel(mode.channelId),
    });
  }
  throw new RangeError("start mode is invalid");
}

function copiedWireState(
  contentState: BlickLiveActivityContentState,
): BlickLiveActivityContentState {
  if (contentState == null || typeof contentState !== "object") {
    throw new TypeError("contentState is required");
  }
  if (contentState.schemaVersion !== BLICK_LIVE_ACTIVITY_SCHEMA_VERSION) {
    throw new RangeError("contentState schema version is unsupported");
  }
  if (
    contentState.commuteKind !== "LINE_DIRECTION" &&
    contentState.commuteKind !== "EXACT_DESTINATION"
  ) {
    throw new RangeError("contentState commute kind is invalid");
  }
  if (contentState.freshness !== "FRESH" && contentState.freshness !== "STALE") {
    throw new RangeError("contentState freshness is invalid");
  }
  const sourceFetchedAt = nonnegativeInteger(
    contentState.sourceFetchedAt,
    "contentState.sourceFetchedAt",
  );
  if (contentState.commuteKind === "LINE_DIRECTION") {
    if (!Array.isArray(contentState.departures)) {
      throw new TypeError("contentState.departures must be an array");
    }
    if (contentState.departures.length > BLICK_LIVE_ACTIVITY_LINE_DEPARTURE_LIMIT) {
      throw new RangeError("contentState exceeds the LINE departure presentation bound");
    }
    return {
      schemaVersion: contentState.schemaVersion,
      commuteKind: contentState.commuteKind,
      freshness: contentState.freshness,
      sourceFetchedAt,
      departures: contentState.departures.map((departure, index) => {
        if (departure == null || typeof departure !== "object") {
          throw new TypeError(`contentState.departures[${index}] must be an object`);
        }
        return {
          departureId: nonemptyString(
            departure.departureId,
            `contentState.departures[${index}].departureId`,
          ),
          lineDesignation: stringValue(
            departure.lineDesignation,
            `contentState.departures[${index}].lineDesignation`,
          ),
          direction: nullableString(
            departure.direction,
            `contentState.departures[${index}].direction`,
          ),
          destination: nullableString(
            departure.destination,
            `contentState.departures[${index}].destination`,
          ),
          scheduledAt: nonnegativeInteger(
            departure.scheduledAt,
            `contentState.departures[${index}].scheduledAt`,
          ),
          expectedAt: optionalEpochSeconds(
            departure.expectedAt,
            `contentState.departures[${index}].expectedAt`,
          ),
          effectiveAt: nonnegativeInteger(
            departure.effectiveAt,
            `contentState.departures[${index}].effectiveAt`,
          ),
          isCancelled: booleanValue(
            departure.isCancelled,
            `contentState.departures[${index}].isCancelled`,
          ),
          departureState: stringValue(
            departure.departureState,
            `contentState.departures[${index}].departureState`,
          ),
          journeyState: stringValue(
            departure.journeyState,
            `contentState.departures[${index}].journeyState`,
          ),
          predictionState: nullableString(
            departure.predictionState,
            `contentState.departures[${index}].predictionState`,
          ),
        };
      }),
    };
  }
  if (!Array.isArray(contentState.journeys)) {
    throw new TypeError("contentState.journeys must be an array");
  }
  if (contentState.journeys.length > 3) {
    throw new RangeError("contentState exceeds the EXACT journey role bound");
  }
  const roles = new Set(contentState.journeys.map((journey) => journey?.role));
  if (roles.size !== contentState.journeys.length) {
    throw new RangeError("contentState EXACT journey roles must be unique");
  }
  return {
    schemaVersion: contentState.schemaVersion,
    commuteKind: contentState.commuteKind,
    freshness: contentState.freshness,
    sourceFetchedAt,
    journeys: contentState.journeys.map((journey, index) => {
      if (journey == null || typeof journey !== "object") {
        throw new TypeError(`contentState.journeys[${index}] must be an object`);
      }
      if (
        journey.role !== "PRIMARY" &&
        journey.role !== "NEXT" &&
        journey.role !== "ALTERNATIVE"
      ) {
        throw new RangeError(`contentState.journeys[${index}].role is invalid`);
      }
      if (journey.firstLeg == null || typeof journey.firstLeg !== "object") {
        throw new TypeError(`contentState.journeys[${index}].firstLeg must be an object`);
      }
      return {
        journeyId: nonemptyString(
          journey.journeyId,
          `contentState.journeys[${index}].journeyId`,
        ),
        role: journey.role,
        originName: stringValue(
          journey.originName,
          `contentState.journeys[${index}].originName`,
        ),
        destinationName: stringValue(
          journey.destinationName,
          `contentState.journeys[${index}].destinationName`,
        ),
        departureAt: nonnegativeInteger(
          journey.departureAt,
          `contentState.journeys[${index}].departureAt`,
        ),
        effectiveDepartureAt: nonnegativeInteger(
          journey.effectiveDepartureAt,
          `contentState.journeys[${index}].effectiveDepartureAt`,
        ),
        arrivalAt: nonnegativeInteger(
          journey.arrivalAt,
          `contentState.journeys[${index}].arrivalAt`,
        ),
        transferCount: nonnegativeInteger(
          journey.transferCount,
          `contentState.journeys[${index}].transferCount`,
        ),
        firstLeg: {
          transportMode: stringValue(
            journey.firstLeg.transportMode,
            `contentState.journeys[${index}].firstLeg.transportMode`,
          ),
          lineDesignation: nullableString(
            journey.firstLeg.lineDesignation,
            `contentState.journeys[${index}].firstLeg.lineDesignation`,
          ),
          direction: nullableString(
            journey.firstLeg.direction,
            `contentState.journeys[${index}].firstLeg.direction`,
          ),
          originName: stringValue(
            journey.firstLeg.originName,
            `contentState.journeys[${index}].firstLeg.originName`,
          ),
          destinationName: stringValue(
            journey.firstLeg.destinationName,
            `contentState.journeys[${index}].firstLeg.destinationName`,
          ),
          departureAt: optionalEpochSeconds(
            journey.firstLeg.departureAt,
            `contentState.journeys[${index}].firstLeg.departureAt`,
          ),
          arrivalAt: optionalEpochSeconds(
            journey.firstLeg.arrivalAt,
            `contentState.journeys[${index}].firstLeg.arrivalAt`,
          ),
          isRealtime: booleanValue(
            journey.firstLeg.isRealtime,
            `contentState.journeys[${index}].firstLeg.isRealtime`,
          ),
        },
      };
    }),
  };
}

function copiedAttributes(
  attributes: BlickLiveActivityAttributes,
  contentState: BlickLiveActivityContentState,
): BlickLiveActivityAttributes {
  if (attributes == null || typeof attributes !== "object") {
    throw new TypeError("attributes are required");
  }
  if (attributes.schemaVersion !== BLICK_LIVE_ACTIVITY_SCHEMA_VERSION) {
    throw new RangeError("attributes schema version is unsupported");
  }
  if (attributes.commuteKind !== contentState.commuteKind) {
    throw new RangeError("attributes and contentState commute kinds must match");
  }
  return createBlickLiveActivityAttributes({
    bindingId: attributes.bindingId,
    sessionRevision: attributes.sessionRevision,
    commuteKind: attributes.commuteKind,
  });
}

const SIZE_CEILING_BINDING_ID = "ffffffff-ffff-4fff-bfff-ffffffffffff";

function activityDataSizeCeilingUtf8ByteLength(
  contentState: BlickLiveActivityContentState,
): number {
  return Buffer.byteLength(
    JSON.stringify({
      attributes: {
        schemaVersion: BLICK_LIVE_ACTIVITY_SCHEMA_VERSION,
        bindingId: SIZE_CEILING_BINDING_ID,
        sessionRevision: LIVE_ACTIVITY_GENERATION_MAX,
        commuteKind: contentState.commuteKind,
      },
      "content-state": contentState,
    }),
    "utf8",
  );
}

function buildPayload<TPayload extends ActivityKitPayload>(
  value: TPayload,
  attributes: BlickLiveActivityAttributes,
): BuiltActivityKitPayload<TPayload> {
  // Serialize and size-check before freezing an owned plain-data copy. Caller-owned wire
  // state must remain untouched even when construction fails closed for size.
  const serialized = JSON.stringify(value);
  const utf8ByteLength = Buffer.byteLength(serialized, "utf8");
  if (utf8ByteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES) {
    throw new RangeError(
      `ActivityKit payload exceeds ${ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES} UTF-8 bytes`,
    );
  }
  const frozenValue = deepFreeze(JSON.parse(serialized) as TPayload);
  const activityData = deepFreeze({
    attributes: JSON.parse(JSON.stringify(attributes)) as BlickLiveActivityAttributes,
    "content-state": JSON.parse(
      JSON.stringify(frozenValue.aps["content-state"]),
    ) as BlickLiveActivityContentState,
  });
  const activityDataUtf8ByteLength = Buffer.byteLength(
    JSON.stringify(activityData),
    "utf8",
  );
  const sizeCeilingUtf8ByteLength = activityDataSizeCeilingUtf8ByteLength(
    activityData["content-state"],
  );
  if (
    activityDataUtf8ByteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES ||
    sizeCeilingUtf8ByteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
  ) {
    throw new RangeError(
      `ActivityKit static and dynamic data exceeds ${ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES} UTF-8 bytes`,
    );
  }
  return Object.freeze({
    value: frozenValue,
    serialized,
    utf8ByteLength,
    activityData,
    activityDataUtf8ByteLength,
    activityDataSizeCeilingUtf8ByteLength: sizeCeilingUtf8ByteLength,
  });
}

/** Revalidates the exact bytes immediately before a future request description is built. */
export function verifiedActivityKitPayloadBody(
  payload: BuiltActivityKitPayload,
): string {
  if (payload == null || typeof payload !== "object") {
    throw new TypeError("ActivityKit payload is required");
  }
  const serialized = JSON.stringify(payload.value);
  if (serialized !== payload.serialized) {
    throw new RangeError("ActivityKit payload representation is inconsistent");
  }
  const byteLength = Buffer.byteLength(serialized, "utf8");
  if (
    byteLength !== payload.utf8ByteLength ||
    byteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
  ) {
    throw new RangeError("ActivityKit payload byte length is invalid");
  }
  const contentState = JSON.stringify(payload.value.aps["content-state"]);
  if (JSON.stringify(payload.activityData["content-state"]) !== contentState) {
    throw new RangeError("ActivityKit size context does not match its content state");
  }
  if (
    payload.value.aps.event === "start" &&
    JSON.stringify(payload.activityData.attributes) !==
      JSON.stringify(payload.value.aps.attributes)
  ) {
    throw new RangeError("ActivityKit size context does not match its attributes");
  }
  const activityDataUtf8ByteLength = Buffer.byteLength(
    JSON.stringify(payload.activityData),
    "utf8",
  );
  if (
    activityDataUtf8ByteLength !== payload.activityDataUtf8ByteLength ||
    activityDataUtf8ByteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
  ) {
    throw new RangeError("ActivityKit combined static and dynamic byte length is invalid");
  }
  const sizeCeilingUtf8ByteLength = activityDataSizeCeilingUtf8ByteLength(
    payload.activityData["content-state"],
  );
  if (
    sizeCeilingUtf8ByteLength !==
      payload.activityDataSizeCeilingUtf8ByteLength ||
    sizeCeilingUtf8ByteLength > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
  ) {
    throw new RangeError("ActivityKit V1 size ceiling is invalid");
  }
  return serialized;
}

export function buildActivityKitStartPayload(
  input: BuildActivityKitStartPayloadInput,
): BuiltActivityKitPayload<{ readonly aps: ActivityKitStartAps }> {
  const contentState = copiedWireState(input.contentState);
  const attributes = copiedAttributes(input.attributes, contentState);
  const fields = startModeFields(input.mode);
  return buildPayload({
    aps: {
      timestamp: activityKitEpochSeconds(input.generatedAt, "generatedAt"),
      event: "start",
      "content-state": contentState,
      "attributes-type": BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
      attributes,
      alert: normalizedAlert(input.alert, "alert"),
      ...fields,
    },
  }, attributes);
}

export function buildActivityKitUpdatePayload(
  input: BuildActivityKitUpdatePayloadInput,
): BuiltActivityKitPayload<{ readonly aps: ActivityKitUpdateAps }> {
  const contentState = copiedWireState(input.contentState);
  const attributes = copiedAttributes(input.attributes, contentState);
  const timestamp = activityKitEpochSeconds(input.generatedAt, "generatedAt");
  const staleDate =
    input.staleAt == null
      ? undefined
      : activityKitEpochSeconds(input.staleAt, "staleAt");
  if (staleDate != null && staleDate < timestamp) {
    throw new RangeError("staleAt cannot precede generatedAt");
  }
  return buildPayload({
    aps: {
      timestamp,
      event: "update",
      "content-state": contentState,
      ...(staleDate == null ? {} : { "stale-date": staleDate }),
      ...(input.alert == null ? {} : { alert: normalizedAlert(input.alert, "alert") }),
    },
  }, attributes);
}

export function buildActivityKitEndPayload(
  input: BuildActivityKitEndPayloadInput,
): BuiltActivityKitPayload<{ readonly aps: ActivityKitEndAps }> {
  const contentState = copiedWireState(input.contentState);
  const attributes = copiedAttributes(input.attributes, contentState);
  const dismissalDate =
    input.dismissalAt == null
      ? undefined
      : activityKitEpochSeconds(input.dismissalAt, "dismissalAt");
  return buildPayload({
    aps: {
      timestamp: activityKitEpochSeconds(input.generatedAt, "generatedAt"),
      event: "end",
      "content-state": contentState,
      ...(dismissalDate == null ? {} : { "dismissal-date": dismissalDate }),
      ...(input.alert == null ? {} : { alert: normalizedAlert(input.alert, "alert") }),
    },
  }, attributes);
}
