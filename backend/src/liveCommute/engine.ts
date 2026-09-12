import { normalizeDeparturesResponse } from "../normalize/normalizeDeparture.js";
import {
  acquireAuthoritativeLiveJourneys,
  type AuthoritativeLiveJourneys,
} from "../services/liveJourneyAcquisition.js";
import type { SlJourneyPlannerClient } from "../services/slJourneyPlannerClient.js";
import type { SlTransportClient } from "../services/slTransportClient.js";
import type { DeparturesResponse } from "../models/departure.js";
import type {
  AcquisitionKey,
  LiveCommuteSession,
  PublicationKey,
} from "./model.js";
import {
  planLiveCommuteSessions,
  prepareLiveCommutePublicationGroup,
  type LiveCommuteAcquisitionGroup,
  type LiveCommutePublicationGroup,
} from "./planner.js";
import type { LiveCommutePreviousSnapshotSource } from "./ports.js";
import {
  buildExactDestinationLiveCommuteSnapshot,
  buildLineDirectionLiveCommuteSnapshot,
  liveCommuteSnapshotContentChanged,
  reprojectStaleLiveCommuteSnapshot,
  type LiveCommuteSnapshot,
} from "./snapshot.js";

export interface RunLiveCommuteTickInput {
  readonly sessions: readonly LiveCommuteSession[];
  /** Injectable clock read for planning, each acquisition outcome, and final publication. */
  readonly now: () => Date;
  readonly transportClient: SlTransportClient;
  readonly journeyClient: SlJourneyPlannerClient;
  readonly previousSnapshots?: LiveCommutePreviousSnapshotSource;
  /**
   * Optional authoritative lifecycle check. It runs once after every acquisition settles and
   * before the final publication clock is read. Only identities from the original plan are
   * retained; returning a different session specification can never retarget acquired state.
   */
  readonly revalidateSessions?: LiveCommuteSessionRevalidator;
}

export interface LiveCommuteSessionIdentity {
  readonly installationId: string;
  readonly sessionId: string;
}

export type LiveCommuteSessionRevalidator = (
  sessions: readonly LiveCommuteSession[],
) => Promise<readonly LiveCommuteSessionIdentity[]>;

export interface AcquiredLiveCommuteGroup {
  readonly status: "ACQUIRED";
  readonly key: AcquisitionKey;
  readonly completedAt: string;
  readonly sourceFetchedAt: string;
  readonly publicationKeys: readonly PublicationKey[];
}

export interface FailedLiveCommuteGroup {
  readonly status: "ACQUISITION_FAILED";
  readonly key: AcquisitionKey;
  readonly completedAt: string;
  readonly publicationKeys: readonly PublicationKey[];
}

export type LiveCommuteAcquisitionOutcome =
  | AcquiredLiveCommuteGroup
  | FailedLiveCommuteGroup;

interface LiveCommuteReadyPublicationBase {
  readonly group: LiveCommutePublicationGroup;
  readonly snapshot: LiveCommuteSnapshot;
  /** Visible commute semantics only; this is not a delivery-frequency decision. */
  readonly contentChanged: boolean;
}

export interface ReadyLiveCommutePublication extends LiveCommuteReadyPublicationBase {
  readonly status: "READY";
}

export interface ReadyStaleLiveCommutePublication extends LiveCommuteReadyPublicationBase {
  readonly status: "READY_STALE";
}

export interface AcquisitionFailedLiveCommutePublication {
  readonly status: "ACQUISITION_FAILED";
  readonly group: LiveCommutePublicationGroup;
}

export interface FailedLiveCommutePublication {
  readonly status: "PUBLICATION_FAILED";
  readonly group: LiveCommutePublicationGroup;
}

export type LiveCommutePublicationOutcome =
  | ReadyLiveCommutePublication
  | ReadyStaleLiveCommutePublication
  | AcquisitionFailedLiveCommutePublication
  | FailedLiveCommutePublication;

export interface LiveCommuteTickResult {
  readonly plannedAt: string;
  readonly acquisitions: readonly LiveCommuteAcquisitionOutcome[];
  readonly publications: readonly LiveCommutePublicationOutcome[];
}

