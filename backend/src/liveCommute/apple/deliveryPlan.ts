import type {
  ReadyLiveCommutePublication,
  ReadyStaleLiveCommutePublication,
} from "../engine.js";
import type { AuthoritativeLiveCommutePublicationMetadata } from "../coordinator.js";
import type { LiveCommuteSessionVersionRef } from "../sessionStore.js";
import {
  activityKitEpochSeconds,
  buildActivityKitEndPayload,
  buildActivityKitStartPayload,
  buildActivityKitUpdatePayload,
  type ActivityKitAlert,
  type ActivityKitStartMode,
} from "./activityKitPayload.js";
import {
  createBroadcastLiveActivityRequestDescription,
  createDirectLiveActivityRequestDescription,
  type ApnsBroadcastLiveActivityPriority,
  type ApnsDirectLiveActivityPriority,
  type ApnsRequestDescription,
} from "./apnsProtocol.js";
import type { SensitiveApnsProviderToken } from "./apnsProviderToken.js";
import {
  type InternalBroadcastLiveActivityUpdateTarget,
  type InternalDirectLiveActivityUpdateTarget,
  type InternalLiveActivityStartTarget,
} from "./deliveryResolver.js";
import type { ApplePushEnvironment } from "./deliveryModel.js";
import {
  createBlickLiveActivityAttributes,
  mapLiveCommuteSnapshotToContentState,
} from "./liveActivityWireContract.js";

export type AuthoritativeReadyLiveCommutePublication =
  | (ReadyLiveCommutePublication & AuthoritativeLiveCommutePublicationMetadata)
  | (ReadyStaleLiveCommutePublication & AuthoritativeLiveCommutePublicationMetadata);

export type LiveActivityDeliveryPlanKind =
  | "START"
  | "DIRECT_UPDATE"
  | "BROADCAST_UPDATE"
  | "DIRECT_END"
  | "BROADCAST_END";

export interface LiveActivityDeliveryPlanCorrelation {
  readonly bindingId: string;
  readonly sessionVersion: LiveCommuteSessionVersionRef;
  /** Present only for a device-token request so a later decision can target that generation. */
  readonly tokenGeneration: {
    readonly clientGeneration: number;
    readonly serverRevision: number;
  } | null;
}

export interface LiveActivityDeliveryPlan {
  readonly kind: LiveActivityDeliveryPlanKind;
  readonly eventTimestamp: number;
  readonly correlation: LiveActivityDeliveryPlanCorrelation;
  readonly request: ApnsRequestDescription;
}

export type LiveActivityDeliveryPlanErrorCode =
  | "PUBLICATION_NOT_READY"
  | "PUBLICATION_TARGET_MISMATCH"
  | "TARGET_KIND_MISMATCH"
  | "START_MODE_MISMATCH"
  | "PROVIDER_TOKEN_UNAVAILABLE";

export class LiveActivityDeliveryPlanError extends Error {
  constructor(readonly code: LiveActivityDeliveryPlanErrorCode) {
    super(
      code === "PUBLICATION_NOT_READY"
        ? "Live Activity publication is not ready"
        : code === "PUBLICATION_TARGET_MISMATCH"
          ? "Live Activity publication does not match the delivery target"
          : code === "TARGET_KIND_MISMATCH"
            ? "Live Activity delivery target kind is incompatible"
            : code === "START_MODE_MISMATCH"
              ? "Live Activity start mode does not match the delivery strategy"
              : "APNs provider token is unavailable",
    );
    this.name = "LiveActivityDeliveryPlanError";
  }
}

interface DirectRequestConfiguration {
  readonly bundleId: string;
  readonly providerToken: SensitiveApnsProviderToken;
  readonly priority: ApnsDirectLiveActivityPriority;
  readonly expiration?: number;
  readonly apnsId?: string;
  readonly collapseId?: string;
}

interface BroadcastRequestConfiguration {
  readonly environment: ApplePushEnvironment;
  readonly bundleId: string;
  readonly providerToken: SensitiveApnsProviderToken;
  readonly channelId: string;
  readonly priority: ApnsBroadcastLiveActivityPriority;
  readonly expiration: number;
  readonly requestId?: string;
}

interface EventStateInput {
  readonly publication: AuthoritativeReadyLiveCommutePublication;
  /** Used both for final expiry projection and the ActivityKit event timestamp. */
  readonly generatedAt: Date;
}

export interface BuildLiveActivityStartPlanInput
  extends EventStateInput,
    DirectRequestConfiguration {
  readonly target: InternalLiveActivityStartTarget;
  readonly mode: ActivityKitStartMode;
  readonly alert: ActivityKitAlert;
}

