# iOS Live Activity architecture

Status: Phase 5A publication-policy and scheduler-independent one-shot cycle foundation,
implemented locally 2026-09-12. In addition to the accepted Phase 3A ownership/session
authority, Phase 3B protected-token delivery targets, Phase 4A payload/protocol contract,
and Phase 4B direct dispatcher, it decides whether an authoritative snapshot warrants a
START, material UPDATE, freshness heartbeat, no push, or deferral. It remains callable
internal code: no production caller, public route, timer, recurring worker, scheduler, or
application-startup path invokes it.

The implemented boundary includes immutable session planning and grouping, direct SL
acquisition, authoritative exact-journey role reuse, one final-clock projection, fresh/stale
fallback projection, semantic comparison, persistent ownership and lifecycle records,
revision-controlled mutations, and an authoritative post-acquisition store check. Snapshot
history persistence, public enrollment, recurring scheduling, broadcast-channel
orchestration, and client rendering remain deferred. Accepted publication metadata is now
durable in the dispatch history, but the larger transit snapshot is not. There is no iOS target, Apple
credential, production APNs configuration, production caller, or production migration
wiring. All Apple behavior described below is derived from Apple's public documentation and
has not been verified with an iOS target, physical device, or real APNs environment.

## Intended system shape

Blick models each concrete routine occurrence as an absolute live session with half-open
interval semantics: `[startsAt, endsAt)`. The persistent coordinator initially selects only
sessions whose installation is active, whose lifecycle is `REGISTERED`, and whose window
contains the current instant. The lower-level engine still accepts an arbitrary session set
and partitions it by time; future and expired sessions never enter an acquisition group.
Cancelled records remain durable tombstones rather than being presented as active work.

```text
installation credential + persistent session records
                    |
                    v
 active installation + REGISTERED + [start, end)
                    |
                    v
            lifecycle planner
                    |
                    v
       site/request acquisition groups
                    |
                    v
       one transit acquisition per group
                    |
                    v
 batch ownership/lifecycle/revision revalidation
                    |
                    v
     final clock + full-query projection
                    |
                    v
 snapshots + publication outcomes + session versions
```

Acquisition and publication are deliberately separate scaling boundaries. An
`AcquisitionKey` identifies one logical acquisition whose normalized result is safe to
share within one tick. For LINE it means one SL Transport request. For EXACT it means one
authoritative state-machine run, which may issue several Journey Planner requests while
collecting PRIMARY/NEXT/ALTERNATIVE candidates, bounded by the shared 30-batch safety
budget. A `PublicationKey` identifies sessions whose final filtered dynamic state can be
shared. Session, installation, routine, and user-facing label identity affect neither key.

Canonical acquisition identity follows current behavior:

- `LINE_DIRECTION`: `siteId` only. SL Transport returns a site-wide departure response,
  matching Android's existing fetch-then-filter flow. Live acquisition omits the setup-only
  `forecast` parameter.
- `EXACT_DESTINATION`: Journey Planner `originId`, `destinationId`, a sorted/deduplicated
  transport-mode allow-list, `changesPreference`, and absolute `searchUntil`. The live
  contract is fixed to `searchMode=NOW` and `laterJourneyCount=0`; the latter keeps
  foreground-only supplemental journey discovery out of background live state. Current
  Android behavior supplies the concrete occurrence end as `searchUntil`, so distinct end
  instants must not share a group.

Canonical publication identity is:

- `LINE_DIRECTION`: `siteId`, normalized `transportMode`, nullable `lineId`, and nullable
  `directionCode`. Null remains the existing wildcard for that filter and is distinct from
  every concrete value.
- `EXACT_DESTINATION`: the same complete normalized request as its acquisition key because
  every listed field can change the authoritative role result.

Both key types are independently branded even where their JSON currently contains the same
fields. Keys use readable deterministic JSON built from fixed-order representations, not
opaque hashes or caller property insertion order.

One hundred active line sessions at one site therefore produce one site acquisition even
when they cover several modes, lines, or directions. That normalized result feeds several
publication groups, each applying its own complete wildcard-aware filters. Different sites
always require different acquisitions.

Because acquisition is asynchronous, every dependent publication group is checked again at
one final publication instant after every acquisition group has settled. That lifecycle
check removes sessions that reached `endsAt` while any request in the tick was in flight; if
none remain, no publication outcome is created for that group. The same final instant
re-filters fresh and stale transit rows, so a fast group cannot return data that expired
while a slower sibling was still acquiring.

When called directly, `runLiveCommuteTick` still performs only this time and content
projection. `runStoredLiveCommuteTick` adds the persistent authority check described below;
callers that need ownership guarantees must use that coordinator rather than treating a
previously loaded session array as authorization.

## Installation ownership and internal service

Installation registration creates two independent random values with standard Node crypto:

- an opaque UUIDv4 installation ID; and
- a 32-byte random bearer credential encoded as unpadded base64url.

The service returns the raw bearer credential only in the initial registration result. It
stores a lowercase SHA-256 digest and compares credential digests with a timing-safe
comparison. Safe installation values expose the installation ID, lifecycle state, and
timestamps only. Ordinary session records, snapshots, publication outcomes, and sanitized
service errors contain neither the raw credential nor its digest, and the internal modules
do not log either value.

Possession of both the installation ID and bearer credential authenticates one installation.
An installation ID by itself is not authority. Authentication is repeated inside the same
installation-scoped transaction as every protected read or mutation; unknown installations,
malformed credentials, wrong credentials, and revoked installations receive the same
sanitized authentication failure. Consequently, one installation cannot list, replace, or
cancel another installation's sessions through this service.

The internal service exposes operations to register and authenticate an installation,
register/list/replace/cancel concrete sessions, and revoke an installation. It has only a
storage dependency, so registration and cancellation make no SL call. None of these
operations is connected to an HTTP endpoint or application startup.

This is possession authentication for a locally issued installation credential. It is not a
user account or user-facing login, Apple device attestation, or proof of Premium ownership.
In particular, it does not authorize paid features from a client-supplied Premium flag.

Revocation is durable. A successful revoke sets `revoked_at`, cancels every still-registered
session, and retains each session revision. Lifecycle and active-parent state are therefore
mandatory parts of every authoritative check; revision alone is never authorization.
Repeating revoke with the same valid credential is harmless; all other later authenticated
operations are rejected. The transaction ordering guarantee is described under PostgreSQL
concurrency below.

## Persisted concrete-session lifecycle

The accepted `LiveCommuteSession` and query types remain the execution model. Persistence
wraps each session with `REGISTERED` or `CANCELLED`, a positive server-controlled revision,
and creation/update/cancellation timestamps. A concrete record stores only:

- `installationId`, installation-scoped `sessionId`, and installation-scoped `routineId`;
- absolute `startsAt` and `endsAt`;
- the complete canonical `LINE_DIRECTION` or `EXACT_DESTINATION` query;
- lifecycle, revision, and minimal lifecycle timestamps.

`sessionId` is the stable occurrence identity, with the database key
`(installation_id, session_id)`. Mutable query fields or window times never define that
identity. Routine IDs are also scoped to an installation and are not globally unique.
Installation IDs and revisions do not enter acquisition or publication keys, so persistence
does not fragment otherwise identical transit work. There is no stored weekly schedule,
timezone recurrence, GPS coordinate or tracking field, routine label, event title, name, or
email. The user's complete routine configuration remains local-first.

