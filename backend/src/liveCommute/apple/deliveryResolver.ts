import {
  liveCommutePublicationKey,
  type PublicationKey,
} from "../model.js";
import type { LiveCommuteSessionVersionRef } from "../sessionStore.js";
import {
  activityKitPushToStartProtectionContext,
  liveActivityUpdateProtectionContext,
  normalizedAppleDeliveryIdentifier,
  normalizedLiveActivityBindingId,
  type ApplePushEnvironment,
  type LiveActivityDeliveryBinding,
  type LiveActivityDeliveryStrategy,
  type StoredLiveActivityUpdateToken,
  type StoredPushToStartToken,
} from "./deliveryModel.js";
import type { LiveActivityDeliveryStore } from "./deliveryStore.js";
import type { ActivityKitTokenProtector } from "./tokenProtection.js";

export interface ResolveLiveActivityDeliveryTargetInput {
  readonly installationId: string;
  readonly bindingId: string;
}

interface InternalLiveActivityDeliveryTargetBase {
  readonly bindingId: string;
  readonly sessionVersion: LiveCommuteSessionVersionRef;
  readonly strategy: LiveActivityDeliveryStrategy;
  readonly publicationKey: PublicationKey;
  readonly resolvedAt: Date;
}

/** Sensitive internal value. Keep the plaintext only for a future immediate APNs call. */
export interface InternalLiveActivityStartTarget
  extends InternalLiveActivityDeliveryTargetBase {
  readonly kind: "START";
  readonly environment: ApplePushEnvironment;
  readonly pushToStartToken: Buffer;
  readonly broadcastChannelRequirement: "NONE" | "APNS_CHANNEL_REQUIRED";
}

/** Sensitive internal value. Keep the plaintext only for a future immediate APNs call. */
export interface InternalDirectLiveActivityUpdateTarget
  extends InternalLiveActivityDeliveryTargetBase {
  readonly kind: "DIRECT_UPDATE";
  readonly strategy: "DIRECT_TOKEN";
  readonly environment: ApplePushEnvironment;
  readonly updateToken: Buffer;
}

export interface InternalBroadcastLiveActivityUpdateTarget
  extends InternalLiveActivityDeliveryTargetBase {
  readonly kind: "BROADCAST_CHANNEL_REQUIRED";
  readonly strategy: "BROADCAST_CHANNEL";
}

export type InternalLiveActivityUpdateTarget =
  | InternalDirectLiveActivityUpdateTarget
  | InternalBroadcastLiveActivityUpdateTarget;

export interface LiveActivityDeliveryResolverOptions {
  readonly now?: () => Date;
}

interface ResolvedAuthority {
  readonly binding: LiveActivityDeliveryBinding;
  readonly sessionVersion: LiveCommuteSessionVersionRef;
  readonly publicationKey: PublicationKey;
  readonly resolvedAt: Date;
  readonly hasUpdateTokenHistory: boolean;
  readonly pushToStartToken: StoredPushToStartToken | undefined;
  readonly updateToken: StoredLiveActivityUpdateToken | undefined;
}

