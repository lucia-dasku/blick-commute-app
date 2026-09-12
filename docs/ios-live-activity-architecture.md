# iOS Live Activity architecture

Status: platform-neutral snapshot engine, locally verified 2026-09-12. It is callable code
with automated coverage, but no production caller invokes it. No iOS target, Apple
credentials, push integration, session persistence, or production scheduler exists.

The implemented boundary is deliberately narrow: immutable session planning and grouping,
direct SL acquisition, authoritative exact-journey role reuse, one final-clock projection,
fresh/stale fallback projection, semantic comparison, and structured outcomes for one tick.
Persistence, scheduling, overlap coordination, publication, push delivery, and client
rendering are deferred. All Apple behavior described below is derived from Apple's public
documentation and has not been verified with an iOS target, physical device, or APNs setup.

## Intended system shape

Blick will model each concrete routine occurrence as an absolute live session and partition
sessions with half-open interval semantics: `[startsAt, endsAt)`. Only active sessions are
eligible for acquisition or publication. Future and expired sessions are retained in the
plan for lifecycle handling but never enter an acquisition group.

```text
installation-scoped sessions
          |
          v
  lifecycle planner -----> future / expired (no acquisition)
          |
          v
 site/request acquisition groups
          |
          v
 one logical transit acquisition per group
          |
          v
 full-query publication groups
          |
          v
 snapshots + publication outcomes
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

This is a time-only revalidation of the sessions supplied to the tick. With no authoritative
session store or reload port, the engine cannot observe a routine occurrence being cancelled,
deleted, or replaced while acquisition is in flight. Production orchestration must resolve
that lifecycle boundary before publication; this phase does not claim it is handled.

## Snapshot engine

`runLiveCommuteTick` is a scheduler-independent, one-tick orchestration function with an
injected clock. It plans active sessions, runs acquisition groups independently, records an
individual completion instant and source timestamp for each acquisition, then reads one
final clock after all groups settle and projects every still-active publication group at
that instant. Independent acquisition groups run concurrently, and one failure does not
suppress unrelated successful groups.

The tick-start plan is execution input only and is not exposed on the tick result because its
groups may age while I/O is running. Only `publications[].group` is returned as an actionable
publication plan, after final-clock session revalidation.

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
only from caller-supplied previous state; there is no persistence implementation here. Its
original `sourceFetchedAt` is preserved, LINE departures are re-filtered against the new
clock, and exact roles remain unchanged. If PRIMARY has expired, its absence is represented
honestly rather than relabelling another journey.

Fallback lookup requires the complete matching `PublicationKey` and snapshot kind. A fresh
empty snapshot is an authoritative replacement for older rows, not a reason to resurrect
them; a future persistence layer must store that latest empty state. `FRESH` and `STALE`
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

## Apple delivery direction

iOS Live Activities will use ActivityKit. ActivityKit exposes a device-specific
push-to-start token that a server can eventually use for a device-targeted remote start.
Broadcast push notifications cannot start a Live Activity; they update activities already
subscribed to a channel and do not replace the device-targeted start request.

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

## Explicitly deferred

- Swift/SwiftUI, ActivityKit and WidgetKit client code
- push-to-start and per-activity token persistence
- APNs credentials, channel management, signing, and network requests
- any database migration or account system
- recurrence and timezone calculation
- GPS or location tracking
- a production execution mechanism
- cross-tick/process acquisition coalescing, concurrency/rate limiting, backpressure,
  monitoring, and publication ordering
- heartbeat and push-frequency policy

No timer, sleep loop, worker, self-HTTP callback, or Vercel Cron configuration is added here. Vercel Cron uses
minute-granularity expressions and, even on paid plans, schedules within the selected minute;
it is not an exact 30-second scheduler. See Vercel's current [Cron usage limits](https://vercel.com/docs/cron-jobs/usage-and-pricing)
and [accuracy guidance](https://vercel.com/docs/cron-jobs/manage-cron-jobs#cron-jobs-accuracy).
The existing Android active-window worker and its approximately 30-second loop remain
unchanged. No public route invokes the snapshot engine, so it performs no production SL
request until a future execution mechanism is deliberately wired.