Persisted queries use the existing canonical publication representation. On every read, the
adapter validates the complete shape and canonical value, including exact-destination
`searchUntil`, fixed `searchMode`, mode ordering, and all other output-affecting fields.
Malformed, extra-field, noncanonical, or otherwise invalid stored JSON fails the read; it is
not normalized into a different query and never reaches a transit source.

Lifecycle mutations have explicit replay semantics:

- New registration starts at revision 1. Repeating the same normalized specification returns
  `UNCHANGED` and creates no duplicate work.
- Reusing the same occurrence identity with incompatible content is a registration conflict;
  it cannot act as an implicit update.
- An exact registration replay after cancellation returns `ALREADY_CANCELLED`. An
  incompatible replay still conflicts. Neither path reactivates the occurrence.
- Replacement requires the expected current revision. A changed specification advances the
  revision by one; a stale expectation fails. An identical request is unchanged, including a
  lost-response retry that supplies the immediately preceding revision for the already
  applied replacement.
- Cancellation requires the current revision, changes lifecycle to `CANCELLED`, and retains
  that revision. Repeating the same cancellation is unchanged. Because cancellation itself
  does not increment revision, authoritative checks must always validate lifecycle as well as
  revision. A cancellation carrying a pre-replacement revision cannot cancel the newer edit.
- Cancellation is terminal in Phase 3A. A future deliberate resume requires a new occurrence
  identity or another explicitly designed transition; replaying registration is never resume
  intent.

Registration and replacement reject any other `REGISTERED` session for the same installation
whose window overlaps under `existing.startsAt < proposed.endsAt` and
`existing.endsAt > proposed.startsAt`. Adjacent windows such as `[06:00, 07:00)` and
`[07:00, 08:00)` are valid. Eligibility is derived from the current time and the absolute
window rather than from a scheduled change to an `active` flag.

## PostgreSQL schema and concurrency

The additive `002_live_commute_sessions.sql` migration is separate from Google Play billing
state and creates two tables:

- `live_commute_installations`: installation primary key, unique SHA-256 credential digest,
  durable revocation time, and creation/update metadata.
- `live_commute_sessions`: installation/session composite primary key, installation foreign
  key, routine ID, absolute window, canonical query JSON, lifecycle, positive revision, and
  lifecycle timestamps.

Database constraints cover UUID and digest formats, referential ownership, nonempty bounded
identifiers, `starts_at < ends_at`, positive revisions, known lifecycle/query-kind values,
and cancellation-state consistency. Partial indexes support registered-session lookups by
installation/window and global eligible-window scans. Full canonical query validation remains
in the TypeScript read boundary because a top-level JSON constraint cannot express the whole
discriminated query contract.

The PostgreSQL adapter receives a caller-owned `postgres` connection/pool. Construction does
no I/O, the adapter never creates or closes an extra pool, and the caller remains responsible
for cleanup. Runtime values use tagged, parameterized SQL. Raw migration text is executed
only by the explicit migration runner.

Every protected operation with structurally valid credentials starts a transaction and locks
its installation parent row with `SELECT ... FOR UPDATE` before credential/state validation
or session work.
Registration, overlap validation, replacement, cancellation, and revocation for one
installation are therefore serialized across processes and separate database connections.
If a session mutation commits first, a following revoke sees and cancels it; if revoke commits
first, the following mutation observes the revoked parent and fails authentication. No
registered session remains authorized after revocation commits. The adapter also rejects
invalid creation/revision transitions, credential mutation, un-revocation, and terminal
session reactivation. Revocation cancels registered rows with a set-based lifecycle update;
it does not deserialize their commute queries, so malformed query JSON cannot prevent the
parent installation from being revoked.

The in-memory adapter uses a process-local queue only to make unit tests deterministic. It is
not the production concurrency guarantee. The PostgreSQL parent-row lock is that guarantee
for mutations made through the adapter.

There is intentionally no PostgreSQL exclusion constraint for overlapping windows in this
minimum migration. A writer issuing direct SQL can bypass the service's overlap and lifecycle
rules. Production database write privileges must restrict these tables to the reviewed
adapter path, or a stronger database constraint must be added before introducing another
writer. The transaction and row lock are held only for short storage operations; no lock is
held during SL acquisition, disruption work, or any future APNs request.

## Snapshot engine

`runLiveCommuteTick` is a scheduler-independent, one-tick orchestration function with an
injected clock. It plans active sessions, runs acquisition groups independently, records an
individual completion instant and source timestamp for each acquisition, then reads one
final clock after all groups settle and projects every still-active publication group at
that instant. Independent acquisition groups run concurrently, and one failure does not
suppress unrelated successful groups.

The tick-start plan is execution input only and is not exposed on the tick result because its
groups may age while I/O is running. Only `publications[].group` is returned as an actionable
publication plan, after the optional authority hook and final-clock session revalidation.

LINE acquisition calls the existing `SlTransportClient` directly and passes its response
through the existing departure normalizer. It never calls Blick's own `/departures` route,
so it does not inherit that HTTP route's `Cache-Control` edge-cache policy
(`s-maxage=30, stale-while-revalidate=30`). The normalized site response is filtered by mode,
optional line, and optional direction; departures whose effective time is before the
projection instant are removed, future cancelled departures remain, and the next five are
retained as a bounded rollover reserve.

EXACT acquisition calls the framework-independent
`acquireAuthoritativeLiveJourneys` service shared with the `/journeys` route. That service
owns candidate collection and PRIMARY/NEXT/ALTERNATIVE selection. The snapshot projection
only preserves assigned roles; it never derives them from array position or promotes NEXT
when PRIMARY expires.

The discriminated `LiveCommuteSnapshot` carries:

- LINE departures with identifiers, line/direction/destination text, scheduled, expected,
  and effective absolute timestamps, cancellation, and relevant operational state.
- EXACT journeys with explicit roles, identifiers, top-level and effective first-departure
  timing, arrival, transfer count, first-leg data, and presentation-relevant leg structure.
- `sourceFetchedAt`, `generatedAt`, and `FRESH` or `STALE` source freshness.

No countdown string or cached minutes-remaining value is stored. A stale fallback is accepted
only from caller-supplied previous state; there is no snapshot-history persistence
implementation here. Its original `sourceFetchedAt` is preserved, LINE departures are
re-filtered against the new clock, and exact roles remain unchanged. If PRIMARY has expired,
its absence is represented honestly rather than relabelling another journey.

Fallback lookup requires the complete matching `PublicationKey` and snapshot kind. A fresh
empty snapshot is an authoritative replacement for older rows, not a reason to resurrect
them; a future snapshot-history layer must store that latest empty state. `FRESH` and `STALE`
describe source acquisition freshness only. They do not turn a scheduled prediction into a
realtime prediction, or vice versa; scheduled/realtime/cancelled operational state remains
separate snapshot content.

The semantic fingerprint includes visible transit fields, roles, operational state, and
fresh/stale presentation state. It excludes both acquisition and generation timestamps, so a
new fetch containing identical content is not reported as a content change. Expiry,
rollover, realtime changes, cancellation, role changes, and relevant journey-structure
changes are semantic changes. This signal is intentionally not a push-frequency policy. A
future heartbeat may republish unchanged content to renew `stale-date`, but without a new
successful acquisition it must preserve `sourceFetchedAt` and must never relabel old source
data as `FRESH`.

Sharing currently stops at one `runLiveCommuteTick` call. Two overlapping ticks containing
the same active key each perform their own acquisition and may finish out of order; there is
no cross-tick, cross-process, or global coalescer, lease, ordering guard, or exactly-once
guarantee. Before production scheduling, a shared protection layer below the existing
Android-facing `/departures` and `/journeys` paths and this engine must address duplicate
acquisition, global concurrency/rate limits, backpressure, monitoring, and stale-write
ordering. Calling Blick's own HTTP route from the engine would not be an appropriate
substitute.

