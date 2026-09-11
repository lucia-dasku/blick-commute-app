# iOS Live Activity architecture

Status: platform-neutral snapshot engine, verified 2026-09-11. No iOS target, Apple
credentials, push integration, session persistence, or production scheduler exists.

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
 one fresh transit acquisition per group
          |
          v
 full-query publication groups
          |
          v
 snapshots + publication outcomes
```

Acquisition and publication are deliberately separate scaling boundaries. An
`AcquisitionKey` identifies one upstream request whose raw normalized result is safe to
share. A `PublicationKey` identifies sessions whose final filtered dynamic state can be
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
the post-acquisition instant. That second lifecycle check removes sessions that reached
`endsAt` while the transit request was in flight; if none remain, no publication outcome is
created for that group.

## Snapshot engine

`runLiveCommuteTick` is a one-tick orchestration function with an injected clock. It plans
active sessions, runs acquisition groups independently, captures one completion instant per
acquisition group, projects all still-active publication groups at that same instant, and
returns results. Independent acquisition groups run concurrently, and one failure does not
suppress unrelated successful groups.

LINE acquisition calls the existing `SlTransportClient` directly and passes its response
through the existing departure normalizer. It never calls Blick's own `/departures` route.
The normalized site response is filtered by mode, optional line, and optional direction;
departures whose effective time is before the projection instant are removed, future
cancelled departures remain, and the next five are retained as a bounded rollover reserve.

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

The semantic fingerprint includes visible transit fields, roles, operational state, and
fresh/stale presentation state. It excludes both acquisition and generation timestamps, so a
new fetch containing identical content is not reported as a content change. Expiry,
rollover, realtime changes, cancellation, role changes, and relevant journey-structure
changes are semantic changes. This signal is intentionally not a push-frequency policy:
future stale-date renewal may still require a heartbeat for unchanged content.

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
once to every activity subscribed to that channel. Broadcast channels map to publication
groups, not necessarily acquisition groups: one site-level acquisition may feed several
channels whose line/direction filters differ. Start each user's routine occurrence
individually, then broadcast an identical update only within its publication group. See Apple's
[ActivityKit push-notification guide](https://developer.apple.com/documentation/ActivityKit/starting-and-updating-live-activities-with-activitykit-push-notifications),
[broadcast setup](https://developer.apple.com/documentation/UserNotifications/setting-up-broadcast-push-notifications),
and [WWDC24 broadcast overview](https://developer.apple.com/videos/play/wwdc2024/10069/).

ActivityKit supports frequent updates, but delivery has a system-controlled budget and may
be throttled. A future Blick engine may aim to acquire fresh state approximately every 30
seconds while a session is active, but it cannot promise that every corresponding push will
arrive on an exact 30-second boundary. Users can also disable frequent Live Activity pushes.

Ordinary WidgetKit widgets are not the 30-second live engine. Widget extensions are not
continuously active, reloads are budgeted and scheduled by the system, and Apple recommends
timeline entries at least about five minutes apart. See [Keeping a widget up to date](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date).

## Freshness and authoritative state

Future live payloads must carry absolute departure/journey timestamps. Countdown rendering
should derive from those timestamps and the current device/system time; a push should not be
sent merely to turn “4 min” into “3 min”. Before acquisition results are published, expired
departures and journeys must be filtered again against the publication instant. Exact-
destination `PRIMARY`, `NEXT`, and optional `ALTERNATIVE` roles remain backend-authoritative.

Fresh transit changes, cancellations, a changed authoritative role set, and meaningful
disruption changes require new live state. Disruption acquisition/enrichment remains
secondary and must never delay primary departure/journey publication. A future publisher
will choose an ActivityKit `stale-date` policy so the system can mark data out of date when a
newer push does not arrive; this phase does not invent that duration. Apple documents the
corresponding stale behavior in
[`ActivityContent.staleDate`](https://developer.apple.com/documentation/activitykit/activitycontent/staledate).

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
- production concurrency and upstream rate-limit policy across independent acquisition groups
- heartbeat and push-frequency policy

No timer, sleep loop, worker, self-HTTP callback, or Vercel Cron configuration is added here. Vercel Cron uses
minute-granularity expressions and, even on paid plans, schedules within the selected minute;
it is not an exact 30-second scheduler. See Vercel's current [Cron usage limits](https://vercel.com/docs/cron-jobs/usage-and-pricing)
and [accuracy guidance](https://vercel.com/docs/cron-jobs/manage-cron-jobs#cron-jobs-accuracy).
The existing Android active-window worker and its approximately 30-second loop remain
unchanged. No public route invokes the snapshot engine, so it performs no production SL
request until a future execution mechanism is deliberately wired.
