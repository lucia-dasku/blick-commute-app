import {
  runLiveCommuteTick,
  type LiveCommutePublicationOutcome,
  type LiveCommuteTickResult,
  type RunLiveCommuteTickInput,
} from "./engine.js";
import {
  createStoredLiveCommuteSession,
  liveCommuteSessionIdentityKey,
  liveCommuteSessionSpecificationEquals,
  liveCommuteStoredSessionMatchesVersion,
  liveCommuteSessionVersionRef,
  type LiveCommuteSessionStore,
  type LiveCommuteSessionVersionRef,
  type StoredLiveCommuteSession,
} from "./sessionStore.js";

const AUTHORITATIVE_VERIFICATION_MESSAGE =
  "Live commute session authority could not be verified";

export class LiveCommuteAuthoritativeVerificationError extends Error {
  readonly code = "LIVE_COMMUTE_AUTHORITATIVE_VERIFICATION_FAILED" as const;

  constructor() {
    super(AUTHORITATIVE_VERIFICATION_MESSAGE);
    this.name = "LiveCommuteAuthoritativeVerificationError";
  }
}

export interface RunStoredLiveCommuteTickInput
  extends Omit<RunLiveCommuteTickInput, "sessions" | "revalidateSessions"> {
  readonly sessionStore: LiveCommuteSessionStore;
}

export interface AuthoritativeLiveCommutePublicationMetadata {
  /** Session revisions that a future dispatcher must authoritatively check again. */
  readonly sessionVersions: readonly LiveCommuteSessionVersionRef[];
  /**
   * Application clock read after the store check completes and used for final projection.
   * This is not a database snapshot timestamp or an authorization lease.
   */
  readonly authorityCheckCompletedAt: string;
}

export type AuthoritativeLiveCommutePublicationOutcome =
  LiveCommutePublicationOutcome & AuthoritativeLiveCommutePublicationMetadata;

export interface StoredLiveCommuteTickResult
  extends Omit<LiveCommuteTickResult, "publications"> {
  readonly publications: readonly AuthoritativeLiveCommutePublicationOutcome[];
}

function readClock(now: () => Date): Date {
  const value = now();
  if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
    throw new RangeError("now() must return a valid absolute Date");
  }
  return new Date(value.getTime());
}

function recordsByIdentity(
  records: readonly StoredLiveCommuteSession[],
): ReadonlyMap<string, StoredLiveCommuteSession> {
  const indexed = new Map<string, StoredLiveCommuteSession>();
  for (const input of records) {
    const record = createStoredLiveCommuteSession(input);
    if (record.lifecycle !== "REGISTERED") {
      throw new LiveCommuteAuthoritativeVerificationError();
    }
    const key = liveCommuteSessionIdentityKey(record.session);
    if (indexed.has(key)) throw new LiveCommuteAuthoritativeVerificationError();
    indexed.set(key, record);
  }
  return indexed;
}

async function listEligibleSessions(
  store: LiveCommuteSessionStore,
  at: Date,
): Promise<readonly StoredLiveCommuteSession[]> {
  try {
    return await store.listEligibleSessions(at);
  } catch {
    throw new LiveCommuteAuthoritativeVerificationError();
  }
}

/**
 * Runs the existing acquisition/projection engine over stored concrete sessions. Store reads
 * bracket asynchronous transit work; no database transaction or lock spans acquisition.
 */
export async function runStoredLiveCommuteTick(
  input: RunStoredLiveCommuteTickInput,
): Promise<StoredLiveCommuteTickResult> {
  const initialRecords = await listEligibleSessions(input.sessionStore, readClock(input.now));
  let initialByIdentity: ReadonlyMap<string, StoredLiveCommuteSession>;
  try {
    initialByIdentity = recordsByIdentity(initialRecords);
  } catch {
    throw new LiveCommuteAuthoritativeVerificationError();
  }
  const capturedRecords = Object.freeze([...initialByIdentity.values()]);

  const authorizedByIdentity = new Map<string, StoredLiveCommuteSession>();
  const result = await runLiveCommuteTick({
    now: input.now,
    transportClient: input.transportClient,
    journeyClient: input.journeyClient,
    previousSnapshots: input.previousSnapshots,
    sessions: Object.freeze(capturedRecords.map((record) => record.session)),
    revalidateSessions: async (sessions) => {
      try {
        const expectedRecords = sessions.map((session) => {
          const record = initialByIdentity.get(liveCommuteSessionIdentityKey(session));
          if (
            record == null ||
            !liveCommuteSessionSpecificationEquals(session, record.session)
          ) {
            throw new LiveCommuteAuthoritativeVerificationError();
          }
          return record;
        });
        const expectedRefs = Object.freeze(
          expectedRecords.map(liveCommuteSessionVersionRef),
        );
        const currentRecords = await input.sessionStore.revalidateSessionVersions(expectedRefs);
        const currentByIdentity = recordsByIdentity(currentRecords);
        const authorized = sessions.filter((session) => {
          const key = liveCommuteSessionIdentityKey(session);
          const expected = initialByIdentity.get(key);
          const current = currentByIdentity.get(key);
          if (
            expected == null ||
            current == null ||
            current.lifecycle !== "REGISTERED" ||
            !liveCommuteStoredSessionMatchesVersion(expected, current)
          ) {
            return false;
          }
          authorizedByIdentity.set(key, current);
          return true;
        });
        return Object.freeze(
          authorized.map(({ installationId, sessionId }) =>
            Object.freeze({ installationId, sessionId }),
          ),
        );
      } catch {
        throw new LiveCommuteAuthoritativeVerificationError();
      }
    },
  });

  const publications = result.publications.map((publication) => {
    const sessionVersions = publication.group.sessions.map((session) => {
      const record = authorizedByIdentity.get(liveCommuteSessionIdentityKey(session));
      if (record == null) throw new LiveCommuteAuthoritativeVerificationError();
      return Object.freeze(liveCommuteSessionVersionRef(record));
    });
    return Object.freeze({
      ...publication,
      sessionVersions: Object.freeze(sessionVersions),
      authorityCheckCompletedAt: publication.group.validatedAt,
    });
  });

  return Object.freeze({
    ...result,
    publications: Object.freeze(publications),
  });
}
