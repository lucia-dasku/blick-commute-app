import {
  canonicalLiveCommuteQueryKey,
  canonicalizeLiveCommuteQuery,
  createLiveCommuteSession,
  type CanonicalLiveCommuteQuery,
  type CanonicalLiveCommuteQueryKey,
  type LiveCommuteSession,
} from "./model.js";

export interface LiveCommuteAcquisitionGroup {
  readonly key: CanonicalLiveCommuteQueryKey;
  readonly query: CanonicalLiveCommuteQuery;
  readonly sessions: readonly LiveCommuteSession[];
}

/** A group whose sessions were checked again after acquisition, immediately before publish. */
export interface LiveCommutePublicationGroup extends LiveCommuteAcquisitionGroup {
  readonly validatedAt: string;
}

export interface LiveCommutePlan {
  readonly notStartedSessions: readonly LiveCommuteSession[];
  readonly activeSessions: readonly LiveCommuteSession[];
  readonly expiredSessions: readonly LiveCommuteSession[];
  readonly acquisitionGroups: readonly LiveCommuteAcquisitionGroup[];
}

interface MutableAcquisitionGroup {
  readonly key: CanonicalLiveCommuteQueryKey;
  readonly query: CanonicalLiveCommuteQuery;
  readonly sessions: LiveCommuteSession[];
}

/**
 * Partitions validated sessions using `[startsAt, endsAt)` and groups only active ones.
 * This function performs no I/O and does not read the wall clock; callers supply `now`.
 */
export function planLiveCommuteSessions(
  now: Date,
  sessions: readonly LiveCommuteSession[],
): LiveCommutePlan {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new RangeError("now must be a valid absolute Date");
  }

  const nowMillis = now.getTime();
  const notStartedSessions: LiveCommuteSession[] = [];
  const activeSessions: LiveCommuteSession[] = [];
  const expiredSessions: LiveCommuteSession[] = [];
  const groupsByKey = new Map<CanonicalLiveCommuteQueryKey, MutableAcquisitionGroup>();

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
    const key = canonicalLiveCommuteQueryKey(session.query);
    const existing = groupsByKey.get(key);
    if (existing != null) {
      existing.sessions.push(session);
    } else {
      groupsByKey.set(key, {
        key,
        query: canonicalizeLiveCommuteQuery(session.query),
        sessions: [session],
      });
    }
  }

  const acquisitionGroups = [...groupsByKey.values()].map((group) =>
    Object.freeze({
      key: group.key,
      query: group.query,
      sessions: Object.freeze([...group.sessions]),
    }),
  );

  return Object.freeze({
    notStartedSessions: Object.freeze(notStartedSessions),
    activeSessions: Object.freeze(activeSessions),
    expiredSessions: Object.freeze(expiredSessions),
    acquisitionGroups: Object.freeze(acquisitionGroups),
  });
}

/**
 * Revalidates an acquisition group at the publication instant. Acquisition is asynchronous,
 * so a session that was active in the original plan may have reached `endsAt` meanwhile.
 * A caller must skip publication when this returns `null`.
 */
export function prepareLiveCommutePublicationGroup(
  now: Date,
  acquisitionGroup: LiveCommuteAcquisitionGroup,
): LiveCommutePublicationGroup | null {
  const publicationPlan = planLiveCommuteSessions(now, acquisitionGroup.sessions);
  const currentGroup = publicationPlan.acquisitionGroups.find(
    (candidate) => candidate.key === acquisitionGroup.key,
  );
  if (currentGroup == null) return null;

  return Object.freeze({
    key: currentGroup.key,
    query: currentGroup.query,
    sessions: currentGroup.sessions,
    validatedAt: now.toISOString(),
  });
}
