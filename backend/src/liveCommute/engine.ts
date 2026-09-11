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
  type LiveCommutePlan,
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
  /** Injectable clock read once for planning and again after each acquisition finishes. */
  readonly now: () => Date;
  readonly transportClient: SlTransportClient;
  readonly journeyClient: SlJourneyPlannerClient;
  readonly previousSnapshots?: LiveCommutePreviousSnapshotSource;
}

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
  readonly plan: LiveCommutePlan;
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
  completedAt: Date,
  previousSnapshots: LiveCommutePreviousSnapshotSource | undefined,
): readonly LiveCommutePublicationOutcome[] {
  const publications: LiveCommutePublicationOutcome[] = [];
  for (const planned of group.publicationGroups) {
    const ready = prepareLiveCommutePublicationGroup(completedAt, planned);
    if (ready == null) continue;

    try {
      const snapshot = freshSnapshotFor(ready, state, completedAt);
      const previous = previousSnapshot(previousSnapshots, ready.key, snapshot.kind);
      publications.push(
        Object.freeze({
          status: "READY",
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

function failedPublications(
  group: LiveCommuteAcquisitionGroup,
  completedAt: Date,
  previousSnapshots: LiveCommutePreviousSnapshotSource | undefined,
): readonly LiveCommutePublicationOutcome[] {
  const publications: LiveCommutePublicationOutcome[] = [];
  for (const planned of group.publicationGroups) {
    const ready = prepareLiveCommutePublicationGroup(completedAt, planned);
    if (ready == null) continue;

    try {
      const previous = previousSnapshot(previousSnapshots, ready.key, ready.query.kind);
      if (previous == null) {
        publications.push(Object.freeze({ status: "ACQUISITION_FAILED", group: ready }));
        continue;
      }

      const snapshot = reprojectStaleLiveCommuteSnapshot(previous, completedAt);
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
): Promise<{
  readonly acquisition: LiveCommuteAcquisitionOutcome;
  readonly publications: readonly LiveCommutePublicationOutcome[];
}> {
  try {
    let state: AcquiredTransitState;
    if (group.query.kind === "LINE_DIRECTION") {
      const raw = await input.transportClient.fetchDepartures(group.query.siteId);
      const completedAt = readClock(input.now);
      state = {
        kind: "LINE_DIRECTION",
        state: normalizeDeparturesResponse(group.query.siteId, raw, completedAt),
      };
      const publications = readyPublications(
        group,
        state,
        completedAt,
        input.previousSnapshots,
      );
      return {
        acquisition: Object.freeze({
          status: "ACQUIRED",
          key: group.key,
          completedAt: completedAt.toISOString(),
          sourceFetchedAt: state.state.fetchedAt,
          publicationKeys: publicationKeys(group),
        }),
        publications,
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
    const publications = readyPublications(
      group,
      state,
      completedAt,
      input.previousSnapshots,
    );
    return {
      acquisition: Object.freeze({
        status: "ACQUIRED",
        key: group.key,
        completedAt: completedAt.toISOString(),
        sourceFetchedAt: state.state.fetchedAt.toISOString(),
        publicationKeys: publicationKeys(group),
      }),
      publications,
    };
  } catch {
    const completedAt = readClock(input.now);
    return {
      acquisition: Object.freeze({
        status: "ACQUISITION_FAILED",
        key: group.key,
        completedAt: completedAt.toISOString(),
        publicationKeys: publicationKeys(group),
      }),
      publications: failedPublications(group, completedAt, input.previousSnapshots),
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

  return Object.freeze({
    plannedAt: planningAt.toISOString(),
    plan,
    acquisitions: Object.freeze(executed.map((result) => result.acquisition)),
    publications: Object.freeze(executed.flatMap((result) => result.publications)),
  });
}