Disruption enrichment is absent from the primary acquisition path. The tick neither fetches
SL Deviations nor waits for a disruption operation, so it introduces no additional
production deviation traffic.

## Store-backed authoritative tick

`runStoredLiveCommuteTick` is the smallest persistent coordinator around
`runLiveCommuteTick`; it does not duplicate acquisition, journey-role selection, snapshot
projection, or fallback logic. One coordinated tick proceeds as follows:

1. Read eligible stored sessions at the injected clock and capture each full specification
   and `(installationId, sessionId, revision)` reference.
2. Pass the captured sessions to the existing planner and shared acquisition engine. No
   database transaction or row lock remains open while SL work runs.
3. After all acquisition groups settle, batch-revalidate the captured references against the
   authoritative store. The coordinator retains only rows whose parent installation is still
   active, lifecycle is still `REGISTERED`, revision is exact, identity is unchanged, and
   complete specification still matches what was acquired.
4. Immediately after that asynchronous check, the engine reads the injected clock again and
   runs its existing active-session and transit-expiry projection at that final instant.
5. Return only surviving publication outcomes, each carrying `sessionVersions` and
   `authorityCheckCompletedAt` for later delivery planning and dispatch. The latter is the
   application clock used for final projection after the check returns, not a database
   snapshot timestamp or authorization lease.

This excludes a cancelled, replaced, revoked, deleted, or newly expired occurrence before a
publication outcome is returned. A changed query invalidates work acquired for the old query;
the coordinator does not retarget the snapshot or automatically reacquire. A later explicit
tick may acquire the new specification. Cancelling one recipient does not discard a shared
acquisition or publication group while another current recipient still needs it.

The final store check is batched rather than performed once per recipient. Session and
installation metadata remain outside acquisition keys, so the existing 100-session
same-site sharing behavior is preserved. Exact-destination roles remain the output of the
existing authoritative journey service: delayed authorization cannot promote `NEXT` to
`PRIMARY` or re-rank an arbitrary journey.

Failure to read initial authoritative state prevents any transit request. Failure of the
post-acquisition authority check fails the coordinated tick closed with a sanitized error;
old in-memory membership is not publication authority. This differs from optional snapshot
history: when fresh transit acquisition succeeds, history lookup failure may still permit the
fresh result under the accepted Phase 2 behavior.

A returned outcome matched authoritative rows at the database statement snapshot used by
the last check. Under PostgreSQL read-committed semantics, that snapshot can precede the
application's `authorityCheckCompletedAt` marker, and a conflicting mutation can commit as
soon as the statement no longer observes it. The marker therefore supports sequencing and
final time projection only; it does not extend authorization. Phase 4B's direct dispatcher
uses a new locked reservation check and an exact target-generation check when claiming an
attempt immediately before the external send. No database lock spans APNs network I/O. A
session can consequently still be cancelled, replaced, revoked, or deleted after that final
check but before APNs receives the request. The post-send authority marker records this race;
it cannot make PostgreSQL and APNs atomic. The coordinator itself still adds no outbox,
queue, lease, distributed scheduler, or exactly-once claim.

## Apple delivery direction

iOS Live Activities will use ActivityKit. ActivityKit exposes a device-specific
push-to-start token that a server can eventually use for a device-targeted remote start.
Broadcast push notifications cannot start a Live Activity; they update activities already
subscribed to a channel and do not replace the device-targeted start request.

