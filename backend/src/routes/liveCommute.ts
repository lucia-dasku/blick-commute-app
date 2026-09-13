import { Hono, type Context } from "hono";
import { z } from "zod";
import { AppError } from "../lib/errors.js";
import { journeyTransportModes } from "../models/common.js";
import { successEnvelope } from "../models/common.js";
import {
  LiveCommuteSessionServiceError,
  type LiveCommuteInstallationAuthentication,
  type LiveCommuteInstallationService,
} from "../liveCommute/installationService.js";
import {
  LiveActivityDeliveryServiceError,
  type LiveActivityDeliveryService,
} from "../liveCommute/apple/deliveryService.js";
import type { LiveActivityClientPublicationStateService } from "../liveCommute/apple/clientPublicationState.js";

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;
const BEARER_PATTERN = /^Bearer ([A-Za-z0-9_-]{43})$/;
const TOKEN_BASE64URL_MAX_CHARACTERS = 5_462;

const AbsoluteDateSchema = z
  .string()
  .datetime({ offset: true })
  .transform((value) => new Date(value));

const LineDirectionQuerySchema = z
  .object({
    kind: z.literal("LINE_DIRECTION"),
    siteId: z.number().int().positive().safe(),
    transportMode: z.string().trim().min(1).max(64),
    lineId: z.number().int().positive().safe().nullable(),
    directionCode: z.number().int().safe().nullable(),
  })
  .strict();

const ExactDestinationQuerySchema = z
  .object({
    kind: z.literal("EXACT_DESTINATION"),
    originId: z.string().trim().min(1).max(128),
    destinationId: z.string().trim().min(1).max(128),
    transportModes: z.array(z.enum(journeyTransportModes)).min(1).max(5),
    changesPreference: z.enum(["DIRECT_ONLY", "BOTH", "WITH_CHANGES_ONLY"]),
    searchUntil: AbsoluteDateSchema,
  })
  .strict();

const SessionSchema = z
  .object({
    sessionId: z.string().trim().min(1).max(256),
    routineId: z.string().trim().min(1).max(256),
    startsAt: AbsoluteDateSchema,
    endsAt: AbsoluteDateSchema,
    query: z.discriminatedUnion("kind", [
      LineDirectionQuerySchema,
      ExactDestinationQuerySchema,
    ]),
  })
  .strict();

const ReplaceSessionSchema = SessionSchema.omit({ sessionId: true })
  .extend({ expectedRevision: z.number().int().positive().safe() })
  .strict();

const CancelSessionSchema = z
  .object({ expectedRevision: z.number().int().positive().safe() })
  .strict();

const TokenSchema = z
  .object({
    token: z
      .string()
      .min(1)
      .max(TOKEN_BASE64URL_MAX_CHARACTERS)
      .regex(BASE64URL_PATTERN),
    clientGeneration: z.number().int().positive().max(2_147_483_647),
    environment: z.enum(["SANDBOX", "PRODUCTION"]),
  })
  .strict();

const InvalidateTokenSchema = z
  .object({
    expectedClientGeneration: z.number().int().positive().max(2_147_483_647),
  })
  .strict();

const DeliveryBindingSchema = z
  .object({
    sessionId: z.string().trim().min(1).max(256),
    sessionRevision: z.number().int().positive().max(2_147_483_647),
    strategy: z.literal("DIRECT_TOKEN"),
  })
  .strict();

const AppleActivityIdentifierSchema = z
  .object({ appleActivityId: z.string().trim().min(1).max(512) })
  .strict();

const EmptyBodySchema = z.object({}).strict();

const ClientPublicationStateSchema = z
  .object({
    capability: z.enum([
      "DIRECT_LEGACY",
      "DIRECT_IOS18",
      "BROADCAST_CAPABLE",
      "UNKNOWN",
    ]),
    frequentPushes: z.enum(["ENABLED", "DISABLED", "UNKNOWN"]),
    locale: z.enum(["en", "sv"]),
  })
  .strict();

type ScheduleCycle = () => Promise<unknown>;

export interface LiveCommuteHttpRouteDependencies {
  readonly installationService?: Pick<
    LiveCommuteInstallationService,
    | "registerInstallation"
    | "authenticateInstallation"
    | "registerSession"
    | "listSessions"
    | "replaceSession"
    | "cancelSession"
  >;
  readonly deliveryService?: Pick<
    LiveActivityDeliveryService,
    | "registerPushToStartToken"
    | "invalidatePushToStartToken"
    | "createDeliveryBinding"
    | "registerUpdateToken"
    | "invalidateUpdateToken"
    | "attachAppleActivityIdentifier"
    | "endDeliveryBinding"
    | "invalidateDeliveryBinding"
  >;
  readonly clientStateService?: Pick<
    LiveActivityClientPublicationStateService,
    "update"
  >;
  /** Omitted until the reviewed production publication-cycle runtime is active. */
  readonly ensureCycleScheduled?: ScheduleCycle;
}

