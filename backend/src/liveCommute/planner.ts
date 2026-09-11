import {
  canonicalizeAcquisitionQuery,
  canonicalizePublicationQuery,
  createLiveCommuteSession,
  liveCommuteAcquisitionKey,
  liveCommutePublicationKey,
  type AcquisitionKey,
  type CanonicalAcquisitionQuery,
  type CanonicalPublicationQuery,
  type LiveCommuteSession,
  type PublicationKey,
} from "./model.js";

/** Sessions that will receive one identical projected snapshot after acquisition. */
export interface LiveCommutePublicationPlan {
  readonly key: PublicationKey;
  readonly acquisitionKey: AcquisitionKey;
  readonly query: CanonicalPublicationQuery;
  readonly sessions: readonly LiveCommuteSession[];
}

/** One upstream request whose result may feed several publication groups. */
export interface LiveCommuteAcquisitionGroup {
  readonly key: AcquisitionKey;
  readonly query: CanonicalAcquisitionQuery;
  readonly sessions: readonly LiveCommuteSession[];
  readonly publicationGroups: readonly LiveCommutePublicationPlan[];
}

/** A publication group checked again after its asynchronous acquisition completed. */
export interface LiveCommutePublicationGroup extends LiveCommutePublicationPlan {
  readonly validatedAt: string;
}

export interface LiveCommutePlan {
  readonly notStartedSessions: readonly LiveCommuteSession[];
  readonly activeSessions: readonly LiveCommuteSession[];
  readonly expiredSessions: readonly LiveCommuteSession[];
  readonly acquisitionGroups: readonly LiveCommuteAcquisitionGroup[];
  readonly publicationGroups: readonly LiveCommutePublicationPlan[];
}

interface MutableAcquisitionGroup {
  readonly key: AcquisitionKey;
  readonly query: CanonicalAcquisitionQuery;
  readonly sessions: LiveCommuteSession[];
  readonly publicationKeys: Set<PublicationKey>;
}

interface MutablePublicationGroup {
  readonly key: PublicationKey;
  readonly acquisitionKey: AcquisitionKey;
  readonly query: CanonicalPublicationQuery;
  readonly sessions: LiveCommuteSession[];
}

function validNowMillis(now: Date): number {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new RangeError("now must be a valid absolute Date");
  }
  return now.getTime();
}

/**
 * Partitions validated sessions using `[startsAt, endsAt)`, groups active sessions by
 * upstream acquisition identity, and independently groups them by final publication identity.
 * This function performs no I/O and never reads the wall clock.
 */
export function planLiveCommuteSessions(
  now: Date,
  sessions: readonly LiveCommuteSession[],
): LiveCommutePlan {
  const nowMillis = validNowMillis(now);
  const notStartedSessions: LiveCommuteSession[] = [];
  const activeSessions: LiveCommuteSession[] = [];
  const expiredSessions: LiveCommuteSession[] = [];
  const acquisitionsByKey = new Map<AcquisitionKey, MutableAcquisitionGroup>();
  const publicationsByKey = new Map<PublicationKey, MutablePublicationGroup>();

  for (const input of sessions) {
    const session = createLiveCommuteSession(input);
    if (nowMillis < session.startsAt.getTime()) {
      notStartedSessions.push(session);
      continue;
    }
    if (nowMillis >= session.endsAt.getTime()) {
      expiredSessions.push(session);
      continue;
    }

    activeSessions.push(session);
    const acquisitionKey = liveCommuteAcquisitionKey(session.query);
    const publicationKey = liveCommutePublicationKey(session.query);

    const publication = publicationsByKey.get(publicationKey);
    if (publication == null) {
      publicationsByKey.set(publicationKey, {
        key: publicationKey,
        acquisitionKey,
        query: canonicalizePublicationQuery(session.query),
        sessions: [session],
      });
    } else {
      publication.sessions.push(session);
    }

    const acquisition = acquisitionsByKey.get(acquisitionKey);
    if (acquisition == null) {
      acquisitionsByKey.set(acquisitionKey, {
        key: acquisitionKey,
        query: canonicalizeAcquisitionQuery(session.query),
        sessions: [session],
        publicationKeys: new Set([publicationKey]),
      });
    } else {
      acquisition.sessions.push(session);
      acquisition.publicationKeys.add(publicationKey);
    }
  }

  const publicationGroups = [...publicationsByKey.values()].map((group) =>
    Object.freeze({
      key: group.key,
      acquisitionKey: group.acquisitionKey,
      query: group.query,
      sessions: Object.freeze([...group.sessions]),
    }),
  );
  const frozenPublicationsByKey = new Map(
    publicationGroups.map((group) => [group.key, group]),
  );

  const acquisitionGroups = [...acquisitionsByKey.values()].map((group) =>
    Object.freeze({
      key: group.key,
      query: group.query,
      sessions: Object.freeze([...group.sessions]),
      publicationGroups: Object.freeze(
        [...group.publicationKeys].map((key) => frozenPublicationsByKey.get(key)!),
      ),
    }),
  );

  return Object.freeze({
    notStartedSessions: Object.freeze(notStartedSessions),
    activeSessions: Object.freeze(activeSessions),
    expiredSessions: Object.freeze(expiredSessions),
    acquisitionGroups: Object.freeze(acquisitionGroups),
    publicationGroups: Object.freeze(publicationGroups),
  });
}

/**
 * Revalidates one final-state group at the publication instant. A session can expire while
 * its shared upstream request is in flight, so callers must skip a `null` result.
 */
export function prepareLiveCommutePublicationGroup(
  now: Date,
  publicationGroup: LiveCommutePublicationPlan,
): LiveCommutePublicationGroup | null {
  const publicationPlan = planLiveCommuteSessions(now, publicationGroup.sessions);
  const currentGroup = publicationPlan.publicationGroups.find(
    (candidate) =>
      candidate.key === publicationGroup.key &&
      candidate.acquisitionKey === publicationGroup.acquisitionKey,
  );
  if (currentGroup == null) return null;

  return Object.freeze({
    ...currentGroup,
    validatedAt: new Date(validNowMillis(now)).toISOString(),
  });
}