type AcquiredTransitState =
  | {
      readonly kind: "LINE_DIRECTION";
      readonly state: DeparturesResponse;
    }
  | {
      readonly kind: "EXACT_DESTINATION";
      readonly state: AuthoritativeLiveJourneys;
    };

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function publicationKeys(group: LiveCommuteAcquisitionGroup): readonly PublicationKey[] {
  return Object.freeze(group.publicationGroups.map((publication) => publication.key));
}

function sessionIdentityKey(session: LiveCommuteSessionIdentity): string {
  return JSON.stringify([session.installationId, session.sessionId]);
}

function retainRevalidatedSessions(
  sessions: readonly LiveCommuteSession[],
  retainedSessionKeys: ReadonlySet<string> | undefined,
): readonly LiveCommuteSession[] {
  if (retainedSessionKeys == null) return sessions;
  return Object.freeze(
    sessions.filter((session) => retainedSessionKeys.has(sessionIdentityKey(session))),
  );
}

function previousSnapshot(
  source: LiveCommutePreviousSnapshotSource | undefined,
  key: PublicationKey,
  kind: LiveCommuteSnapshot["kind"],
): LiveCommuteSnapshot | undefined {
  const previous = source?.getPreviousSnapshot(key);
  return previous?.kind === kind ? previous : undefined;
}

function freshSnapshotFor(
  group: LiveCommutePublicationGroup,
  state: AcquiredTransitState,
  generatedAt: Date,
): LiveCommuteSnapshot {
  if (group.query.kind === "LINE_DIRECTION" && state.kind === "LINE_DIRECTION") {
    return buildLineDirectionLiveCommuteSnapshot(group.query, state.state, generatedAt);
  }
  if (group.query.kind === "EXACT_DESTINATION" && state.kind === "EXACT_DESTINATION") {
    return buildExactDestinationLiveCommuteSnapshot(
      {
        fetchedAt: state.state.fetchedAt.toISOString(),
        journeys: state.state.journeys.map(({ role, journey }) => ({
          ...journey,
          role,
        })),
      },
      generatedAt,
    );
  }
  throw new Error("acquired transit state does not match its publication group");
}

function readyPublications(
  group: LiveCommuteAcquisitionGroup,
  state: AcquiredTransitState,
  publicationAt: Date,
  previousSnapshots: LiveCommutePreviousSnapshotSource | undefined,
  retainedSessionKeys: ReadonlySet<string> | undefined,
): readonly LiveCommutePublicationOutcome[] {
  const publications: LiveCommutePublicationOutcome[] = [];
  for (const planned of group.publicationGroups) {
    const ready = prepareLiveCommutePublicationGroup(publicationAt, {
      ...planned,
      sessions: retainRevalidatedSessions(planned.sessions, retainedSessionKeys),
    });
    if (ready == null) continue;

    try {
      const snapshot = freshSnapshotFor(ready, state, publicationAt);
      let contentChanged = true;
      try {
        const previous = previousSnapshot(previousSnapshots, ready.key, snapshot.kind);
        contentChanged = liveCommuteSnapshotContentChanged(previous, snapshot);
      } catch {
        // History is optional comparison input; fresh transit state remains publishable.
      }
      publications.push(
        Object.freeze({
          status: "READY",
          group: ready,
          snapshot,
          contentChanged,
        }),
      );
    } catch {
      publications.push(Object.freeze({ status: "PUBLICATION_FAILED", group: ready }));
    }
  }
  return Object.freeze(publications);
}

function failedPublications(
  group: LiveCommuteAcquisitionGroup,
  publicationAt: Date,
  previousSnapshots: LiveCommutePreviousSnapshotSource | undefined,
  retainedSessionKeys: ReadonlySet<string> | undefined,
): readonly LiveCommutePublicationOutcome[] {
  const publications: LiveCommutePublicationOutcome[] = [];
  for (const planned of group.publicationGroups) {
    const ready = prepareLiveCommutePublicationGroup(publicationAt, {
      ...planned,
      sessions: retainRevalidatedSessions(planned.sessions, retainedSessionKeys),
    });
    if (ready == null) continue;

    try {
      const previous = previousSnapshot(previousSnapshots, ready.key, ready.query.kind);
      if (previous == null) {
        publications.push(Object.freeze({ status: "ACQUISITION_FAILED", group: ready }));
        continue;
      }

      const snapshot = reprojectStaleLiveCommuteSnapshot(previous, publicationAt);
      publications.push(
        Object.freeze({
          status: "READY_STALE",
          group: ready,
          snapshot,
          contentChanged: liveCommuteSnapshotContentChanged(previous, snapshot),
        }),
      );
    } catch {
      publications.push(Object.freeze({ status: "PUBLICATION_FAILED", group: ready }));
    }
  }
  return Object.freeze(publications);
}