export interface BuildDirectLiveActivityUpdatePlanInput
  extends EventStateInput,
    DirectRequestConfiguration {
  readonly target: InternalDirectLiveActivityUpdateTarget;
  readonly staleAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export interface BuildBroadcastLiveActivityUpdatePlanInput
  extends EventStateInput,
    BroadcastRequestConfiguration {
  readonly target: InternalBroadcastLiveActivityUpdateTarget;
  readonly staleAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export interface BuildDirectLiveActivityEndPlanInput
  extends EventStateInput,
    DirectRequestConfiguration {
  readonly target: InternalDirectLiveActivityUpdateTarget;
  readonly dismissalAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

export interface BuildBroadcastLiveActivityEndPlanInput
  extends EventStateInput,
    BroadcastRequestConfiguration {
  readonly target: InternalBroadcastLiveActivityUpdateTarget;
  readonly dismissalAt?: Date | number;
  readonly alert?: ActivityKitAlert;
}

type AnyDeliveryTarget =
  | InternalLiveActivityStartTarget
  | InternalDirectLiveActivityUpdateTarget
  | InternalBroadcastLiveActivityUpdateTarget;

function sameSessionVersion(
  left: LiveCommuteSessionVersionRef,
  right: LiveCommuteSessionVersionRef,
): boolean {
  return (
    left.installationId === right.installationId &&
    left.sessionId === right.sessionId &&
    left.revision === right.revision
  );
}

function contentStateFor(
  publication: AuthoritativeReadyLiveCommutePublication,
  target: AnyDeliveryTarget,
  generatedAt: Date,
) {
  if (publication.status !== "READY" && publication.status !== "READY_STALE") {
    throw new LiveActivityDeliveryPlanError("PUBLICATION_NOT_READY");
  }
  if (
    publication.group.key !== target.publicationKey ||
    publication.snapshot.kind !== publication.group.query.kind ||
    !publication.sessionVersions.some((version) =>
      sameSessionVersion(version, target.sessionVersion),
    )
  ) {
    throw new LiveActivityDeliveryPlanError("PUBLICATION_TARGET_MISMATCH");
  }
  return mapLiveCommuteSnapshotToContentState(publication.snapshot, generatedAt);
}

function revealedProviderToken(token: SensitiveApnsProviderToken): string {
  try {
    if (token == null || typeof token.revealForAuthorization !== "function") {
      throw new Error();
    }
    return token.revealForAuthorization();
  } catch {
    throw new LiveActivityDeliveryPlanError("PROVIDER_TOKEN_UNAVAILABLE");
  }
}

function correlationFor(
  target: AnyDeliveryTarget,
): LiveActivityDeliveryPlanCorrelation {
  const tokenGeneration =
    target.kind === "BROADCAST_CHANNEL_REQUIRED"
      ? null
      : Object.freeze({ ...target.tokenGeneration });
  return Object.freeze({
    bindingId: target.bindingId,
    sessionVersion: Object.freeze({ ...target.sessionVersion }),
    tokenGeneration,
  });
}

function plan(
  kind: LiveActivityDeliveryPlanKind,
  generatedAt: Date,
  target: AnyDeliveryTarget,
  request: ApnsRequestDescription,
): LiveActivityDeliveryPlan {
  return Object.freeze({
    kind,
    eventTimestamp: activityKitEpochSeconds(generatedAt, "generatedAt"),
    correlation: correlationFor(target),
    request,
  });
}

export function buildLiveActivityStartPlan(
  input: BuildLiveActivityStartPlanInput,
): LiveActivityDeliveryPlan {
  if (input.target.kind !== "START") {
    throw new LiveActivityDeliveryPlanError("TARGET_KIND_MISMATCH");
  }
  const directMode =
    input.mode.kind === "DIRECT_LEGACY" || input.mode.kind === "DIRECT_IOS_18";
  if (
    (input.target.strategy === "DIRECT_TOKEN" && !directMode) ||
    (input.target.strategy === "BROADCAST_CHANNEL" &&
      input.mode.kind !== "BROADCAST_CHANNEL") ||
    (input.target.strategy === "DIRECT_TOKEN" &&
      input.target.broadcastChannelRequirement !== "NONE") ||
    (input.target.strategy === "BROADCAST_CHANNEL" &&
      input.target.broadcastChannelRequirement !== "APNS_CHANNEL_REQUIRED")
  ) {
    throw new LiveActivityDeliveryPlanError("START_MODE_MISMATCH");
  }
  const contentState = contentStateFor(
    input.publication,
    input.target,
    input.generatedAt,
  );
  const payload = buildActivityKitStartPayload({
    generatedAt: input.generatedAt,
    contentState,
    attributes: createBlickLiveActivityAttributes({
      bindingId: input.target.bindingId,
      sessionRevision: input.target.sessionVersion.revision,
      commuteKind: contentState.commuteKind,
    }),
    alert: input.alert,
    mode: input.mode,
  });
  const request = createDirectLiveActivityRequestDescription({
    environment: input.target.environment,
    bundleId: input.bundleId,
    providerToken: revealedProviderToken(input.providerToken),
    deviceToken: input.target.pushToStartToken,
    payload,
    priority: input.priority,
    expiration: input.expiration,
    apnsId: input.apnsId,
    collapseId: input.collapseId,
  });
  return plan("START", input.generatedAt, input.target, request);
}

export function buildDirectLiveActivityUpdatePlan(
  input: BuildDirectLiveActivityUpdatePlanInput,
): LiveActivityDeliveryPlan {
  if (input.target.kind !== "DIRECT_UPDATE" || input.target.strategy !== "DIRECT_TOKEN") {
    throw new LiveActivityDeliveryPlanError("TARGET_KIND_MISMATCH");
  }
  const contentState = contentStateFor(
    input.publication,
    input.target,
    input.generatedAt,
  );
  const payload = buildActivityKitUpdatePayload({
    generatedAt: input.generatedAt,
    contentState,
    attributes: createBlickLiveActivityAttributes({
      bindingId: input.target.bindingId,
      sessionRevision: input.target.sessionVersion.revision,
      commuteKind: contentState.commuteKind,
    }),
    staleAt: input.staleAt,
    alert: input.alert,
  });
  const request = createDirectLiveActivityRequestDescription({
    environment: input.target.environment,
    bundleId: input.bundleId,
    providerToken: revealedProviderToken(input.providerToken),
    deviceToken: input.target.updateToken,
    payload,
    priority: input.priority,
    expiration: input.expiration,
    apnsId: input.apnsId,
    collapseId: input.collapseId,
  });
  return plan("DIRECT_UPDATE", input.generatedAt, input.target, request);
}

export function buildBroadcastLiveActivityUpdatePlan(
  input: BuildBroadcastLiveActivityUpdatePlanInput,
): LiveActivityDeliveryPlan {
  if (
    input.target.kind !== "BROADCAST_CHANNEL_REQUIRED" ||
    input.target.strategy !== "BROADCAST_CHANNEL"
  ) {
    throw new LiveActivityDeliveryPlanError("TARGET_KIND_MISMATCH");
  }
  const contentState = contentStateFor(
    input.publication,
    input.target,
    input.generatedAt,
  );
  const payload = buildActivityKitUpdatePayload({
    generatedAt: input.generatedAt,
    contentState,
    attributes: createBlickLiveActivityAttributes({
      bindingId: input.target.bindingId,
      sessionRevision: input.target.sessionVersion.revision,
      commuteKind: contentState.commuteKind,
    }),
    staleAt: input.staleAt,
    alert: input.alert,
  });
  const request = createBroadcastLiveActivityRequestDescription({
    environment: input.environment,
    bundleId: input.bundleId,
    providerToken: revealedProviderToken(input.providerToken),
    channelId: input.channelId,
    payload,
    priority: input.priority,
    expiration: input.expiration,
    requestId: input.requestId,
  });
  return plan("BROADCAST_UPDATE", input.generatedAt, input.target, request);
}

export function buildDirectLiveActivityEndPlan(
  input: BuildDirectLiveActivityEndPlanInput,
): LiveActivityDeliveryPlan {
  if (input.target.kind !== "DIRECT_UPDATE" || input.target.strategy !== "DIRECT_TOKEN") {
    throw new LiveActivityDeliveryPlanError("TARGET_KIND_MISMATCH");
  }
  const contentState = contentStateFor(
    input.publication,
    input.target,
    input.generatedAt,
  );
  const payload = buildActivityKitEndPayload({
    generatedAt: input.generatedAt,
    contentState,
    attributes: createBlickLiveActivityAttributes({
      bindingId: input.target.bindingId,
      sessionRevision: input.target.sessionVersion.revision,
      commuteKind: contentState.commuteKind,
    }),
    dismissalAt: input.dismissalAt,
    alert: input.alert,
  });
  const request = createDirectLiveActivityRequestDescription({
    environment: input.target.environment,
    bundleId: input.bundleId,
    providerToken: revealedProviderToken(input.providerToken),
    deviceToken: input.target.updateToken,
    payload,
    priority: input.priority,
    expiration: input.expiration,
    apnsId: input.apnsId,
    collapseId: input.collapseId,
  });
  return plan("DIRECT_END", input.generatedAt, input.target, request);
}

export function buildBroadcastLiveActivityEndPlan(
  input: BuildBroadcastLiveActivityEndPlanInput,
): LiveActivityDeliveryPlan {
  if (
    input.target.kind !== "BROADCAST_CHANNEL_REQUIRED" ||
    input.target.strategy !== "BROADCAST_CHANNEL"
  ) {
    throw new LiveActivityDeliveryPlanError("TARGET_KIND_MISMATCH");
  }
  const contentState = contentStateFor(
    input.publication,
    input.target,
    input.generatedAt,
  );
  const payload = buildActivityKitEndPayload({
    generatedAt: input.generatedAt,
    contentState,
    attributes: createBlickLiveActivityAttributes({
      bindingId: input.target.bindingId,
      sessionRevision: input.target.sessionVersion.revision,
      commuteKind: contentState.commuteKind,
    }),
    dismissalAt: input.dismissalAt,
    alert: input.alert,
  });
  const request = createBroadcastLiveActivityRequestDescription({
    environment: input.environment,
    bundleId: input.bundleId,
    providerToken: revealedProviderToken(input.providerToken),
    channelId: input.channelId,
    payload,
    priority: input.priority,
    expiration: input.expiration,
    requestId: input.requestId,
  });
  return plan("BROADCAST_END", input.generatedAt, input.target, request);
}