Apple exposes the current value through
[`Activity<Attributes>.pushToStartToken`](https://developer.apple.com/documentation/activitykit/activity/pushtostarttoken)
and replacements through
[`pushToStartTokenUpdates`](https://developer.apple.com/documentation/activitykit/activity/pushtostarttokenupdates).
The per-activity [`pushToken`](https://developer.apple.com/documentation/activitykit/activity/pushtoken)
and [`pushTokenUpdates`](https://developer.apple.com/documentation/activitykit/activity/pushtokenupdates-swift.property)
lifecycle is separate. Apple directs clients to upload replacements
and invalidate outdated values for both token kinds. Apple does not document a fixed byte
length, rotation cadence, or one-use lifetime for these ActivityKit tokens; Blick therefore
treats them as bounded opaque bytes with explicit client-observation generations, not as
fixed-format or permanent credentials.

On iOS 18 and later, that individual start payload can include `input-push-channel`. The
new Live Activity then listens on the named channel, and later dynamic updates can be sent
once to every activity subscribed to that channel. The intended, not yet implemented,
mapping is one broadcast channel per publication group rather than per acquisition group:
one site-level acquisition may feed several channels whose line/direction filters differ.
Start each user's routine occurrence individually, then broadcast an identical update only
within its publication group. See Apple's
[ActivityKit push-notification guide](https://developer.apple.com/documentation/ActivityKit/starting-and-updating-live-activities-with-activitykit-push-notifications),
[broadcast setup](https://developer.apple.com/documentation/UserNotifications/setting-up-broadcast-push-notifications),
and [WWDC24 broadcast overview](https://developer.apple.com/videos/play/wwdc2024/10069/).

ActivityKit supports frequent updates, but delivery has a system-controlled budget and may
be throttled. A future Blick engine may aim to acquire fresh state approximately every 30
seconds while a session is active, but it cannot promise that every corresponding push will
arrive on an exact 30-second boundary. Acquisition cadence and Apple delivery cadence are
different contracts. Users can also disable frequent Live Activity pushes.

## Phase 3B delivery foundation

### Implemented

- Apple-specific code lives under `backend/src/liveCommute/apple`; ActivityKit delivery
  concepts do not enter `LiveCommuteQuery`, acquisition/publication keys, transit snapshots,
  journey roles, or SL clients.
- One installation-scoped push-to-start token history records explicit monotonically
  increasing client generations and immutable server revisions. An identical retry of the
  current generation is idempotent; a higher generation supersedes the former current row;
  an older or same-generation different value is rejected. Exactly one row may be `CURRENT`.
- Each direct-delivery binding has an independent update-token history with the same
  rotation, replay, and invalidation rules. A push-to-start token is never interchangeable
  with a per-activity update token.
- Token plaintext is accepted only as copied, nonempty raw bytes. The 4,096-byte maximum is
  Blick's own API/storage resource bound, not an assertion about an Apple token length.
  Persistence uses an injected caller-supplied 256-bit key with AES-256-GCM, a fresh 12-byte
  nonce, a 16-byte authentication tag, context-bound additional authenticated data (including
  token kind, owner, generation, and APNs environment), and a separate deterministic SHA-256
  digest used only for equality. A bounded recent-nonce detector catches a repeating random
  source without creating unbounded process state; cryptographic uniqueness relies on Node's
  CSPRNG. The key is neither hard-coded nor derived from the installation credential, and
  production key configuration is deferred.
- Delivery bindings use server-generated UUIDs and bind one installation/session to one
  exact mutable-session revision. Their honest backend lifecycle is `PENDING_START`,
  `ENDED`, or `INVALIDATED`; no row claims that APNs successfully started an on-device
  activity. An invalidated exact-revision binding is a retained tombstone and cannot be
  recreated by delayed replay. A normalized Apple activity identifier can be attached once
  after creation, is unique per installation, and cannot be changed or attached to a terminal
  binding. Its presence—or any direct update-token history—prevents that binding from resolving
  another start target.
- `DIRECT_TOKEN` and `BROADCAST_CHANNEL` are explicit strategies. Direct bindings alone may
  accept update tokens. No channel identifier is invented or persisted.
- Every authenticated mutation takes the same Phase 3A installation-parent lock before
  credential, session, binding, or token checks. PostgreSQL provides cross-process
  serialization; the in-memory adapter composes through the Phase 3A transaction only for
  deterministic tests. No process-local mutex is production authority.
- Internal start/update target resolution rechecks that the installation is active, the
  session is `REGISTERED`, its revision exactly matches the binding, the half-open session
  window contains the resolution instant, the binding remains eligible, and the needed
  token is current. Only then is plaintext decrypted. Safe service DTOs, ordinary
  installation/session values, errors, snapshots, and diagnostic output contain no token,
  ciphertext, authentication tag, nonce, or digest.
- Revocation, cancellation, replacement, expiry, explicit token invalidation, and terminal
  binding state all fail delivery lookup closed. Physical token history rows need not be
  rewritten row by row for session authority changes. Push-to-start and direct update-token
  invalidation both use an expected client generation so a delayed invalidation cannot disable
  a newer token. Resolver time comes only from its injected trusted clock and is read after
  locked persistence reads, immediately before the half-open window check. No token mutation
  performs an SL request.

The intended iOS 18+ broadcast boundary remains:

```text
installation push-to-start token
        -> future device-addressed APNs start request
        -> input-push-channel names a real APNs-created channel
        -> the individual Live Activity subscribes
        -> later identical publication-group updates may be broadcast once
```

An acquisition group may feed several publication groups. One publication group may later
map to one active Apple broadcast channel, and many installation-specific activities may
subscribe to that channel. `AcquisitionKey`, `PublicationKey`, and an Apple channel ID are
three different identities. Channel creation, persistence, replacement, and deletion remain
a later APNs channel-management phase.

### Still deferred at the Phase 3B boundary

- iOS/ActivityKit code that obtains, observes, and uploads real tokens
- physical-device and real Apple-environment verification
- Apple Developer account, APNs credentials, production provider-token configuration, and
  any real Apple request
- broadcast-channel creation and real channel identifiers
- public enrollment/token/binding routes, their rate limits, and abuse controls
- production key management, rotation, retention, deletion, and operational access policy
- stale-date, freshness-heartbeat, publication policy, scheduler, and queue/outbox worker;
  Phase 4B adds an explicit direct-dispatch call but no recurring execution
- cross-instance transit acquisition coordination and App Store configuration

APNs delivery remains best effort and distinct from backend session authority. A successful
authority lookup is a point-in-time check, not a lease across a later network request. None
of the Apple behavior or token shapes above has been exercised on a real device.

## Phase 4A APNs payload and protocol foundation

The Phase 4A contract was rechecked against Apple's current
[ActivityKit push payload guide](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications),
[direct request contract](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns),
[broadcast request contract](https://developer.apple.com/documentation/usernotifications/sending-broadcast-push-notification-requests-to-apns),
[channel-management contract](https://developer.apple.com/documentation/usernotifications/sending-channel-management-requests-to-apns),
[provider-token requirements](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns),
and [response contract](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns).

### Implemented

- `BlickLiveActivityAttributes` is fixed at wire schema version `1` with exactly
  `schemaVersion`, `bindingId`, `sessionRevision`, and `commuteKind`. Mutable transit data,
  installation identity and credentials, ActivityKit tokens, encryption metadata, purchase
  state, and complete routines are excluded. The attributes type string is exactly
  `BlickLiveActivityAttributes`.
- Content state is also versioned and discriminated by `commuteKind`. Both variants carry
  `freshness` and integer `sourceFetchedAt` UNIX seconds. The LINE variant carries at most two
  presentation rows: the leading future departure (including its cancellation state) and the
  first later non-cancelled boarding opportunity when available, otherwise the second future
  row. Each carries identity, line/direction/destination, scheduled/expected/effective epoch
  seconds, cancellation, and current operational state. This two-row policy is an explicit
  Live Activity presentation bound; rows are never dropped dynamically to make an oversized
  payload pass.
- The EXACT variant carries compact journey summaries with the backend-assigned `PRIMARY`,
  `NEXT`, or `ALTERNATIVE` role, overall and effective departure epoch seconds, arrival,
  origin/destination, transfer count, and first useful public-transport-leg presentation.
  It does not copy full leg arrays or invent cancellation state that the current authoritative
  snapshot does not provide. Roles are neither inferred from position nor promoted after an
  earlier journey expires.
- Snapshot mapping is pure and takes an explicit projection instant. It filters expired LINE
  rows by `effectiveTime` and EXACT rows by `effectiveDepartureTime` before reducing time to
  whole seconds, preserves future cancellations and FRESH/STALE state, copies and freezes its
  result, and never generates countdown strings. Swift must calculate countdowns from the
  absolute values and must independently reject data that is expired when displayed.
- Pure ActivityKit builders produce start, update, and end JSON. Every builder requires an
  explicit event-generation instant and emits whole epoch seconds without reading an ambient
  clock. Start requires caller-supplied alert title/body and supports three deliberate modes:
  legacy direct start with neither input field, iOS 18+ direct start with
  `input-push-token: 1`, or iOS 18+ channel subscription with one caller-supplied
  `input-push-channel`. The two input mechanisms cannot be combined. Update supports an
  optional caller-chosen `stale-date`; end requires final state and supports an optional
  caller-chosen `dismissal-date`, including a past instant for immediate dismissal.
- The exact final serialized payload is counted in UTF-8 bytes and rejected above 4,096 bytes
  both while building and at the APNs request boundary. Update/end builders also require the
  normalized static attributes as non-wire sizing context, so the combined static attributes
  plus dynamic state cannot exceed 4,096 bytes merely because those events do not resend the
  attributes. The version-1 ceiling reserves the largest valid session revision so one shared
  broadcast update cannot fit a low-revision subscriber while exceeding the ActivityKit limit
  for another subscriber. Strings and rows are not silently truncated or removed. The same
  conservative limit is used for direct and broadcast ActivityKit bodies even though Apple's
  broadcast transport currently documents a larger transport envelope.
- Pure APNs descriptions cover direct device requests, broadcast update/end publication, and
  CREATE/READ/DELETE/LIST channel-management operations. Direct requests target the selected
  sandbox or production host, use `/3/device/<activity-token>`, set the
  `<bundle-id>.push-type.liveactivity` topic, and accept only priority 5 or 10. Broadcast
  requests use `/4/broadcasts/apps/<bundle-id>`, require a caller-supplied APNs channel ID and
  expiration, accept Apple-documented priority 1, 5, or 10, and accept update/end payloads
  only. Channel creation requires the caller to choose documented message-storage policy `0`
  or `1`; only APNs may return a channel ID.
- Sensitive paths, authorization headers, channel headers, and bodies live behind a private
  request representation. Ordinary JSON, string conversion, inspection, fake-transport
  recording, and errors expose only bounded redacted diagnostics. Raw transport material is
  available only through the explicit transport boundary.
- The provider-token primitive validates a caller-supplied private P-256 key and explicit Team
  ID, Key ID, and issued-at instant. It emits an ES256 JWT with `iss`/`iat` and a 64-byte JOSE
  R||S signature using Node's IEEE-P1363 encoding. The token wrapper is redacted from ordinary
  serialization and inspection. No key is read from an environment variable or file.
- Pure response handling normalizes bounded APNs metadata and independently classifies device,
  broadcast, and channel-management responses. Direct terminal token outcomes retain APNs'
  millisecond invalidation timestamp for a later generation-specific mutation decision.
  Authentication, throttling, invalid request, retryable server, channel-invalid, and unknown
  protocol outcomes remain distinct. A direct or broadcast HTTP 200 is `ACCEPTED`, never
  proof of display or device delivery.
- `DeterministicFakeApnsTransport` returns scripted normalized responses and records redacted
  diagnostics only. It never materializes a request and contains no HTTP, TLS, retry, timer,
  or connection code.
- The delivery-plan seam matches each Phase 3B target to the exact authoritative publication
  key and session revision, performs one last expiry projection, and builds start, direct or
  broadcast update, and direct or broadcast end request descriptions. Device plans retain the
  exact token client generation/server revision as safe correlation metadata. Planning does
  not send, create a channel, rotate/invalidate a token, or move a binding to ACTIVE/ENDED.

### Protocol and ordering boundaries

ActivityKit `timestamp`, optional `stale-date`/`dismissal-date`, and APNs transport expiration
are separate clocks. The builders validate their shape and the sensible stale-date ordering,
but choose no global freshness, dismissal, heartbeat, or expiration policy. Apple may reorder,
delay, group, throttle, or omit best-effort updates. A caller must use persistently coordinated
per-activity ordering; a process-local counter or wall-clock assumption is not a cross-process
ordering guarantee.

The Phase 3B resolver check and Phase 4A publication match are point-in-time checks, not a
lease. Phase 4B adds durable direct ordering, a second target-generation check at claim,
retry classification, and generation-specific token invalidation. It cannot eliminate the
remaining post-claim check-to-send race, and broadcast orchestration remains future work.
Constructing an `end` payload is separate from recording backend terminal intent, and neither
action alone proves the device ended the Live Activity.

Apple's current broadcast documentation is internally inconsistent. The connection table in
[Sending broadcast push notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-broadcast-push-notification-requests-to-apns)
and Apple's [command-line broadcast examples](https://developer.apple.com/documentation/usernotifications/sending-push-notifications-using-command-line-tools)
use the ordinary `api[.sandbox].push.apple.com` hosts. Development examples on the broadcast
request page itself and Apple's [push troubleshooting guide](https://developer.apple.com/documentation/usernotifications/troubleshooting-push-notifications)
instead use dedicated `api-broadcast[.sandbox].push.apple.com` hosts. Blick keeps this mapping
centralized and currently models the dedicated hosts; the documentation alone cannot resolve
the conflict. Similarly, the ActivityKit guide lists broadcast priority 5 or 10 while the
dedicated broadcast transport page lists 1, 5, or 10; the protocol model follows the latter.
Both choices require confirmation in a real Apple environment before launch and are not
evidence of a successful connection.

Before any real push is attempted, the future Swift `ActivityAttributes` and nested
`ContentState` synthesized `Codable` types must match the version-1 JSON names, nullability,
integer timestamp units, and discriminated LINE/EXACT shapes exactly. Swift/client capability
code must also choose legacy direct, iOS 18+ direct update-token, or iOS 18+ channel start; it
must not infer capability from token shape. Caller-supplied start alert localization remains a
future English/Swedish client/product integration decision.

### Boundary after Phase 4B

Phase 4A's request builders and classifiers remain pure. Phase 4B adds the direct transport,
provider-token reuse, durable ordering, result mutation, and exact-generation invalidation
described next. The following remain outside both phases:

- a real Apple `.p8`, Team ID/Key ID production configuration, finalized bundle ID, and a
  reviewed signing-key lifecycle
- broadcast channel creation execution, persistence, replacement, deletion, subscriber
  coordination, and final message-storage policy
- publication policy, scheduler, queue/outbox worker, freshness heartbeat, and stale-date
  policy
- iOS/Swift implementation, push-to-start/update-token upload integration, localization,
  entitlements, App Store configuration, and physical-device validation
- production telemetry, connection scaling, idle PING policy, retry scheduling, and
  credential/key rotation procedure

## Phase 4B direct APNs dispatch foundation

Phase 4B was rechecked against Apple's current
[connection guidance](https://developer.apple.com/documentation/usernotifications/establishing-a-connection-to-apns),
[direct request contract](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns),
[response guidance](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns),
[provider-token requirements](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns),
and [ActivityKit push guide](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications).
The implemented dispatcher is deliberately limited to device-addressed `START`,
`DIRECT_UPDATE`, and `DIRECT_END`. The Phase 4A broadcast and channel descriptions remain
available to pure callers, and the transport can carry them, but no production dispatcher
or channel registry makes broadcast publication operational.

### HTTP/2 transport and provider authentication

- `NodeHttp2ApnsTransport` uses Node's standard HTTP/2 client, keeps ordinary certificate
  verification enabled, and requests TLS 1.2 or newer. Construction performs no network
  I/O. The first explicit send lazily creates one reusable session per APNs authority; later
  calls share it, including concurrent streams, without a fixed stream-count assumption or
  HTTP/2 PRIORITY frames.
- Session error, unexpected close, or GOAWAY retires that session for future work. A later
  explicit send may create a replacement, but the failed call is never reconnected and
  resent automatically. A stream that has already obtained a complete response can still
  return that response while the session is being retired. Controlled shutdown stops new
  sends and lets existing streams finish through Node's graceful session close.
- Every explicit send makes at most one attempt with a 15-second request deadline and a 64
  KiB response-body ceiling. A local refusal that provably occurs before stream creation,
  such as request-materialization failure or an already-closed transport, is explicit
  `NOT_ATTEMPTED`; the dispatcher records the claimed attempt as `ABORTED`. A missing
  definitive response after network initiation is an enumerated, redacted
  `OUTCOME_UNKNOWN`, not an invented APNs rejection. There is no retry loop, backoff sleep,
  reconnect timer, fixed concurrency limit, idle PING timer, or hourly reconnect. Apple
  documents an optional PING for a mostly idle connection after roughly an hour; that is a
  future connection-pool concern.
- The device path and bearer JWT, plus a channel identifier when present, are marked as
  never-index HTTP/2 header values. Those values and the body remain inside the private
  materialized request. Transport results expose bounded response metadata or a sanitized
  unknown-outcome reason; they do not expose target tokens, provider JWTs, request bodies,
  signing material, or complete sensitive paths.
- Transport tests exercise real stream/write/read, reuse, concurrency, timeout, reset,
  close, error, GOAWAY, and replacement behavior against injected local HTTP/2 servers. They
  separately assert the production connector's TLS minimum without weakening certificate
  checks. They perform neither a real TLS handshake nor any Apple request.
- `LazyApnsProviderTokenCache` is process-local, wraps the Phase 4A ES256 signer, and accepts
  an explicit trusted clock. It signs only when `getToken()` is explicitly called, has no
  refresh timer, and reuses the same lease for Blick's named 50-minute refresh interval. A
  caller override must be a whole number of seconds from 20 minutes inclusive to one hour
  exclusive. Refresh is lazy at the next request boundary, before Apple's one-hour rejection
  boundary.
- Cache invalidation is conditional on the exact provider-token lease used by a response.
  The last issuance time survives invalidation, so an authentication response cannot force
  another signature inside Apple's 20-minute lower boundary. Replacing an HTTP/2 session
  does not rotate the cached JWT. `ExpiredProviderToken` can invalidate the matching lease
  for the next explicit dispatch; it never triggers a same-call resend. If that next call is
  still inside the 20-minute floor, the no-send attempt records the cache's defensible
  not-before instant. Invalid provider configuration and `TooManyProviderTokenUpdates` fail
  closed without an immediate regeneration loop. This in-process floor does not coordinate
  signing across recycled or concurrent serverless processes; production rollout must assess
  process lifetime and concurrency or add shared issuance coordination if required.

### Durable per-binding ordering

The additive `004_live_activity_dispatch.sql` migration separates network-delivery state
from commute sessions and from the Phase 3B token registry. One cursor row per direct binding
stores the exact session revision, the last reserved ActivityKit event timestamp, and
optional END terminal-intent timestamp. Attempt rows store safe correlation and lifecycle
data: dispatch and APNs request UUIDs, operation, exact token generation and environment,
payload fingerprint, APNs status/reason, retry advice, post-send authority, and
generation-invalidation outcome. They do not store plaintext or copied encrypted ActivityKit
tokens, provider JWTs, signing keys, request headers, or complete payload JSON.

Each reservation runs in a short PostgreSQL transaction under the installation-parent lock,
so separate backend processes share the same authority without serializing unrelated
installations. The transaction rechecks active installation, registered exact-revision
session, half-open active window, direct binding eligibility, and a current operation-
appropriate token. Its caller supplies the intended generation instant using the Phase 4A
whole-epoch-second rule. The timestamp must be strictly greater than the binding cursor:
an older value returns `STALE_EVENT`, an equal value returns `SAME_SECOND`, and Blick never
fabricates a future timestamp to break a tie. Apple's documentation does not define
same-second tie behavior; the strict comparison is Blick's conservative ordering policy.
Once advanced, the cursor never moves backward after rejection, timeout, or unknown outcome.

A partial unique index permits only one `RESERVED` or `IN_FLIGHT` attempt per binding. A
newer UPDATE or END may supersede an older unsent `RESERVED` UPDATE. It cannot supersede an
`IN_FLIGHT` request, which returns `BUSY`; separate bindings can proceed independently. An
older UPDATE with an already-recorded ambiguous outcome does not freeze later authoritative
content: a strictly newer timestamp may still be reserved and ActivityKit ordering protects
the device if both arrive. An abandoned `IN_FLIGHT` attempt is not presumed unsent and
remains blocking for later reconciliation; Phase 4B does not invent crash recovery or a
blind resend.

Before APNs I/O, the dispatcher re-resolves the sensitive Phase 3B target, builds the bounded
request in memory, and claims its reservation with a payload fingerprint. The short claim
transaction again checks current authority, the latest cursor, and the exact recorded token
generation/environment. Failure or supersession changes the attempt to `ABORTED` or
`SUPERSEDED` and performs zero APNs traffic. No database transaction or row lock is held
while the transport waits for APNs.

START reservation is blocked by an active, accepted, or unknown prior START, by an attached
Apple Activity identifier, or by update-token history proving an activity exists. A
definitive rejection may permit a later explicit attempt, while a retryable START respects
its durable not-before boundary. An unresolved or process-abandoned START is never retried
blindly. END uses the same timestamp cursor and records durable terminal intent at
reservation; subsequent ordinary UPDATE is rejected even if APNs later rejects, times out,
or accepts the END. APNs acceptance remains separate from backend terminal intent and does
not prove on-device dismissal.

### Honest APNs outcomes and remaining races

One completed network attempt is durably one of `ACCEPTED`, `REJECTED`, `RETRYABLE`, or
`OUTCOME_UNKNOWN`; a claimed attempt that the transport provably did not initiate is
`ABORTED` and does not create false unknown-START evidence. **APNs HTTP 200 means accepted by
APNs; it does not mean delivered to the device, displayed to the user, or still visible as a
Live Activity.** Apple 5xx responses
receive `RETRY_AFTER_APPLE_BACKOFF` with a 15-minute not-before instant. Throttling is marked
retryable without inventing an Apple-specified delay. Provider-authentication failures are
kept separate from destination-token validity: they never invalidate an ActivityKit token,
and no classification schedules or performs another request.

If APNs may have observed a request but the completion transaction cannot record its result,
the dispatcher returns `RESULT_NOT_RECORDED` with safe dispatch/APNs request correlation and
leaves the attempt unresolved; it neither rewrites the attempt as unsent nor retries it.

Only a classifier-proven terminal destination response for `BadDeviceToken`,
`DeviceTokenNotForTopic`, `ExpiredToken`, or `Unregistered` requests ActivityKit-token
invalidation. Completion applies that mutation only when the exact client generation,
server revision, and APNs environment used by the attempt are still current; a delayed
response for generation N cannot invalidate N+1. Server errors, authentication failures,
timeout, GOAWAY, stream/session failure, and other unknown outcomes never invalidate the
destination token.

After APNs accepts a request, the result transaction rechecks the installation, exact session
revision/window, binding, and token generation, then records post-send authority as
`MATCHED` or `CHANGED`. `CHANGED` is evidence that the request happened across a concurrent
authority change; it does not rewrite the attempt as unsent and triggers no compensating
network call here.

The final claim still cannot create an atomic transaction across PostgreSQL and APNs. A
cancellation, replacement, revocation, expiry, or token rotation may commit after claim but
before the request reaches APNs. That check-to-send race is explicit and remains input to a
future cleanup policy. START has an additional important boundary: APNs may accept START,
then authority may change before the iPhone uploads its Apple Activity identifier or update
token. Blick can temporarily lack a direct token with which to end that new activity.
Phase 4B neither weakens Phase 3B authorization nor invents an unverified cleanup upload
path; local client cleanup and a narrowly reviewed compensation path require real Swift and
device evidence before production.

Ordinary WidgetKit widgets are not the 30-second live engine. Widget extensions are not
continuously active, reloads are budgeted and scheduled by the system, and Apple recommends
timeline entries at least about five minutes apart. See [Keeping a widget up to date](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date).

## Phase 5A publication policy and one-shot cycle

Phase 5A adds a pure policy between the authoritative stored tick and the Phase 4B direct
dispatcher. Approximately 30-second-capable transit acquisition does **not** mean an APNs
submission every 30 seconds. Absolute departure and journey timestamps let the device
advance a countdown without a new server message; the policy submits only a material
visible change, a narrowly allowed freshness heartbeat, or an eligible initial START.

### Visible state and durable accepted history

The policy fingerprints the exact version-1 wire `ContentState` that the Live Activity can
render, after final expiry filtering and the two-row LINE presentation selection. The
lowercase SHA-256 input includes the schema and commute kind, `FRESH`/`STALE`, every visible
LINE or EXACT field, authoritative journey roles, and visible cancellation/operational
state. It deliberately excludes `sourceFetchedAt`, the ActivityKit event timestamp,
`stale-date`, binding/session identity, request identity, token generation, and all hidden
third-through-fifth LINE reserve rows. A refreshed source timestamp or changing wall-clock
countdown therefore cannot masquerade as changed visible content.

Migration 005 extends the immutable Phase 4B attempt history with nullable
`visible_content_fingerprint`, `publication_source_fetched_at`, and
`publication_stale_at`. The fields are attached at reservation and become accepted history
in the same transaction that changes that attempt to `ACCEPTED`; there is no separate
best-effort checkpoint. Policy reads select the newest accepted attempt by ActivityKit
event timestamp for the exact binding, installation, and session revision. An older result
cannot overwrite a newer row. Pre-005 accepted rows remain readable but have explicitly
incomplete publication metadata, which causes policy deferral rather than a guessed push.
`payload_fingerprint` remains the hash of the complete APNs JSON body and is not reused as
visible identity.

APNs `ACCEPTED` means that APNs accepted the request, not that the phone displayed it. It is
nevertheless the strongest server-side evidence available for cross-process publication
decisions; there is no device-delivered acknowledgement to wait for.

### Pure decision rules

- A new START requires an authoritative active session, a `READY` (never `READY_STALE`)
  publication with nonempty visible state, an exact current binding, a known direct client
  capability, and an injected localized alert. `DIRECT_LEGACY` and `DIRECT_IOS18` select
  their respective Phase 4A start modes. `UNKNOWN` defers instead of inferring an iOS
  version from a token; `BROADCAST_CAPABLE` and broadcast bindings remain deferred. Under
  Apple's current ActivityKit contract, START does not carry `stale-date`, so an accepted
  START records `publication_stale_at = NULL`.
- For an existing direct activity, a changed visible fingerprint requests
  `UPDATE_CONTENT`. This includes expected-time/rollover/cancellation changes,
  `FRESH`/`STALE` transitions, and authoritative journey role or timing changes. It excludes
  source bookkeeping, hidden reserves, and countdown-only changes.
- An UPDATE `stale-date` derives only from
  `sourceFetchedAt + configured fresh-source lifetime`, never from worker or dispatch time.
  A supposedly fresh result whose derived stale date is not safely in the future is rejected
  as stale source. A stale fallback keeps its original source time and may publish an honest
  visible transition to `STALE`, but it omits `stale-date` and never renews freshness.
- `UPDATE_FRESHNESS` is possible only when visible state is unchanged, the current snapshot
  is fresh, its source acquisition is genuinely newer, the derived stale date extends the
  accepted value, the accepted value is within the configured lead window, the minimum
  interval has elapsed, and the injected frequent-push state is `ENABLED`. `DISABLED` and
  `UNKNOWN` still permit material updates but do not assume a frequent-update budget.
- An unknown UPDATE outcome is not treated as accepted. An explicit injected reconciliation
  rule may request a later update with a strictly newer ActivityKit timestamp; this is
  distinct from a freshness heartbeat. An unknown START remains a blocker and is never
  blindly retried.

Because accepted START history has no stale date, the current heartbeat rule cannot bootstrap
an otherwise unchanged activity: the first accepted material UPDATE establishes its first
server-selected stale deadline. Initial stale-state establishment and physical-device
validation remain deliberate follow-up work rather than an undocumented START field or a
relaxed heartbeat rule.

Fresh-source lifetime, heartbeat lead, minimum heartbeat interval, minimum safe stale-date
lead, unknown-update reconciliation, and APNs priority are explicit policy inputs. The
foundation does not choose production timing values. The priority boundary permits a
caller-reviewed policy; ordinary content and freshness updates can conservatively use
priority 5 without turning every acquisition into priority 10.
The future client should report
[`frequentPushesEnabled`](https://developer.apple.com/documentation/activitykit/activityauthorizationinfo/frequentpushesenabled)
to the server so the injected capability state reflects the user's system setting.

### One-shot publication cycle

`runLiveActivityPublicationCycle` executes exactly once when explicitly called. It invokes
`runStoredLiveCommuteTick`, retains only authoritative `READY`/`READY_STALE` groups, maps
each group to the Phase 4A wire state once, fingerprints it once, batch-loads bindings for
the exact session-version references, and batch-loads their dispatch histories. Recipient
policy and dispatch then run with an explicit bounded fan-out limit. Once the batch reads
succeed, one recipient's missing token, changed authority, busy reservation, APNs result, or
binding-local empty/incomplete history is represented in that binding's sanitized result and
does not suppress siblings. A systemic batch-read failure fails the cycle closed before any
recipient dispatch.

For each requested send the existing Phase 4B dispatcher remains responsible for sensitive
target resolution, provider-token use, payload/request construction, strict event ordering,
same-second rejection, one active attempt, START blocking, terminal intent, APNs transport,
and durable result classification. A prepared-content seam reuses the exact group mapping
while still checking publication key, session version, commute kind, and source timestamp;
no token, JWT, private key, APNs body, or raw transit response enters cycle summaries.

The cycle performs no recurrence and is not connected to Vercel, application startup, an
HTTP route, a queue, or a timer. Two overlapping invocations can still duplicate upstream
transit acquisition before Phase 4B ordering is reached. A global cross-process schedule
lease is required before recurring activation; a process-local mutex would not provide that
guarantee. Automatic END is also not added: cancelled, expired, and replaced sessions are
absent from the active tick, while the ordinary Phase 3B resolver intentionally requires
current session authority. A later security design needs a narrow END-only cleanup resolver,
including the START-before-update-token gap, informed by real client/device evidence.

## Freshness and authoritative state

Phase 4A live payloads carry absolute departure/journey timestamps, and Phase 4B does not
change their semantic comparison. Countdown rendering should derive from those timestamps
and the current device/system time; a push should not be sent merely to turn “4 min” into
“3 min”. Before acquisition results are published, expired
departures and journeys must be filtered again against the publication instant. The future
client must also reject expired absolute timestamps locally: backend pre-filtering cannot
prove correct presentation when a push is delayed, throttled, reordered, or never delivered.
Exact-destination `PRIMARY`, `NEXT`, and optional `ALTERNATIVE` roles remain
backend-authoritative. Apple describes broadcast delivery as best effort in
[Sending broadcast push notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-broadcast-push-notification-requests-to-apns).

Fresh transit changes, cancellations, a changed authoritative role set, and meaningful
disruption changes require new live state. Disruption acquisition/enrichment remains
secondary and must never delay primary departure/journey publication. Phase 5A supplies the
source-based UPDATE `stale-date` calculation and requires its lifetime/lead values to be
injected; it deliberately does not select production durations. Passing `stale-date`
changes the system-visible activity state to stale after that date, but it does not design an
honest stale presentation automatically. The future Activity view must observe and render
stale/expired state explicitly. Apple documents the corresponding behavior in
[`ActivityContent.staleDate`](https://developer.apple.com/documentation/activitykit/activitycontent/staledate)
and [`ActivityState.stale`](https://developer.apple.com/documentation/activitykit/activitystate/stale).

Exact-destination disruption enrichment has one unresolved grouping boundary: current role
selection depends on Journey Planner origin/destination, modes, preference, and
`searchUntil`, while disruption relevance can additionally use the SL Transport `originSiteId`.
A future enriched-state engine must either add that value to the full payload key or perform
nonblocking disruption work in a narrower subgroup. It must not weaken the role grouping or
move role selection to a client.

## Security, privacy, and retention

The Phase 3B tables store encrypted push-to-start and per-activity update-token generations,
plus comparison-only digests and lifecycle metadata. The Phase 4B/5A dispatch history adds
only safe correlation, ordering, result fields, a visible-state digest, and source/stale
timestamps. Neither layer stores token
plaintext, provider JWTs, Apple signing keys, raw installation bearer credentials, complete
payload bodies, purchase tokens, copied billing credentials, or user-account data.
ActivityKit tokens are installation-linked delivery identifiers and must be treated as
sensitive operational data even when encrypted. They are excluded from safe DTOs, errors,
logs, snapshots, and committed fixtures; tests use only synthetic byte sequences. The Apple
delivery and dispatch migrations remain separate from Google Play purchase state.

Installation-linked stop choices, destinations, route filters, and commute windows can still
reveal habits and are potentially sensitive personal data. Replacing a name with an opaque
installation ID does not make those records anonymous. Access controls, logging/redaction,
backup handling, operational access, and retention therefore need the same privacy review as
other location-adjacent data.

The current cancellation tombstone retains the occurrence identity, original specification,
revision, and lifecycle timestamps. Keeping terminal identity and enough comparison state is
what prevents a delayed registration replay from recreating or silently changing a cancelled
occurrence. Installation revocation is likewise retained so it survives restarts. Before
production, the product must decide how long this terminal metadata is needed, whether the
full query can be minimized or replaced by narrower replay evidence, how installation
deletion interacts with replay prevention, and how backups expire. Phase 3A does not promise
immediate erasure while retaining complete cancelled records.

The internal credential is intentionally a narrow foundation, not a publicly launchable
authentication system. Public activation requires at least enrollment abuse protection,
rate limits, request and per-installation resource limits, credential rotation/recovery and
loss policy, server-authoritative Premium authorization, and an explicit decision about
device attestation. It also requires reviewed database privileges, retention policy,
operational monitoring, migration/rollback procedures, and a reviewed compensation policy
for the remaining check-to-send and START-cleanup races.

## Local migration and PostgreSQL verification

Migration execution is manual. Each independent runner is scoped to one checked-in migration
file. From `backend`, set a dedicated non-production target explicitly, run the command, and
then clear the process variable:

```powershell
$env:LIVE_COMMUTE_MIGRATION_DATABASE_URL = 'postgresql://localhost/blick_live_commute_dev'
npm run migrate:live-commute
Remove-Item Env:LIVE_COMMUTE_MIGRATION_DATABASE_URL
```

The runner requires `LIVE_COMMUTE_MIGRATION_DATABASE_URL`, applies only
`002_live_commute_sessions.sql` in a transaction, does not inspect `DATABASE_URL`, does not
invoke the billing migration, and explicitly closes the pool it creates. Importing the
migration runner, adapter, service, coordinator, or engine does not connect to a database,
start work, or require Redis or Apple configuration. In particular, imports initiate no SL
acquisition or periodic cleanup. The existing production Redis validation elsewhere in the
application is unchanged.

After migration 002, apply the independent delivery migration explicitly to the same
reviewed non-production target:

```powershell
$env:LIVE_COMMUTE_MIGRATION_DATABASE_URL = 'postgresql://localhost/blick_live_commute_dev'
npm run migrate:live-activity-delivery
Remove-Item Env:LIVE_COMMUTE_MIGRATION_DATABASE_URL
```

That runner applies only `003_live_activity_delivery.sql`; it does not inspect `DATABASE_URL`,
run migration 002 or the billing migration, load an encryption key, or connect on import.

After migration 003, apply the independent direct-dispatch migration explicitly to the same
reviewed non-production target:

```powershell
$env:LIVE_COMMUTE_MIGRATION_DATABASE_URL = 'postgresql://localhost/blick_live_commute_dev'
npm run migrate:live-activity-dispatch
Remove-Item Env:LIVE_COMMUTE_MIGRATION_DATABASE_URL
```

That runner applies only `004_live_activity_dispatch.sql`; it does not inspect
`DATABASE_URL`, run migrations 001–003 or the billing migration, load a token-encryption or
provider-signing key, create an APNs transport, or connect on import. Migration 004 is
idempotent and additive: it adds dispatch-specific indexes plus direct-dispatch cursor and
attempt tables without rewriting migrations 001–003.

After migration 004, apply the independent publication-policy migration to the same reviewed
non-production target:

```powershell
$env:LIVE_COMMUTE_MIGRATION_DATABASE_URL = 'postgresql://localhost/blick_live_commute_dev'
npm run migrate:live-activity-publication-policy
Remove-Item Env:LIVE_COMMUTE_MIGRATION_DATABASE_URL
```

That runner applies only `005_live_activity_publication_policy.sql`. It does not inspect
`DATABASE_URL`, run another migration, load any Apple material, construct a dispatcher, or
connect on import. Migration 005 is additive and idempotent; its nullable attempt metadata
keeps Phase 4B rows valid while making incomplete accepted history explicit.

Real PostgreSQL adapter and concurrency verification uses a separate setting and command:

```powershell
$env:LIVE_COMMUTE_TEST_DATABASE_URL = 'postgresql://localhost/blick_live_commute_test'
npm run test:live-commute-postgres
npm run test:live-activity-postgres
Remove-Item Env:LIVE_COMMUTE_TEST_DATABASE_URL
```

The PostgreSQL integration suites share one fail-closed URL guard: they refuse non-local
hosts and database names without a distinct `test` segment. Each creates its own random
`blick_live_commute_test_*` schema, applies its required checked-in migrations twice,
exercises independent connections, and drops only that prefixed schema. The synthetic
PostgreSQL 17 CI job also invokes migrations 004 and 005 twice before running the combined
delivery, dispatch, and publication-history suite. Use an explicitly disposable local test database;
never substitute a real `.env` or production `DATABASE_URL`. When
`LIVE_COMMUTE_TEST_DATABASE_URL` is absent, the database-backed suites are guarded and skip.
A skipped run, unit test, or SQL-text assertion is not evidence that real PostgreSQL
transactions, constraints, migration execution, or cross-connection locking passed.

## Explicitly deferred

- Swift/SwiftUI, ActivityKit and WidgetKit client code
- real token acquisition/upload and physical-device validation
- APNs credentials, production provider-token configuration, signing-key rotation, real
  Apple/TLS-environment verification, and any production network request
- broadcast channel registry, creation/deletion execution, publication-group mapping, and
  real-environment validation of Apple's documented host behavior
- public installation enrollment/authentication routes and any user-account system
- production application of migrations 002–005, database-pool wiring, and dispatcher
  construction/configuration
- persistent snapshot history
- recurrence and timezone calculation
- GPS or location tracking
- any production recurring execution mechanism, timer, scheduler, polling loop, or cleanup job
- an outbox or delivery-queue worker, abandoned-attempt reconciliation, and compensation for
  the remaining check-to-send and START-before-update-token cleanup races
- cross-tick/process acquisition coalescing, concurrency/rate limiting, backpressure,
  monitoring/metrics, multi-connection APNs scaling, and any required cross-process
  provider-token issuance coordination
- production freshness/stale timing values and real client reporting of
  `frequentPushesEnabled`
- initial stale-deadline establishment for an unchanged accepted START, plus device validation
  of the resulting ActivityKit stale-state transition
- localized English/Swedish START alerts and finalized START priority policy
- global cross-process publication-cycle lease, queue/outbox recovery, and recurring
  approximately 30-second activation
- narrow END cleanup authority, including the START-before-update-token gap

No timer, sleep loop, recurring worker, self-HTTP callback, or Vercel Cron configuration is added here.
Vercel Cron uses minute-granularity expressions and, even on paid plans, schedules within
the selected minute; it is not an exact 30-second scheduler. See Vercel's current
[Cron usage limits](https://vercel.com/docs/cron-jobs/usage-and-pricing) and
[accuracy guidance](https://vercel.com/docs/cron-jobs/manage-cron-jobs#cron-jobs-accuracy).
The existing Android active-window worker, notifications, widgets, and approximately
30-second loop remain unchanged, as do `/departures`, `/journeys`, journey-role selection,
Google Play billing, and English/Swedish presentation behavior. No public route invokes the
store-backed coordinator, snapshot engine, or direct dispatcher. Import and application
startup therefore open no production database or APNs connection and perform no production
SL or Apple request until a future execution mechanism is deliberately reviewed and wired.
