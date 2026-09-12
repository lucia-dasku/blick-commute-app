import { describe, expect, it } from "vitest";
import {
  ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES,
  activityKitEpochSeconds,
  buildActivityKitEndPayload,
  buildActivityKitStartPayload,
  buildActivityKitUpdatePayload,
  verifiedActivityKitPayloadBody,
  type ActivityKitStartMode,
} from "../src/liveCommute/apple/activityKitPayload.js";
import {
  BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
  createBlickLiveActivityAttributes,
  type BlickLiveActivityLineContentStateV1,
} from "../src/liveCommute/apple/liveActivityWireContract.js";

const EVENT_AT = new Date("2026-09-12T10:00:00.987Z");
const BINDING_ID = "68fdf0ad-e5b4-4cf1-a429-4b61a4144238";

function lineState(
  destination = "Stockholm Central",
): BlickLiveActivityLineContentStateV1 {
  return Object.freeze({
    schemaVersion: 1,
    commuteKind: "LINE_DIRECTION",
    freshness: "FRESH",
    sourceFetchedAt: 1_789_207_190,
    departures: Object.freeze([
      Object.freeze({
        departureId: "departure-1",
        lineDesignation: "Pendeltåg 41",
        direction: "Northbound",
        destination,
        scheduledAt: 1_789_207_800,
        expectedAt: 1_789_207_860,
        effectiveAt: 1_789_207_860,
        isCancelled: false,
        departureState: "EXPECTED",
        journeyState: "NORMAL",
        predictionState: "REALTIME",
      }),
    ]),
  });
}

function attributes() {
  return createBlickLiveActivityAttributes({
    bindingId: BINDING_ID,
    sessionRevision: 7,
    commuteKind: "LINE_DIRECTION",
  });
}

const ALERT = Object.freeze({ title: "Synthetic start", body: "Synthetic body" });

