# iOS Live Activity architecture

Status: Phase 3B backend foundation, implemented locally 2026-09-12. In addition to the
accepted Phase 3A ownership/session authority, it provides protected ActivityKit token
registries, exact-session-revision delivery bindings, and an authority-revalidating internal
delivery-target seam. It remains callable internal code: no production caller, public route,
timer, worker, scheduler, or APNs client invokes it.

The implemented boundary includes immutable session planning and grouping, direct SL
acquisition, authoritative exact-journey role reuse, one final-clock projection, fresh/stale
fallback projection, semantic comparison, persistent ownership and lifecycle records,
revision-controlled mutations, and an authoritative post-acquisition store check. Snapshot
history persistence, public enrollment, scheduling, publication, push delivery, and client
rendering remain deferred. There is no iOS target, Apple credential, APNs sender integration,
or production migration wiring. All Apple behavior described below is derived from Apple's
public documentation and has not been verified with an iOS target, physical device, or APNs
setup.

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
   `authorityCheckCompletedAt` for a future dispatcher. The latter is the application clock
   used for final projection after the check returns, not a database snapshot timestamp or
   authorization lease.

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
final time projection only; it does not extend authorization. A session can be cancelled,
replaced, revoked, or deleted during result handling or before a future APNs send. The
dispatcher must recheck the attached session versions and coordinate that check with its
send/ordering mechanism. Phase 3A deliberately adds no outbox, queue, lease, distributed
scheduler, exactly-once claim, or solution to this check-to-send race.

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

### Still deferred

- iOS/ActivityKit code that obtains, observes, and uploads real tokens
- physical-device and real Apple-environment verification
- Apple Developer account, APNs credentials, signing JWTs, and network requests
- broadcast-channel creation and real channel identifiers
- ActivityKit start/update/end payload schemas and APNs response handling
- public enrollment/token/binding routes, their rate limits, and abuse controls
- production key management, rotation, retention, deletion, and operational access policy
- stale-date, freshness-heartbeat, scheduler, dispatcher, queue/outbox, and check-to-send
  coordination
- cross-instance transit acquisition coordination and App Store configuration

APNs delivery remains best effort and distinct from backend session authority. A successful
authority lookup is a point-in-time check, not a lease across a later network request. None
of the Apple behavior or token shapes above has been exercised on a real device.

Ordinary WidgetKit widgets are not the 30-second live engine. Widget extensions are not
continuously active, reloads are budgeted and scheduled by the system, and Apple recommends
timeline entries at least about five minutes apart. See [Keeping a widget up to date](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date).

## Freshness and authoritative state

Future live payloads must carry absolute departure/journey timestamps. Countdown rendering
should derive from those timestamps and the current device/system time; a push should not be
sent merely to turn “4 min” into “3 min”. Before acquisition results are published, expired
departures and journeys must be filtered again against the publication instant. The future
client must also reject expired absolute timestamps locally: backend pre-filtering cannot
prove correct presentation when a push is delayed, throttled, reordered, or never delivered.
Exact-destination `PRIMARY`, `NEXT`, and optional `ALTERNATIVE` roles remain
backend-authoritative. Apple describes broadcast delivery as best effort in
[Sending broadcast push notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-broadcast-push-notification-requests-to-apns).

Fresh transit changes, cancellations, a changed authoritative role set, and meaningful
disruption changes require new live state. Disruption acquisition/enrichment remains
secondary and must never delay primary departure/journey publication. A future publisher
will choose an ActivityKit `stale-date` policy so the system can mark data out of date when a
newer push does not arrive; this phase does not invent that duration. Passing `stale-date`
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
plus comparison-only digests and lifecycle metadata. They do not store token plaintext,
Apple signing keys, raw installation bearer credentials, purchase tokens, copied billing
credentials, or user-account data. ActivityKit tokens are installation-linked delivery
identifiers and must be treated as sensitive operational data even when encrypted. They are
excluded from safe DTOs, errors, logs, snapshots, and committed fixtures; tests use only
synthetic byte sequences. The Apple delivery migration and services remain separate from
Google Play purchase state.

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
operational monitoring, migration/rollback procedures, and dispatcher coordination for the
remaining check-to-send race.

## Local migration and PostgreSQL verification

Migration execution is manual and scoped to the live-commute schema file. From `backend`, set
a dedicated non-production target explicitly, run the command, and then clear the process
variable:

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
exercises independent one-connection pools, and drops only that prefixed schema. Use an
explicitly disposable local test database; never substitute a real `.env` or production
`DATABASE_URL`. When `LIVE_COMMUTE_TEST_DATABASE_URL` is absent, the database-backed suites
are guarded and skip. A skipped run, unit test, or SQL-text assertion is not evidence that
real PostgreSQL transactions, constraints, migration execution, or cross-connection locking
passed.

## Explicitly deferred

- Swift/SwiftUI, ActivityKit and WidgetKit client code
- real token acquisition/upload and physical-device validation
- APNs credentials, channel management, signing, payloads, and network requests
- public installation enrollment/authentication routes and any user-account system
- production application of the migration, database-pool wiring, and provider configuration
- persistent snapshot history
- recurrence and timezone calculation
- GPS or location tracking
- any production execution mechanism, timer, scheduler, polling loop, or cleanup job
- an outbox, delivery queue, dispatcher, and check-to-send coordination
- cross-tick/process acquisition coalescing, concurrency/rate limiting, backpressure,
  monitoring, and publication ordering
- heartbeat and push-frequency policy

No timer, sleep loop, worker, self-HTTP callback, or Vercel Cron configuration is added here. Vercel Cron uses
minute-granularity expressions and, even on paid plans, schedules within the selected minute;
it is not an exact 30-second scheduler. See Vercel's current [Cron usage limits](https://vercel.com/docs/cron-jobs/usage-and-pricing)
and [accuracy guidance](https://vercel.com/docs/cron-jobs/manage-cron-jobs#cron-jobs-accuracy).
The existing Android active-window worker, notifications, widgets, and approximately
30-second loop remain unchanged, as do `/departures`, `/journeys`, journey-role selection,
Google Play billing, and English/Swedish presentation behavior. No public route invokes the
store-backed coordinator or snapshot engine, so this foundation opens no production database
connection and performs no production SL or Apple request until a future execution mechanism
is deliberately reviewed and wired.