function unavailable<T>(value: T | undefined, subject: string): T {
  if (value == null) {
    throw new AppError("SERVICE_UNAVAILABLE", `${subject} is temporarily unavailable`);
  }
  return value;
}

async function strictJson<Schema extends z.ZodTypeAny>(
  c: Context,
  schema: Schema,
): Promise<z.output<Schema>> {
  let raw: unknown;
  try {
    raw = await c.req.json();
  } catch {
    throw new AppError("VALIDATION_ERROR", "Request body must be valid JSON");
  }
  const parsed = schema.safeParse(raw);
  if (!parsed.success) {
    throw new AppError("VALIDATION_ERROR", "Invalid live commute request");
  }
  return parsed.data;
}

function authentication(c: Context): LiveCommuteInstallationAuthentication {
  const match = BEARER_PATTERN.exec(c.req.header("Authorization") ?? "");
  const installationId = c.req.param("installationId");
  if (match == null || installationId == null || installationId.trim().length === 0) {
    throw new AppError("AUTHENTICATION_ERROR", "Installation authentication failed");
  }
  return Object.freeze({
    installationId,
    bearerCredential: match[1] as string,
  });
}

async function authenticated(
  c: Context,
  dependencies: LiveCommuteHttpRouteDependencies,
): Promise<LiveCommuteInstallationAuthentication> {
  const auth = authentication(c);
  try {
    await unavailable(
      dependencies.installationService,
      "Live commute service",
    ).authenticateInstallation(auth);
  } catch (error) {
    throw mappedError(error);
  }
  return auth;
}

function decodedToken(value: string): Buffer {
  const decoded = Buffer.from(value, "base64url");
  if (decoded.length === 0 || decoded.toString("base64url") !== value) {
    throw new AppError("VALIDATION_ERROR", "Invalid ActivityKit token encoding");
  }
  return decoded;
}

function mappedError(error: unknown): AppError {
  if (error instanceof AppError) return error;
  if (error instanceof LiveCommuteSessionServiceError) {
    switch (error.code) {
      case "INSTALLATION_AUTHENTICATION_FAILED":
        return new AppError(
          "AUTHENTICATION_ERROR",
          "Installation authentication failed",
        );
      case "SESSION_NOT_FOUND":
        return new AppError("NOT_FOUND", "Live commute session was not found");
      case "SESSION_REGISTRATION_CONFLICT":
      case "SESSION_REVISION_CONFLICT":
      case "SESSION_OVERLAP":
      case "SESSION_CANCELLED":
        return new AppError("CONFLICT", error.message);
      case "INSTALLATION_REGISTRATION_FAILED":
        return new AppError(
          "SERVICE_UNAVAILABLE",
          "Live commute service is temporarily unavailable",
        );
    }
  }
  if (error instanceof LiveActivityDeliveryServiceError) {
    switch (error.code) {
      case "TOKEN_NOT_FOUND":
      case "DELIVERY_BINDING_NOT_FOUND":
        return new AppError("NOT_FOUND", error.message);
      case "TOKEN_GENERATION_CONFLICT":
      case "SESSION_NOT_AUTHORIZED":
      case "DELIVERY_BINDING_CONFLICT":
      case "DELIVERY_BINDING_INACTIVE":
      case "DELIVERY_STRATEGY_MISMATCH":
        return new AppError("CONFLICT", error.message);
      case "DELIVERY_BINDING_REGISTRATION_FAILED":
        return new AppError(
          "SERVICE_UNAVAILABLE",
          "Live Activity delivery is temporarily unavailable",
        );
    }
  }
  if (error instanceof TypeError || error instanceof RangeError) {
    return new AppError("VALIDATION_ERROR", "Invalid live commute request");
  }
  return new AppError("INTERNAL_ERROR", "Unexpected internal error");
}

async function routeOperation<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    throw mappedError(error);
  }
}

async function seedAfterCommittedSession(
  dependencies: LiveCommuteHttpRouteDependencies,
): Promise<void> {
  if (dependencies.ensureCycleScheduled == null) return;
  try {
    await dependencies.ensureCycleScheduled();
  } catch {
    throw new AppError(
      "SERVICE_UNAVAILABLE",
      "Live commute scheduling is temporarily unavailable",
    );
  }
}