describe("ActivityKit payload builders", () => {
  it("builds the exact legacy remote-start shape with caller-provided alert", () => {
    const built = buildActivityKitStartPayload({
      generatedAt: EVENT_AT,
      contentState: lineState(),
      attributes: attributes(),
      alert: ALERT,
      mode: { kind: "DIRECT_LEGACY" },
    });

    expect(built.value).toEqual({
      aps: {
        timestamp: 1_789_207_200,
        event: "start",
        "content-state": lineState(),
        "attributes-type": BLICK_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        attributes: attributes(),
        alert: ALERT,
      },
    });
    expect(built.value.aps).not.toHaveProperty("input-push-token");
    expect(built.value.aps).not.toHaveProperty("input-push-channel");
    expect(built.value.aps["attributes-type"]).toBe(
      "BlickLiveActivityAttributes",
    );
    expect(verifiedActivityKitPayloadBody(built)).toBe(built.serialized);
    expect(built.utf8ByteLength).toBe(
      Buffer.byteLength(built.serialized, "utf8"),
    );
  });

  it("supports explicit iOS 18 direct-token and broadcast-channel starts", () => {
    const direct = buildActivityKitStartPayload({
      generatedAt: 1_789_207_200,
      contentState: lineState(),
      attributes: attributes(),
      alert: ALERT,
      mode: { kind: "DIRECT_IOS_18" },
    });
    expect(direct.value.aps["input-push-token"]).toBe(1);
    expect(direct.value.aps).not.toHaveProperty("input-push-channel");

    const channelId = Buffer.from("synthetic-channel", "utf8").toString("base64");
    const broadcast = buildActivityKitStartPayload({
      generatedAt: 1_789_207_200,
      contentState: lineState(),
      attributes: attributes(),
      alert: ALERT,
      mode: { kind: "BROADCAST_CHANNEL", channelId },
    });
    expect(broadcast.value.aps["input-push-channel"]).toBe(channelId);
    expect(broadcast.value.aps).not.toHaveProperty("input-push-token");
  });

  it("requires a start alert and rejects incompatible or invalid start modes", () => {
    expect(() =>
      buildActivityKitStartPayload({
        generatedAt: EVENT_AT,
        contentState: lineState(),
        attributes: attributes(),
        alert: undefined as never,
        mode: { kind: "DIRECT_LEGACY" },
      }),
    ).toThrow("alert is required");
    expect(() =>
      buildActivityKitStartPayload({
        generatedAt: EVENT_AT,
        contentState: lineState(),
        attributes: attributes(),
        alert: ALERT,
        mode: {
          kind: "DIRECT_IOS_18",
          channelId: Buffer.from("forged").toString("base64"),
        } as unknown as ActivityKitStartMode,
      }),
    ).toThrow("cannot include an input push channel");
    expect(() =>
      buildActivityKitStartPayload({
        generatedAt: EVENT_AT,
        contentState: lineState(),
        attributes: attributes(),
        alert: ALERT,
        mode: {
          kind: "BROADCAST_CHANNEL",
          channelId: Buffer.from("channel").toString("base64"),
          inputPushToken: true,
        } as unknown as ActivityKitStartMode,
      }),
    ).toThrow("cannot also request a direct update token");
    expect(() =>
      buildActivityKitStartPayload({
        generatedAt: EVENT_AT,
        contentState: lineState(),
        attributes: attributes(),
        alert: ALERT,
        mode: { kind: "BROADCAST_CHANNEL", channelId: "AA=" },
      }),
    ).toThrow("opaque base64");
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: EVENT_AT,
        attributes: attributes(),
        contentState: {
          ...lineState(),
          freshness: undefined,
        } as unknown as BlickLiveActivityLineContentStateV1,
      }),
    ).toThrow("freshness is invalid");
    const oneDeparture = lineState().departures[0]!;
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: EVENT_AT,
        attributes: attributes(),
        contentState: {
          ...lineState(),
          departures: [oneDeparture, oneDeparture, oneDeparture],
        },
      }),
    ).toThrow("LINE departure presentation bound");
  });

  it("builds update state with an explicit optional stale date and no automatic alert", () => {
    const built = buildActivityKitUpdatePayload({
      generatedAt: EVENT_AT,
      contentState: lineState(),
      attributes: attributes(),
      staleAt: new Date("2026-09-12T10:02:00.999Z"),
    });

    expect(built.value).toEqual({
      aps: {
        timestamp: 1_789_207_200,
        event: "update",
        "content-state": lineState(),
        "stale-date": 1_789_207_320,
      },
    });
    expect(built.value.aps).not.toHaveProperty("alert");
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: 1_789_207_200,
        contentState: lineState(),
        attributes: attributes(),
        staleAt: 1_789_207_199,
      }),
    ).toThrow("staleAt cannot precede generatedAt");
  });

  it("builds end with required final state and permits past immediate dismissal", () => {
    const finalState = lineState();
    const built = buildActivityKitEndPayload({
      generatedAt: 1_789_207_200,
      contentState: finalState,
      attributes: attributes(),
      dismissalAt: 1_789_207_100,
    });

    expect(built.value).toEqual({
      aps: {
        timestamp: 1_789_207_200,
        event: "end",
        "content-state": finalState,
        "dismissal-date": 1_789_207_100,
      },
    });
  });

  it("uses explicit valid whole-second instants", () => {
    expect(activityKitEpochSeconds(EVENT_AT)).toBe(1_789_207_200);
    expect(activityKitEpochSeconds(1_789_207_200)).toBe(1_789_207_200);
    expect(() => activityKitEpochSeconds(1.5)).toThrow("whole nonnegative");
    expect(() => activityKitEpochSeconds(-1)).toThrow("whole nonnegative");
    expect(() => activityKitEpochSeconds(new Date(Number.NaN))).toThrow(
      "valid absolute instant",
    );
  });

  it("counts final UTF-8 bytes, fails closed above 4 KB, and never truncates", () => {
    const longDestination = "Å🚆".repeat(900);
    const state = lineState(longDestination);
    const wouldBePayload = {
      aps: {
        timestamp: 1_789_207_200,
        event: "update",
        "content-state": state,
      },
    };
    const json = JSON.stringify(wouldBePayload);
    expect(json.length).toBeLessThan(ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES);
    expect(Buffer.byteLength(json, "utf8")).toBeGreaterThan(
      ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES,
    );

    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: 1_789_207_200,
        contentState: state,
        attributes: attributes(),
      }),
    ).toThrow("exceeds 4096 UTF-8 bytes");
    expect(state.departures[0]?.destination).toBe(longDestination);
  });

  it("enforces the combined static-attributes and dynamic-state 4 KB limit on updates", () => {
    let boundaryState: BlickLiveActivityLineContentStateV1 | undefined;
    for (let length = 3_000; length <= 4_096; length += 1) {
      const candidate = lineState("x".repeat(length));
      const updateBodyBytes = Buffer.byteLength(
        JSON.stringify({
          aps: {
            timestamp: 1_789_207_200,
            event: "update",
            "content-state": candidate,
          },
        }),
        "utf8",
      );
      const combinedDataBytes = Buffer.byteLength(
        JSON.stringify({
          attributes: attributes(),
          "content-state": candidate,
        }),
        "utf8",
      );
      if (
        updateBodyBytes <= ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES &&
        combinedDataBytes > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
      ) {
        boundaryState = candidate;
        break;
      }
    }
    expect(boundaryState).toBeDefined();
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: 1_789_207_200,
        contentState: boundaryState!,
        attributes: attributes(),
      }),
    ).toThrow("static and dynamic data exceeds 4096 UTF-8 bytes");
  });

  it("reserves the largest V1 revision when sizing shared broadcast state", () => {
    let boundaryState: BlickLiveActivityLineContentStateV1 | undefined;
    for (let length = 3_000; length <= 4_096; length += 1) {
      const candidate = lineState("x".repeat(length));
      const updateBodyBytes = Buffer.byteLength(
        JSON.stringify({
          aps: {
            timestamp: 1_789_207_200,
            event: "update",
            "content-state": candidate,
          },
        }),
        "utf8",
      );
      const currentRevisionDataBytes = Buffer.byteLength(
        JSON.stringify({
          attributes: attributes(),
          "content-state": candidate,
        }),
        "utf8",
      );
      const maximumRevisionDataBytes = Buffer.byteLength(
        JSON.stringify({
          attributes: {
            ...attributes(),
            sessionRevision: 2_147_483_647,
          },
          "content-state": candidate,
        }),
        "utf8",
      );
      if (
        updateBodyBytes <= ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES &&
        currentRevisionDataBytes <= ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES &&
        maximumRevisionDataBytes > ACTIVITYKIT_PAYLOAD_MAX_UTF8_BYTES
      ) {
        boundaryState = candidate;
        break;
      }
    }
    expect(boundaryState).toBeDefined();
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: 1_789_207_200,
        contentState: boundaryState!,
        attributes: attributes(),
      }),
    ).toThrow("static and dynamic data exceeds 4096 UTF-8 bytes");
  });

  it("copies caller values and never freezes them on success or size failure", () => {
    const mutableState = JSON.parse(JSON.stringify(lineState())) as BlickLiveActivityLineContentStateV1;
    const mutableAttributes = {
      schemaVersion: 1,
      bindingId: BINDING_ID,
      sessionRevision: 7,
      commuteKind: "LINE_DIRECTION",
    } as const;
    const built = buildActivityKitStartPayload({
      generatedAt: EVENT_AT,
      contentState: mutableState,
      attributes: mutableAttributes,
      alert: { title: "Synthetic start", body: "Synthetic body" },
      mode: { kind: "DIRECT_LEGACY" },
    });
    expect(Object.isFrozen(mutableState)).toBe(false);
    expect(Object.isFrozen(mutableState.departures[0])).toBe(false);
    expect(Object.isFrozen(mutableAttributes)).toBe(false);
    expect(built.value.aps["content-state"]).not.toBe(mutableState);
    expect(Object.isFrozen(built.value.aps["content-state"])).toBe(true);

    const stateWithExtra = Object.assign(JSON.parse(JSON.stringify(lineState())), {
      installationCredential: "must-not-cross-wire-boundary",
    }) as BlickLiveActivityLineContentStateV1;
    Object.assign(stateWithExtra.departures[0]!, {
      activityKitToken: "must-not-cross-wire-boundary",
    });
    const attributesWithExtra = Object.assign(
      {
        schemaVersion: 1,
        bindingId: BINDING_ID,
        sessionRevision: 7,
        commuteKind: "LINE_DIRECTION",
      } as const,
      { installationId: "must-not-cross-wire-boundary" },
    );
    const allowlisted = buildActivityKitStartPayload({
      generatedAt: EVENT_AT,
      contentState: stateWithExtra,
      attributes: attributesWithExtra,
      alert: ALERT,
      mode: { kind: "DIRECT_LEGACY" },
    });
    expect(allowlisted.serialized).not.toContain("must-not-cross-wire-boundary");
    expect(allowlisted.value.aps.attributes).not.toHaveProperty("installationId");
    expect(allowlisted.value.aps["content-state"]).not.toHaveProperty(
      "installationCredential",
    );

    const oversizedMutableState = JSON.parse(
      JSON.stringify(lineState("Å🚆".repeat(900))),
    ) as BlickLiveActivityLineContentStateV1;
    expect(() =>
      buildActivityKitUpdatePayload({
        generatedAt: EVENT_AT,
        contentState: oversizedMutableState,
        attributes: attributes(),
      }),
    ).toThrow("exceeds 4096 UTF-8 bytes");
    expect(Object.isFrozen(oversizedMutableState)).toBe(false);
    expect(Object.isFrozen(oversizedMutableState.departures[0])).toBe(false);
  });

  it("rejects a forged transport representation instead of trusting a stale byte count", () => {
    const built = buildActivityKitUpdatePayload({
      generatedAt: EVENT_AT,
      contentState: lineState(),
      attributes: attributes(),
    });
    expect(() =>
      verifiedActivityKitPayloadBody({
        ...built,
        serialized: `${built.serialized} `,
        utf8ByteLength: built.utf8ByteLength + 1,
      }),
    ).toThrow("representation is inconsistent");
    expect(() =>
      verifiedActivityKitPayloadBody({
        ...built,
        activityDataSizeCeilingUtf8ByteLength:
          built.activityDataSizeCeilingUtf8ByteLength - 1,
      }),
    ).toThrow("V1 size ceiling is invalid");
  });
});