function validInstant(value: Date): Date {
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

export class LiveActivityDeliveryResolver {
  private readonly now: () => Date;

  constructor(
    private readonly store: LiveActivityDeliveryStore,
    private readonly tokenProtector: ActivityKitTokenProtector,
    options: LiveActivityDeliveryResolverOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
  }

  async resolveStartTarget(
    input: ResolveLiveActivityDeliveryTargetInput,
  ): Promise<InternalLiveActivityStartTarget | undefined> {
    const authority = await this.resolveAuthority(input, true);
    if (
      authority?.pushToStartToken == null ||
      authority.binding.appleActivityId != null ||
      authority.hasUpdateTokenHistory
    ) {
      return undefined;
    }
    const stored = authority.pushToStartToken;
    const plaintext = this.tokenProtector.unprotect(
      stored.protectedToken,
      activityKitPushToStartProtectionContext(
        authority.binding.installationId,
        stored.clientGeneration,
        stored.environment,
      ),
    );
    return Object.freeze({
      kind: "START",
      bindingId: authority.binding.bindingId,
      sessionVersion: authority.sessionVersion,
      strategy: authority.binding.strategy,
      publicationKey: authority.publicationKey,
      resolvedAt: authority.resolvedAt,
      environment: stored.environment,
      pushToStartToken: plaintext,
      broadcastChannelRequirement:
        authority.binding.strategy === "BROADCAST_CHANNEL"
          ? "APNS_CHANNEL_REQUIRED"
          : "NONE",
    });
  }

  async resolveUpdateTarget(
    input: ResolveLiveActivityDeliveryTargetInput,
  ): Promise<InternalLiveActivityUpdateTarget | undefined> {
    const authority = await this.resolveAuthority(input, false);
    if (authority == null) return undefined;
    const base = {
      bindingId: authority.binding.bindingId,
      sessionVersion: authority.sessionVersion,
      publicationKey: authority.publicationKey,
      resolvedAt: authority.resolvedAt,
    } as const;
    if (authority.binding.strategy === "BROADCAST_CHANNEL") {
      return Object.freeze({
        ...base,
        kind: "BROADCAST_CHANNEL_REQUIRED",
        strategy: "BROADCAST_CHANNEL",
      });
    }
    if (authority.updateToken == null) return undefined;
    const stored = authority.updateToken;
    const plaintext = this.tokenProtector.unprotect(
      stored.protectedToken,
      liveActivityUpdateProtectionContext(
        authority.binding.installationId,
        authority.binding.bindingId,
        stored.clientGeneration,
        stored.environment,
      ),
    );
    return Object.freeze({
      ...base,
      kind: "DIRECT_UPDATE",
      strategy: "DIRECT_TOKEN",
      environment: stored.environment,
      updateToken: plaintext,
    });
  }

  private async resolveAuthority(
    input: ResolveLiveActivityDeliveryTargetInput,
    includePushToStart: boolean,
  ): Promise<ResolvedAuthority | undefined> {
    const installationId = normalizedAppleDeliveryIdentifier(
      input.installationId,
      "installationId",
    );
    const bindingId = normalizedLiveActivityBindingId(input.bindingId);
    return await this.store.withInstallationTransaction(
      installationId,
      async (transaction) => {
        const installation = await transaction.getInstallation();
        if (installation?.state !== "ACTIVE") return undefined;
        const binding = await transaction.getDeliveryBinding(bindingId);
        if (
          binding == null ||
          binding.installationId !== installationId ||
          binding.lifecycle !== "PENDING_START"
        ) {
          return undefined;
        }
        const session = await transaction.getSession(binding.sessionId);
        if (
          session == null ||
          session.lifecycle !== "REGISTERED" ||
          session.revision !== binding.sessionRevision
        ) {
          return undefined;
        }
        const pushToStartToken = includePushToStart
          ? await transaction.getLatestPushToStartToken()
          : undefined;
        const updateToken =
          binding.strategy === "DIRECT_TOKEN"
            ? await transaction.getLatestUpdateToken(binding.bindingId)
            : undefined;
        const resolvedAt = validInstant(this.now());
        if (
          session.session.startsAt.getTime() > resolvedAt.getTime() ||
          session.session.endsAt.getTime() <= resolvedAt.getTime()
        ) {
          return undefined;
        }
        return Object.freeze({
          binding,
          sessionVersion: Object.freeze({
            installationId,
            sessionId: binding.sessionId,
            revision: binding.sessionRevision,
          }),
          publicationKey: liveCommutePublicationKey(session.session.query),
          resolvedAt,
          hasUpdateTokenHistory: updateToken != null,
          pushToStartToken:
            pushToStartToken?.lifecycle === "CURRENT" ? pushToStartToken : undefined,
          updateToken: updateToken?.lifecycle === "CURRENT" ? updateToken : undefined,
        });
      },
    );
  }
}

export function createLiveActivityDeliveryResolver(
  store: LiveActivityDeliveryStore,
  tokenProtector: ActivityKitTokenProtector,
  options?: LiveActivityDeliveryResolverOptions,
): LiveActivityDeliveryResolver {
  return new LiveActivityDeliveryResolver(store, tokenProtector, options);
}