export function createLiveCommuteRoute(
  dependencies: LiveCommuteHttpRouteDependencies,
) {
  const route = new Hono();
  route.use("*", async (c, next) => {
    c.header("Cache-Control", "no-store");
    await next();
  });

  route.post("/installations", async (c) => {
    const service = unavailable(
      dependencies.installationService,
      "Live commute service",
    );
    const issued = await routeOperation(async () => await service.registerInstallation());
    return c.json(successEnvelope(issued), 201);
  });

  route.post("/installations/:installationId/sessions", async (c) => {
    const auth = await authenticated(c, dependencies);
    const input = await strictJson(c, SessionSchema);
    const result = await routeOperation(async () =>
      await unavailable(
        dependencies.installationService,
        "Live commute service",
      ).registerSession(auth, input),
    );
    if (result.session.lifecycle === "REGISTERED") {
      await seedAfterCommittedSession(dependencies);
    }
    return c.json(successEnvelope(result), result.status === "REGISTERED" ? 201 : 200);
  });

  route.get("/installations/:installationId/sessions", async (c) => {
    const auth = await authenticated(c, dependencies);
    const sessions = await routeOperation(async () =>
      await unavailable(
        dependencies.installationService,
        "Live commute service",
      ).listSessions(auth),
    );
    return c.json(successEnvelope({ sessions }));
  });

  route.put(
    "/installations/:installationId/sessions/:sessionId",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, ReplaceSessionSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.installationService,
          "Live commute service",
        ).replaceSession(auth, {
          sessionId: c.req.param("sessionId"),
          ...body,
        }),
      );
      await seedAfterCommittedSession(dependencies);
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/sessions/:sessionId/cancel",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, CancelSessionSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.installationService,
          "Live commute service",
        ).cancelSession(auth, {
          sessionId: c.req.param("sessionId"),
          expectedRevision: body.expectedRevision,
        }),
      );
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/push-to-start-token",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, TokenSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).registerPushToStartToken(auth, {
          ...body,
          token: decodedToken(body.token),
        }),
      );
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/push-to-start-token/invalidate",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, InvalidateTokenSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).invalidatePushToStartToken(auth, body),
      );
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/delivery-bindings",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, DeliveryBindingSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).createDeliveryBinding(auth, body),
      );
      return c.json(successEnvelope(result), result.status === "CREATED" ? 201 : 200);
    },
  );

  route.post(
    "/installations/:installationId/delivery-bindings/:bindingId/update-token",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, TokenSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).registerUpdateToken(auth, {
          bindingId: c.req.param("bindingId"),
          ...body,
          token: decodedToken(body.token),
        }),
      );
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/delivery-bindings/:bindingId/update-token/invalidate",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, InvalidateTokenSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).invalidateUpdateToken(auth, {
          bindingId: c.req.param("bindingId"),
          ...body,
        }),
      );
      return c.json(successEnvelope(result));
    },
  );

  route.post(
    "/installations/:installationId/delivery-bindings/:bindingId/apple-activity",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, AppleActivityIdentifierSchema);
      const result = await routeOperation(async () =>
        await unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        ).attachAppleActivityIdentifier(auth, {
          bindingId: c.req.param("bindingId"),
          ...body,
        }),
      );
      return c.json(successEnvelope(result));
    },
  );

  for (const action of ["end", "invalidate"] as const) {
    route.post(
      `/installations/:installationId/delivery-bindings/:bindingId/${action}`,
      async (c) => {
        const auth = await authenticated(c, dependencies);
        await strictJson(c, EmptyBodySchema);
        const service = unavailable(
          dependencies.deliveryService,
          "Live Activity delivery",
        );
        const result = await routeOperation(async () =>
          action === "end"
            ? await service.endDeliveryBinding({ ...auth }, {
                bindingId: c.req.param("bindingId"),
              })
            : await service.invalidateDeliveryBinding({ ...auth }, {
                bindingId: c.req.param("bindingId"),
              }),
        );
        return c.json(successEnvelope(result));
      },
    );
  }

  route.put(
    "/installations/:installationId/publication-state",
    async (c) => {
      const auth = await authenticated(c, dependencies);
      const body = await strictJson(c, ClientPublicationStateSchema);
      const state = await routeOperation(async () =>
        await unavailable(
          dependencies.clientStateService,
          "Live Activity client state",
        ).update(auth, body),
      );
      return c.json(successEnvelope({ state }));
    },
  );

  return route;
}