async function acquireGroup(
  group: LiveCommuteAcquisitionGroup,
  planningAt: Date,
  input: RunLiveCommuteTickInput,
): Promise<
  | {
      readonly group: LiveCommuteAcquisitionGroup;
      readonly acquisition: AcquiredLiveCommuteGroup;
      readonly state: AcquiredTransitState;
    }
  | {
      readonly group: LiveCommuteAcquisitionGroup;
      readonly acquisition: FailedLiveCommuteGroup;
    }
> {
  try {
    let state: AcquiredTransitState;
    if (group.query.kind === "LINE_DIRECTION") {
      const raw = await input.transportClient.fetchDepartures(group.query.siteId);
      const completedAt = readClock(input.now);
      state = {
        kind: "LINE_DIRECTION",
        state: normalizeDeparturesResponse(group.query.siteId, raw, completedAt),
      };
      return {
        group,
        acquisition: Object.freeze({
          status: "ACQUIRED",
          key: group.key,
          completedAt: completedAt.toISOString(),
          sourceFetchedAt: state.state.fetchedAt,
          publicationKeys: publicationKeys(group),
        }),
        state,
      };
    }

    state = {
      kind: "EXACT_DESTINATION",
      state: await acquireAuthoritativeLiveJourneys(input.journeyClient, {
        originId: group.query.originId,
        destinationId: group.query.destinationId,
        transportModes: group.query.transportModes,
        changesPreference: group.query.changesPreference,
        searchUntil: new Date(group.query.searchUntil),
        fetchedAt: planningAt,
        laterJourneyCount: group.query.laterJourneyCount,
      }),
    };
    const completedAt = readClock(input.now);
    return {
      group,
      acquisition: Object.freeze({
        status: "ACQUIRED",
        key: group.key,
        completedAt: completedAt.toISOString(),
        sourceFetchedAt: state.state.fetchedAt.toISOString(),
        publicationKeys: publicationKeys(group),
      }),
      state,
    };
  } catch {
    const completedAt = readClock(input.now);
    return {
      group,
      acquisition: Object.freeze({
        status: "ACQUISITION_FAILED",
        key: group.key,
        completedAt: completedAt.toISOString(),
        publicationKeys: publicationKeys(group),
      }),
    };
  }
}

/**
 * Executes one scheduler-independent live-commute tick. It acquires once per acquisition
 * group, projects every dependent publication group, and returns results without publishing.
 */
export async function runLiveCommuteTick(
  input: RunLiveCommuteTickInput,
): Promise<LiveCommuteTickResult> {
  const planningAt = readClock(input.now);
  const plan = planLiveCommuteSessions(planningAt, input.sessions);
  const executed = await Promise.all(
    plan.acquisitionGroups.map((group) => acquireGroup(group, planningAt, input)),
  );
  let retainedSessionKeys: ReadonlySet<string> | undefined;
  if (input.revalidateSessions != null && plan.activeSessions.length > 0) {
    const revalidatedSessions = await input.revalidateSessions(plan.activeSessions);
    retainedSessionKeys = new Set(revalidatedSessions.map(sessionIdentityKey));
  }
  const publicationAt = executed.length === 0 ? planningAt : readClock(input.now);
  const publications = executed.flatMap((result) =>
    "state" in result
      ? readyPublications(
          result.group,
          result.state,
          publicationAt,
          input.previousSnapshots,
          retainedSessionKeys,
        )
      : failedPublications(
          result.group,
          publicationAt,
          input.previousSnapshots,
          retainedSessionKeys,
        ),
  );

  return Object.freeze({
    plannedAt: planningAt.toISOString(),
    acquisitions: Object.freeze(executed.map((result) => result.acquisition)),
    publications: Object.freeze(publications),
  });
}
