# iOS Live Activity architecture

Status: backend foundation only, verified 2026-09-11. No iOS target, Apple credentials,
push integration, session persistence, or production scheduler exists in this phase.

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
 canonical active-query groups
          |
          v
 one fresh transit acquisition per group
          |
          v
 platform-neutral publisher seam
```

This is the scaling boundary: one hundred active sessions with the same canonical query
produce one transit acquisition group, not one hundred SL polls. Session, installation,
routine, and user-facing label identity never split an otherwise identical query.

Canonical acquisition identity follows current production behavior:

- `LINE_DIRECTION`: `siteId`, normalized `transportMode`, nullable `lineId`, and nullable
  `directionCode`. Null remains a meaningful wildcard. `lineDesignation`, destination text,
  site name, and routine name are display metadata, not acquisition identity. Live polling
  omits the setup-only `forecast` parameter.
- `EXACT_DESTINATION`: Journey Planner `originId`, `destinationId`, a sorted/deduplicated
  transport-mode allow-list, `changesPreference`, and absolute `searchUntil`. The live
  contract is fixed to `searchMode=NOW` and `laterJourneyCount=0`; the latter keeps
  foreground-only supplemental journey discovery out of background live state. Current
  Android behavior supplies the concrete occurrence end as `searchUntil`, so distinct end
  instants must not share a group.

The canonical key is readable deterministic JSON built from an explicit fixed-order
representation. It is not an opaque hash and does not depend on caller property insertion
order.

Because acquisition is asynchronous, every acquisition group must be converted to a
publication group at the actual publication instant. That second lifecycle check removes
sessions that reached `endsAt` while the transit request was in flight; if none remain, no
publication occurs.

## Apple delivery direction

iOS Live Activities will use ActivityKit. ActivityKit exposes a device-specific
push-to-start token that a server can eventually use for a device-targeted remote start.
Broadcast push notifications cannot start a Live Activity; they update activities already
subscribed to a channel and do not replace the device-targeted start request.

On iOS 18 and later, that individual start payload can include `input-push-channel`. The
new Live Activity then listens on the named channel, and later dynamic updates can be sent
once to every activity subscribed to that channel. This matches Blick's canonical grouping:
start each user's routine occurrence individually, then broadcast an identical update only
when the complete transit query and resulting payload are genuinely shared. See Apple's
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
secondary and must never delay primary departure/journey publication. Future ActivityKit
payloads must set `stale-date` so the system can mark data out of date when a newer push does
not arrive; Apple documents the corresponding stale behavior in
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

No timer, sleep loop, worker, or Vercel Cron configuration is added here. Vercel Cron uses
minute-granularity expressions and, even on paid plans, schedules within the selected minute;
it is not an exact 30-second scheduler. See Vercel's current [Cron usage limits](https://vercel.com/docs/cron-jobs/usage-and-pricing)
and [accuracy guidance](https://vercel.com/docs/cron-jobs/manage-cron-jobs#cron-jobs-accuracy).
The existing Android active-window worker and its approximately 30-second loop remain
unchanged, and this foundation performs no production SL request at all.
