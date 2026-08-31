# Blick — API contract and upstream mapping

This document is the source of truth for the backend's public contract and for how
every normalized field maps back to the upstream SL APIs. It is kept in sync with the
implementation under `backend/src`; the normalization code links back to this file in
comments at the points where a non-obvious decision was made.

## Premium and Journey Planner additions

### `POST /api/v1/billing/verify`

Request JSON: `{ "productId": "blick_premium_lifetime", "purchaseToken": "..." }`.
The product ID is closed to that one non-consumable product. The backend exchanges its Google
service-account assertion for an OAuth access token, calls Android Publisher Product Purchases
v2 for the configured package, grants entitlement only for a completed, unrefunded purchase,
and acknowledges an unacknowledged valid purchase through the publisher API. The success data is
`{ productId, verified, state, verifiedAt }`; `verifiedAt` is the last time Google was queried,
not the time a cached record was read. A PostgreSQL record keyed by the SHA-256 purchase-token
fingerprint stores the minimum lifecycle fields needed for entitlement. Raw purchase tokens are
never persisted. Transaction-scoped advisory locks make duplicate/concurrent verification and
acknowledgement idempotent. Active records are revalidated with Google at least every six hours;
pending and inactive records use shorter intervals. The route has a billing-only fixed-window
limit keyed by the token fingerprint plus a global billing limit. It never uses an IP address as
purchase identity and does not affect transit endpoints. Credentials never enter the Android app
or repository. Responses use the existing sanitized error envelope and `Cache-Control: no-store`.

### `POST /api/v1/billing/rtdn`

This is the Google Cloud Pub/Sub push target for Google Play Real-time Developer Notifications,
not an Android-client API. It requires a Google-signed OIDC bearer token with the exact configured
audience, verified email and configured push service-account email. The Pub/Sub envelope and
Google Play payload are validated strictly. Each Pub/Sub message ID is claimed durably; completed
IDs are idempotent and failed claims may be retried. Purchase, cancellation, refund and revocation
notifications force a Product Purchases v2 lookup, so notification fields alone never grant or
remove entitlement. PostgreSQL event times prevent an older delivery from overwriting newer state.
`pendingRefundReviewNotification` is different: it is a chargeback review request, not a final
refund. Blick responds through `orders.reviewrefund` with `NEUTRAL`, declares that functionality
information was provided before purchase, submits no usage evidence, and leaves entitlement
unchanged. Blick has no account or
purchase-usage history from which to make a more specific recommendation. The pending-refund token
is used only for that Google API call and is not persisted. A later voided-purchase notification
continues through the normal authoritative revalidation path and removes entitlement when Google
reports that ownership is no longer valid.
Processed notification claims are retained for 90 days; inactive purchase records are retained
for 24 months. The endpoint returns `204` after successful or already-completed processing.

### `GET /api/v1/journeys/locations/search?query=`

Resolves a user-entered stop/location through Journey Planner Stop Finder and returns supported
global identifiers plus display names. Android must persist these identifiers; SL Transport's
numeric site IDs are not assumed to be compatible.

### `GET /api/v1/journeys?originId=&destinationId=&transportModes=METRO,TRAIN,BUS&searchUntil=&changesPreference=&searchMode=&requestedDateTime=`

Calls Journey Planner Trips, normalizes complete legs (first public transport mode/line,
departure and final arrival, transfer count, realtime flags, stop names, disruptions, and each
leg's own canonicalized stop-area sequence — see `backend/src/domain/routePattern.ts`), and maps
the requested allow-list to SL's `incl_mot_*` parameters. Walking transfer legs are always
permitted; a journey using any unselected public mode is rejected defensively. `transportModes`
defaults to all regular modes for backward compatibility and rejects empty or unsupported
selections. For live requests, `searchUntil` (an ISO-8601 instant) bounds how far forward the
backend's targeted acquisition may search for NEXT/ALTERNATIVE; a malformed value is a validation
error, but an absent one is not — it means "answer from the initial live acquisition alone", never
an invented search horizon. Planned chooser acquisition is independently capped at two targeted
requests and does not use a horizon. `changesPreference` (`DIRECT_ONLY` | `BOTH` |
`WITH_CHANGES_ONLY`, defaulting to `BOTH`)
narrows the whole eligible candidate pool BEFORE PRIMARY/NEXT/ALTERNATIVE selection — see
`backend/src/services/candidateCollector.ts`'s own `JourneyChangesPreference` doc — so a
`DIRECT_ONLY` response's roles are always genuinely direct, never a mixed selection with
disallowed rows merely hidden afterward; an unrecognized value is a validation error. Upstream
timeout/network/schema failures retain the existing error-envelope behavior. Responses are
public-cacheable for 30 seconds so app/detail/widget consumers do not independently amplify
upstream traffic.

`searchMode` is explicit and defaults to `NOW` when omitted. A `NOW` request rejects
`requestedDateTime` and keeps the existing departure-at-current-time acquisition behavior.
`LEAVE_AT` and `ARRIVE_BY` require `requestedDateTime` as a future, whole-minute ISO-8601
timestamp with an explicit offset (for example `2026-09-17T18:30:00+02:00`); timezone-less,
malformed, past, and sub-minute values are rejected. Planned requests also reject `searchUntil`,
which remains a live routine-window boundary. `LEAVE_AT` maps the requested instant to SL's
departure search; `ARRIVE_BY` maps it to SL's native arrival search. Planned requests never enter
the live PRIMARY/NEXT/ALTERNATIVE selectors or acquisition loop. If the initial best-match batch
does not cover both sides of the planned recommendation, the backend can complement it with a
`leastinterchange` search at the original planned instant and a departure search from the current
recommendation's minute. A complete initial chooser spends no follow-up request; no planned lookup
spends more than two.

Every response identifies its meaning directly with `journeyContext` (`LIVE` or `PLANNED`),
`searchMode`, and canonical `requestedDateTime` (`null` for `NOW`, otherwise an explicit UTC ISO
instant). `fetchedAt` remains the time the backend performed the lookup, never the future planning
anchor. Live responses use `PRIMARY`/`NEXT`/`ALTERNATIVE`; planned responses use the separate
`EARLIER`/`RECOMMENDED`/`LATER` vocabulary and return those choices in chronological departure
order. For `ARRIVE_BY`, every planned choice must arrive by the requested deadline. For `LEAVE_AT`,
every choice must depart at or after the requested instant. `RECOMMENDED` is selected by a
deterministic, planned-specific lexicographic quality order using arrival/departure, transfers,
known walking, and total duration rather than simply taking the first or absolute latest trip.
Within an otherwise equal-comfort ARRIVE_BY tier, an interior candidate is preferred when it
preserves both an earlier and a later deadline-safe choice; this is a structural robustness rule,
not a fixed-minute threshold.
`EARLIER` and `LATER` are the closest distinct useful departures on either side; unlike live NEXT,
they may use completely different route families. Exact duplicate departure/arrival opportunities
are collapsed, while the same line at genuinely different times remains distinct. If a useful
neighbor does not exist, one or two choices are valid.
Because planning mode and requested time are query parameters at whole-minute precision, CDN
cache identity separates live, departure-planned, arrival-planned, and different planned minutes.

Each journey additionally carries `disruptionContext` (`{ version, journeyStart, journeyEnd,
legs: [{ transportMode, lineDesignation, boardingPatternPointGid?, alightingPatternPointGid?,
stopPatternPointGids, stopSequenceComplete }] }`) — purely structural metadata extracted from the
SAME Journey Planner response already being normalized (no extra upstream call, no
`StopPointDirectory` lookup — see "Resolving Journey Planner notices + matched SL Deviations"
below for what actually reads it). Android does not interpret this: it retains the live PRIMARY's
or planned RECOMMENDED's copy unchanged and sends it back verbatim as part of
`POST /api/v1/journeys/disruptions`.

### `POST /api/v1/journeys/disruptions`

The single authoritative source of exact-destination disruption relevance — resolves a journey's
own `disruptionNotices` (above, sent in the request body) together with SL Deviations matched to
its transit legs, read from the SAME shared cached snapshot `/api/v1/disruptions` uses — no new
upstream SL Deviations request. When the request also carries a recognized `disruptionContext`
(above), this additionally reads a second shared, independently-cached snapshot —
`StopPointDirectory`, backed by SL Transport's own `/v1/stop-points` — to verify not just the
journey's origin but its destination and every transfer/intermediate stop too. Returns each
disruption tagged `CONFIRMED` or `LINE_RELEVANT`, never a plain relevant/unrelated binary — see
"Resolving Journey Planner notices + matched SL Deviations" below for the full request/response
shape, the relevance model, and matching rules.

Each returned LIVE journey carries a `role` of `PRIMARY`, `NEXT`, or `ALTERNATIVE` — Android renders
off this field and never infers a role from list position (see `backend/src/routes/journeys.ts`'s
own doc for the full model):

- Two journeys are **route-compatible** when their public-transport legs structurally match — same
  leg count, same transport mode per leg, same boarding/alighting stop per leg (canonical
  stop-area id, not platform id, so a platform change alone never breaks compatibility), and stop
  sequences that are either identical or one an ordered subsequence of the other (a local/express
  pair, or a different line through the same corridor). Line designation is never compared. This
  is a pairwise "is this candidate a legitimate stand-in for PRIMARY" check, not a globally
  transitive equivalence class (see `backend/src/domain/routePattern.ts`'s own doc for a
  counterexample) — Blick only ever asks it about one candidate against the current PRIMARY.
- PRIMARY and NEXT are selected directly from the full eligible candidate pool, deliberately
  WITHOUT a Pareto-dominance pass first — dominance and "soonest compatible departure" are
  different questions, and filtering globally can silently eliminate the correct NEXT (see
  `backend/src/domain/journeyRoles.ts`'s own doc for the two concrete failure modes this avoids).
  Dominance is applied only once, scoped to the candidates that already structurally qualify as
  ALTERNATIVE (see below): journey B is discarded when another qualifying journey A is no worse on
  departure time (a *later* departure counts as no worse when arrival is no later), final arrival,
  transfer count, and total walking duration, and strictly better on at least one — never a
  minute-based "is this close enough" threshold. Walking participates in that comparison only when
  BOTH candidates have a known walking duration; if either side's is unknown (SL didn't report
  one), dominance is never established between them at all, on any dimension — an unknown value is
  never treated as zero, as equal, or as automatically no worse.
- **PRIMARY** is the current regular route's own next departure — deterministic lexicographic
  order (earliest arrival, then fewer transfers, then less known walking, then a later departure,
  then journey id), never a weighted score.
- **NEXT** is the earliest still-current departure route-compatible with PRIMARY that departs after
  it; two candidates departing at exactly the same effective instant are broken by the same
  earliest-arrival/fewer-transfers/less-known-walking order, then journey id — never by journey id
  alone. SL only ever returns up to 3 trips per request, so when NEXT isn't in the initial batch
  the backend issues further, narrower requests (anchored just after PRIMARY's own departure,
  restricted to its own route's transport modes and transfer count) until NEXT is found,
  `searchUntil` is reached, or SL can make no further forward progress. A batch that merely repeats
  an already-seen set of journeys never stops this on its own — SL is a best-match proposal
  service, not exhaustive pagination, so a repeated response doesn't prove a further request
  couldn't still expose something new; only reaching the boundary, an outright empty response, a
  lack of forward progress, or the shared per-request upstream budget being spent ends the search.
- **ALTERNATIVE** is a useful journey that is NOT route-compatible with PRIMARY, that departs after
  PRIMARY, before NEXT, and arrives strictly before NEXT's own arrival — only ever searched for
  once both PRIMARY and NEXT are known, using the same targeted-acquisition strategy. A compatible
  journey discovered during that search is never treated as an alternative; it reclassifies NEXT
  (and the interval still being searched) before alternative candidates are evaluated again.
- Every candidate the backend has ever seen for one request is kept as a single pool entry per
  journey id, holding the MOST RECENTLY returned representation of it — a later batch's updated
  realtime data (a delayed departure, a revised arrival estimate, a changed transfer count)
  replaces what an earlier batch reported, rather than the first observation being kept forever.
  PRIMARY, NEXT, and ALTERNATIVE are all re-derived from scratch after every batch, so an update to
  a journey already in the pool can change any of the three roles exactly as a brand-new discovery
  could — including PRIMARY itself. If that happens mid-search, the NEXT/ALTERNATIVE search
  in flight (which was targeted to the OLD PRIMARY's own transport modes/transfer count) is
  abandoned and restarted against the new PRIMARY, never left running against a journey that is no
  longer current (see `backend/src/routes/journeys.ts`'s own `resolveSelection` doc for the exact
  state machine). This retargeting draws from the same shared upstream request budget as everything
  else — it cannot itself cause unbounded requests.
- The backend logs one structured `journey_acquisition_metrics` line per request (SL call counts
  broken down by phase, whether PRIMARY retargeted, whether the budget was exhausted) purely for
  operational visibility. It carries no station names, stop ids, or journey payloads, and is never
  part of the response body.

A response therefore contains `PRIMARY` alone, `PRIMARY, NEXT`, or `PRIMARY, ALTERNATIVE, NEXT`
(in that departure order) — never an unrelated journey mislabelled `NEXT` merely to fill a second
slot, and never more than one upstream request in the common case where PRIMARY and NEXT are both
already in the initial batch and no alternative exists to find.

#### Journey disruption notices (`disruptionNotices`)

Each journey's `disruptions: string[]` (raw Journey Planner `infos` text, flattened across every
leg, unchanged) is joined by an additive `disruptionNotices: { text, effect }[]` — the same raw
text, deduplicated (identical text repeated across legs collapses to one entry, first-occurrence
order preserved) and classified with the exact same nine-effect classifier `/api/v1/disruptions`
uses (`classifyEffectFromText` in `backend/src/normalize/classifyDisruptionEffect.ts` — see
"Disruption effect classification" below), never a second, independent set of rules. Journey
Planner `infos` have no header/details split the way an SL Deviations message does, so the
lower-level single-string classifier is called directly on each notice; `"DISRUPTION"` is the
same conservative fallback for text it cannot confidently categorize. `text` is always SL's own
unmodified notice — never translated, summarized, or replaced by the classification label.

This exists specifically so an `EXACT_DESTINATION` routine — which has no `siteId`/`lineId` to
query `/api/v1/disruptions` with, and for which a network-wide Deviations snapshot is the wrong
relevance scope anyway — can derive its own live disruption relevance directly from the SAME
`/api/v1/journeys` response it already re-fetches roughly every 30 seconds, from whichever
journey currently holds the `PRIMARY` role, with no additional upstream request. See the Android
client's `RoutineActiveWindowWorker` (`android/app/src/main/java/se/blick/app/scheduling/`) for
exactly how PRIMARY's notices become the ongoing notification's classified summary line, the
widget's disruption strip, and Routine Details' own disruption cards. An `EXACT_DESTINATION`
routine never calls `/api/v1/disruptions` itself — that endpoint, and everything described in §3
below, remains `LINE_DIRECTION`-only — but see "Resolving Journey Planner notices + matched SL
Deviations" immediately below for the one additional, still-`disruptions`-endpoint-free source it
does consult.

#### Resolving Journey Planner notices + matched SL Deviations (`/api/v1/journeys/disruptions`)

Journey Planner's own `infos` (the source of `disruptionNotices` above) is **not** a reliable
disruption source on its own: SL Journey Planner can silently reroute a journey around an active
disruption — e.g. terminating a metro line short of its usual destination and continuing on foot
or another line — without attaching any notice text to the resulting legs at all. Confirmed live:
an Akalla → Kungsträdgården `PRIMARY` journey using Metro 11 (rerouted to terminate at
T-Centralen) had `disruptions: []`/`disruptionNotices: []` on every leg, while SL Deviations
simultaneously listed an active `NO_SERVICE` deviation for exactly that corridor
(`affectedLines`: Metro 10 + 11, `affectedStopAreas: []`).

`POST /api/v1/journeys/disruptions` closes this gap. It is the single authoritative source of
exact-destination disruption relevance — all matching AND combination logic lives in
`backend/src/domain/disruptionRelevance.ts`'s own `resolveJourneyDisruptions`; the route itself is
a thin HTTP adapter, and Android performs no relevance inference of its own. A genuinely separate
HTTP call from `/api/v1/journeys`, deliberately never baked into that response, so a
disruption-relevance lookup can never delay or couple to the critical PRIMARY journey update (see
the Android client's own "primary data first, disruption lookup second" timing, mirroring
`LINE_DIRECTION`'s existing pattern). No new upstream SL request is introduced: this reads the
exact same shared, already-cached SL Deviations snapshot `/api/v1/disruptions` itself reads
(`deviationsSnapshotService.ts`).

**Why a POST, not a GET**: the request body carries an arbitrary-length list of the journey's own
already-fetched Journey Planner notices (see below) — sent back specifically so this ONE endpoint
can perform the full combine/dedupe/merge in one authoritative place, rather than splitting that
decision between the backend and Android.

Request body: `{ legs: {transportMode, lineDesignation}[], originSiteId?, journeyPlannerNotices:
{text, effect}[], disruptionContext?, departureTime?, arrivalTime? }` — `legs` are PRIMARY's own
transit legs (a WALK leg or one with no line designation carries no line-scope signal and must
never be sent); `originSiteId` is the routine's own SL-Transport-namespace origin site id, absent
when unavailable; `journeyPlannerNotices` is the journey's own already-fetched `disruptionNotices`,
unchanged. `disruptionContext` is PRIMARY's own additive structural metadata from
`GET /api/v1/journeys` (see above), sent back unchanged; `departureTime`/`arrivalTime` are
PRIMARY's own real travel interval, enabling the temporal-relevance check below — both pairs are
optional and independently backward compatible (an older Android build simply omits them).

Response: `{ fetchedAt, disruptions: ResolvedJourneyDisruption[] }`, where each entry is
`{ id?, headline, details?, effect, relevance, source, matchedLineDesignations }` — unchanged by
this feature.

**Relevance model** — three semantic outcomes, only two of which are ever represented in the
response (an `UNRELATED` deviation is filtered out entirely, never returned as a value):

- **`CONFIRMED`**: structured evidence proves this disruption affects the journey — either a
  Journey Planner notice was attached directly to PRIMARY (the strongest possible evidence, since
  Journey Planner itself already scoped it to this exact journey), or an SL Deviation's own
  structured stop scope (`scope.stop_areas` and/or `scope.stop_points`) genuinely intersects the
  journey's own resolved scope of the effect-appropriate kind (see "Structured stop scope" below).
  Blick may present the real classified `effect` (e.g. "No service") as definitely true for this
  journey.
- **`LINE_RELEVANT`**: an SL Deviation's line/mode scope matches a PRIMARY leg (via
  `matchedLineDesignations`), but the currently available structured stop evidence cannot prove
  the affected segment/stop intersects this exact journey — either the deviation has no stop scope
  at all (SL did not scope it to specific stops), or the journey's own relevant scope is only
  `PARTIAL` and does not itself intersect. Blick must NOT present the real classified `effect` as
  proven for this journey's own segment; only a conservative, line-scoped warning is appropriate on
  the client. Never produced for a deviation with no line scope at all (see rule 2 below).
- **`UNRELATED`** (never returned): temporal validity does not overlap the journey, line/mode does
  not match PRIMARY at all, or the deviation's own stop scope is verified to exclude the journey's
  `COMPLETE` relevant scope. `affectedModes` alone is never treated as relevance evidence.

**Structured stop scope — ACCESS_POINTS vs. TRAVELLED_PATH**: different disruption effects have
different relevance scopes, computed per PRIMARY transit leg
(`backend/src/domain/journeyDisruptionScope.ts`):

- **`ACCESS_POINTS`** (`ACCESSIBILITY_ISSUE`, `STATION_ACCESS`, `STOP_CHANGE`): only the stop(s)
  the passenger actually boards/alights at for that leg — an ordinary intermediate stop the
  passenger stays onboard through is never an access point.
- **`TRAVELLED_PATH`** (`DELAYS`, `NO_SERVICE`, `REDUCED_SERVICE`, `ROUTE_CHANGE`,
  `REPLACEMENT_SERVICE`, `DISRUPTION`): every stop actually traversed by that leg, boarding through
  alighting, including intermediate stops.

Each leg's own scope is a `{ stopAreaIds, stopPointIds, completeness }` set, `completeness` being
`COMPLETE` only when every point that scope kind should cover was independently verified —
otherwise `PARTIAL`, which a non-intersection can never be treated as a disproof for (only an
actual intersection is ever usable as `CONFIRMED` evidence at `PARTIAL` completeness). Verification
comes from `StopPointDirectory`, resolving each `disruptionContext` leg's own
`boardingPatternPointGid`/`alightingPatternPointGid`/`stopPatternPointGids` (Journey Planner
`stopSequence` platform ids) against SL Transport's `/v1/stop-points` — see "Stop identity: Journey
Planner platform ids ↔ SL Transport `pattern_point_gid`" below for the full bridge. The routine's
own proven `originSiteId` is folded in additively for the journey's own FIRST leg only, alongside
(never instead of) the platform-bridge result.

When `disruptionContext` is absent, unrecognized, or `StopPointDirectory` itself is unavailable,
every leg falls back to a synthetic, uniform, always-`PARTIAL` scope built from `originSiteId`
alone — byte-for-byte the same relevance behavior this endpoint had before this scope model
existed. This fallback is why supplying `disruptionContext` can only ever ADD precision
(`UNRELATED` becoming reachable for a genuinely unrelated stop-scoped deviation, and `CONFIRMED`
becoming reachable for a genuinely matching destination/transfer/intermediate stop), never remove
disruption coverage an older client already had.

**Matching rules** (`resolveDeviationRelevance`), in order:

1. **Temporal**: the deviation's own `publish.from`/`publish.upto` must overlap PRIMARY's own
   `departureTime`/`arrivalTime` (when supplied) — an already-ended or not-yet-started deviation
   relative to the journey's own travel interval is `UNRELATED`. Skipped entirely (never filters
   anything) when `departureTime`/`arrivalTime` are absent from the request.
2. **No line evidence at all** (`scope.lines` empty/absent): resolvable ONLY via stop evidence —
   reaches `CONFIRMED` or `UNRELATED`, but never `LINE_RELEVANT` (that state specifically means "a
   line matched but the stop is uncertain"; nothing here has a line to be uncertain about). No
   stop scope either (`scope.stop_areas` AND `scope.stop_points` both empty) → `UNRELATED` — fails
   closed rather than parsing SL's own free text. Otherwise compared against the UNION of every
   leg's own scope of the effect-appropriate kind (no line to narrow to one specific leg).
3. **Line evidence present**: exact `(transportMode, lineDesignation)` overlap required between a
   PRIMARY leg and a `scope.lines` entry — never textual/fuzzy matching. No overlap → `UNRELATED`
   (this is what keeps Slussen → Liljeholmen, Metro 13/14, correctly unaffected by an unrelated Bus
   401 delay at the same station — sharing a station is never sharing a line).
4. Given a line match and NO stop scope at all → `LINE_RELEVANT`. This is the confirmed Akalla →
   Kungsträdgården closure case (`NO_SERVICE`, `affectedLines`: Metro 10 + 11, no stop scope) — SL
   itself did not scope it to specific stops, so Blick must not invent a stricter restriction SL
   never provided, but also must not claim the specific effect is proven merely because the line
   matches.
5. Given a line match and a non-empty stop scope: compared against the UNION of only the matched
   leg(s)' own scope of the effect-appropriate kind — an exact `stop_areas` OR `stop_points`
   intersection → `CONFIRMED` (direct proof, regardless of completeness); no intersection while
   `COMPLETE` → `UNRELATED` (a genuine disproof); no intersection while `PARTIAL` → `LINE_RELEVANT`
   (fails safe — the affected stop may simply be one Blick could not verify).

Concretely, for Akalla → T-Centralen direct on Metro 11 (ACCESS_POINTS = {Akalla, T-Centralen};
TRAVELLED_PATH additionally includes every intermediate stop, e.g. Kista): a lift outage at
Kungsträdgården (never on this route) is `UNRELATED`; the same at T-Centralen or Akalla is
`CONFIRMED`; a lift outage at Kista (an intermediate, stayed-onboard stop — `ACCESS_POINTS` policy)
is `UNRELATED`, but a delay at Kista on the same line (`TRAVELLED_PATH` policy) is `CONFIRMED`.
These are the live acceptance scenarios `backend/tests/disruptionRelevance.test.ts` and
`backend/tests/journeyDisruptions.test.ts` encode directly.

#### Stop identity: Journey Planner platform ids ↔ SL Transport `pattern_point_gid`

A Journey Planner `stopSequence` node with `type: "platform"` and `isGlobalId: true` carries an
`id` (e.g. `"9025001000003272"`) that is the SAME value as one SL Transport `/v1/stop-points`
record's own `pattern_point_gid` — confirmed live (2026-08-16 architecture review) by fetching real
trips across every mode Journey Planner exposes (metro, commuter train, tram, bus, ferry), a
multi-leg interchange transferring at T-Centralen, and one transferring at Slussen (via a genuine
WALK between two DIFFERENT Slussen-named stop areas — the metro station, `1011`, and the bus
terminal, `44000`), then cross-referencing every platform-typed `stopSequence` entry returned
against a live full `/v1/stop-points` snapshot: **101 of 101 resolved, 0 unresolved, 0 ambiguous**,
every resolved stop-area name/type matching Journey Planner's own name for it. `pattern_point_gid`
was confirmed globally unique across all 14,187 live stop-point records.

This is NOT derivable by any arithmetic/substring relationship between a platform id and its own
stop-area id — live counterexamples: Fridhemsplan's platform gid ends `...3152` but its stop-area
id is `1151` (not `3151`); T-Centralen's platform gid ends `...3051` but its stop-area id is `1051`
(not `3051`). The exact `pattern_point_gid` value is the only reliable join, which is why
`StopPointDirectory` (`backend/src/services/stopPointDirectory.ts`) exists as its own identity-only
resolution service — never a formula, never name/coordinate matching. A `stopSequence` node that is
NOT `type: "platform"` (Journey Planner can echo even a leg's own origin as a coarser `type: "stop"`
node when it hasn't pinned a specific boarding platform — confirmed to happen live) is simply
unresolvable evidence, contributing nothing rather than being guessed at.

`pattern_point_gid`/`gid` values routinely exceed `Number.MAX_SAFE_INTEGER` (every one of the
14,187 live records does) — `/v1/stop-points` is therefore the one upstream response read via a
custom lossless JSON parser (`backend/src/lib/losslessJson.ts`, every number preserved as its exact
source digit string) rather than the ordinary `response.json()` path every other upstream call in
this codebase still uses unchanged.

`StopPointDirectory`'s own compact index (`patternPointGid -> {stopPointId, stopAreaId,
stopAreaType}`, ~594KB for the full live dataset) is cached the same way `deviationsSnapshotService`
caches its own SL Deviations snapshot — a shared Redis-backed `Cache`/`DistributedLock` (see §7
below), a 24-hour freshness window (matching `/v1/sites`' own documented "changes at most once per
day"), a 7-day stale fallback on refresh failure, and both in-process (`InFlightDeduper`) and
cross-instance (the refresh lock) deduplication of concurrent cold refreshes. `/api/v1/journeys`
itself never touches this directory — only `/api/v1/journeys/disruptions` does, and only when a
recognized `disruptionContext` is present.

**Known limitation, unchanged from before**: SL Deviations' own `/v1/messages` feed (the source
`deviationsSnapshotService` caches) currently never populates `scope.stop_points` at all — confirmed
against a live 159-deviation snapshot; `scope.stop_areas` (in 89 of 159) and `scope.lines` (in all
159) are the only structured scope evidence SL actually sends there today. `scope.stop_points` is
still modeled and compared (`src/services/upstreamTypes.ts`'s own `RawDeviationSchema`, reusing the
same `stop_points` shape the embedded `siteDeviations`/`RawSiteDeviationSchema` already used) so
this backend benefits
automatically the moment SL starts populating it, with no further schema change required.

A deviation with `scope.lines` but no stop scope at all (line-only) reaches `LINE_RELEVANT` from
the rules above — `resolveDeviationRelevance`/`resolveJourneyDisruptions` (the SYNCHRONOUS core)
never parse SL's own free text and never touch GTFS; every existing caller of those two functions
keeps exactly that structured-evidence-only contract, unchanged. An OPTIONAL, ADDITIVE async layer
(`resolveDeviationRelevanceAsync`/`resolveJourneyDisruptionsAsync`, `resolveJourneyDisruptionsRoute`'s
own production caller) can upgrade SPECIFICALLY that line-only `LINE_RELEVANT` case further, when a
`SegmentEvidenceContext` is supplied — see "Segment-parsing relevance enhancement (optional, GTFS
Regional-backed)" below for the full contract, current status, and why this remains an addition on
top of the structured model above, never a replacement for it.

#### Segment-parsing relevance enhancement (optional, GTFS Regional-backed)

**The full relevance hierarchy, most authoritative first:**

1. **Structured SL stop scope** (`scope.stop_areas`/`scope.stop_points`, matching rules 1-5 above)
   — always outranks everything below; the segment parser is never even consulted for a deviation
   this alone already decides (`resolveDeviationRelevanceAsync` only proceeds past a `LINE_RELEVANT`
   result that has BOTH `scope.stop_areas` and `scope.stop_points` empty).
2. **A high-confidence parsed segment + fresh, `COMPLETE` line topology** (this section) — can
   upgrade that specific `LINE_RELEVANT` result to `CONFIRMED` or (much more conservatively — see
   below) to omitted (`UNRELATED`/`null`).
3. **`LINE_RELEVANT`** (the existing baseline) — the safe fallback whenever step 2 cannot produce a
   confident answer, for any reason (see "Required failure semantics" below). Never `500`, never
   delayed, never a reason to drop line-level evidence Blick genuinely has.

**Parser**: the ONE grammar `journeySegmentParser.ts` recognizes is `"mellan <A> och <B>"` ("between
A and B") — the only pattern a live audit of real SL Deviations text has actually demonstrated as
naming a station pair unambiguously. Both `message.header` and `message.details` are parsed
independently and their candidates unioned (never short-circuited on whichever field happens to
match first, and never concatenated together before parsing — each candidate stays clause-local).

**Topology**: `services/lineTopologyDirectory.ts` downloads GTFS Regional (a ZIP archive, real
in-memory extraction via `services/gtfsZipExtractor.ts`), parses `routes.txt`/`trips.txt`/
`stop_times.txt`, and builds ONE compact per-`(transportMode, lineDesignation)` topology graph per
refresh (never rescanning raw GTFS per lookup). Trafiklab's real extended `route_type` scheme (NOT
the basic GTFS 0-7 codes — confirmed live against Trafiklab's own documentation) is mapped
explicitly (100-109 → TRAIN, 400-405 → METRO, 700-716 → BUS, 900-906 → TRAM, 1000-1099/1200 →
FERRY). Every line's own topology carries an explicit `completeness: "COMPLETE" | "PARTIAL"` —
`"PARTIAL"` (any GTFS stop on that line whose own identity could not be resolved) is treated
identically to "no topology for this line at all": never authoritative for CONFIRMED/UNRELATED. A
raw GTFS trip is never bridged across an unresolved stop (an earlier version of this code had
exactly that fake-edge bug; fixed and regression-tested — see `lineTopologyDirectory.test.ts`'s own
"never create an edge across a missing GTFS stop" tests).

**GTFS stop-id identity bridge — genuinely unverified**: `services/lineTopologyDirectory.ts`'s own
`createGtfsStopIdResolver` resolves a GTFS `stop_id` to a StopArea id via
`StopPointDirectory.resolveStopPointGids` (`RawStopPoint.gid`, the SAME cached `/v1/stop-points`
snapshot `pattern_point_gid` resolution already uses — see above). The hypothesis that these two
values are identical rests on genuine but INDIRECT evidence (Trafiklab support documentation
describing both as sharing the same `9022`-prefixed "Stop point" class-id scheme, both sourced from
SL's own internal pubtrans/NOPTIS system) — NOT a live cross-check against a real downloaded feed,
since this backend does not have a `TRAFIKLAB_API_KEY` credential. `scripts/verifyGtfsStopIdentityBridge.ts`
exists to perform that live audit once a key is available; until it has been run and reviewed, this
bridge — and therefore the whole segment-parsing enhancement — must not be considered
production-verified, only production-*capable*.

**Production wiring reflects this honestly**: `app.ts` wires the real `LineTopologyDirectory` and
`JourneyEndpointSiteResolver` ONLY when `TRAFIKLAB_API_KEY` is configured; both stay `undefined`
otherwise (zero GTFS network traffic, zero segment-topology cache checks, zero requested-endpoint
resolver construction — `resolveJourneyDisruptionsAsync` behaves byte-for-byte like the synchronous
core). Setting the key is a NECESSARY but not SUFFICIENT condition for trusting this enhancement in
a real deployment — see `app.ts`'s own wiring comment for why that gap cannot be closed by code
alone.

**The enhancement only ever runs for `TRAVELLED_PATH`-policy effects.** Before any parsing or
topology work, `resolveDeviationRelevanceAsync` checks
`journeyDisruptionScope.scopePolicyForEffect(effect) === "TRAVELLED_PATH"`; an `ACCESS_POINTS`
effect (`ACCESSIBILITY_ISSUE`, `STATION_ACCESS`, `STOP_CHANGE`) returns the synchronous
`LINE_RELEVANT` base result unchanged. This mechanism proves ONLY "the affected segment lies on
the path the vehicle actually travelled" — irrelevant to a broken lift or a moved stop, which only
matter at a stop the passenger actually boards, alights, or transfers at. An `ACCESS_POINTS` effect
can still reach `CONFIRMED`, but only through the pre-existing structured `scope.stop_areas`/
`scope.stop_points` comparison (rule 5 of `resolveDeviationRelevance`), never through this
free-text path — see `disruptionRelevance.test.ts`'s and `segmentEvidenceEndToEnd.test.ts`'s own
"ACCESS_POINTS gate" regressions, including a Mariatorget-shaped one proving this doesn't regress
the original Mariatorget accessibility-classification fix.

**CONFIRMED / UNRELATED / LINE_RELEVANT — the exact upgrade rule**, decided per PARSED CANDIDATE
(never once per line — trust is inherently per-candidate, see requested-corridor evidence below),
then combined:

- **`CONFIRMED`**: ANY parsed candidate's own resolved edges overlap EITHER PRIMARY's own real
  travelled edges on the matched line (regardless of completeness — one structurally known real
  edge is sufficient even from an incomplete path) OR a requested corridor that is trusted
  specifically with respect to THAT candidate's own two endpoints. A genuine overlap found this way
  is sufficient on its own; another candidate on the same line being unresolved, ambiguous, or
  simply unproven can never withdraw it.
- **`UNRELATED`** (omitted): only when at least one candidate resolved AND EVERY resolved
  candidate's own outcome was a proven non-overlap against a corridor trusted for THAT candidate.
  **PRIMARY's own actual edges can NEVER by themselves prove a negative, however complete.**
  Journey Planner may have already rerouted PRIMARY around the very disruption being evaluated —
  confirmed live for Akalla → Kungsträdgården during the Metro 11 T-Centralen↔Kungsträdgården
  closure: PRIMARY reroutes onto Metro 11 only as far as T-Centralen (a fully resolved, complete
  stop sequence) + Metro 13 + a walk, so a "complete" account of PRIMARY's own CURRENT path simply
  never goes anywhere near the closed edge — precisely because the closure caused the reroute, not
  because the closure is irrelevant. Only an independently-reconstructed, trusted requested
  corridor can supply that negative proof.
- **`LINE_RELEVANT`**: every other case — the conservative default the whole enhancement degrades
  to whenever it cannot confidently do better (including: no requested corridor available at all,
  as in the reroute case above).

**Requested-corridor evidence** (`domain/requestedCorridor.ts`, `isRequestedCorridorTrusted`) lets
a disruption stay `CONFIRMED` — or, unlike before, also lets it genuinely reach `UNRELATED` — even
after Journey Planner has already rerouted PRIMARY off the affected line entirely. Both requested
endpoints (the routine's own `journeyOriginId`/`journeyDestinationId`, resolved via
`journeyEndpointSiteResolver.resolveSiteId` — an empirically-verified `stopId - 18000000 = site.id`
arithmetic bridge, re-checked live against an exact-GID-match alternative and confirmed still the
best available option — see that service's own doc) must still resolve to exactly one StopArea each
on the matched line's own fresh, `"COMPLETE"` topology, with a unique corridor between them.
`LineTopologyDirectory.resolveEndpointsCorridor` returns that corridor's own stop sequence ALWAYS
oriented origin-first, destination-last — regardless of which direction the underlying GTFS trip
pattern happens to be stored in — because trust itself now depends on genuine ORDERED sequence
equality, corrected (twice now) from an unordered edge-set comparison:

- An EARLIER version trusted the entire corridor as soon as it shared even one edge anywhere with
  PRIMARY's own travel — overclaiming (a bus sharing one early edge before transferring away has a
  corridor whose own remainder was never verified).
- The IMMEDIATELY PRIOR version tightened that to "the run's edges are a subset of the corridor's
  edges AND the run's own first-or-last stop matches an affected endpoint" — still not enough: an
  unordered subset check cannot distinguish a genuine reroute truncation from an ordinary INTERNAL
  FRAGMENT. Requested `A-B-C-D-E`, actual run `B-C`: the run's edges sit inside the corridor and `C`
  is a real affected-segment endpoint, so this rule wrongly trusted it — but `B-C` might simply be
  an ordinary mid-journey transfer, never proving the passenger was ever meant to continue past `C`.
- The CURRENT rule requires genuine ordered prefix/suffix equality instead (`isExactPrefix`/
  `isExactSuffix` in `requestedCorridor.ts` — position-by-position array comparison, never an edge
  set, never sorted ids, never numeric-id direction inference): for AT LEAST ONE of PRIMARY's own
  real, contiguous travelled runs on that line, either (a) the run is ordered-identical to the
  ENTIRE requested corridor (trusted unconditionally — a complete, gap-free, independently
  reconstructed account of PRIMARY's real path), or (b) the run is an EXACT prefix of the requested
  corridor AND its own last stop is exactly one of the affected candidate's own two endpoints (the
  Akalla → T-Centralen reroute shape), or (c) the run is an EXACT suffix of the requested corridor
  AND its own first stop is exactly one of the affected candidate's own two endpoints (the reverse-
  direction equivalent). An internal fragment — however cleanly its own edges sit inside the
  corridor — is never trusted merely because one of its own ends happens to coincide with an
  affected endpoint.

This resolution is LAZY and memoized per request (`routes/journeyDisruptions.ts`): every candidate
is first resolved against topology and checked for a DIRECT overlap with PRIMARY's own actual edges
before the requested-endpoints provider is ever invoked — a candidate that resolves nowhere, or
that already overlaps actual PRIMARY, or a line PRIMARY never touches at all (so requested-corridor
trust could never succeed regardless of what the corridor turns out to be) all reach their final
verdict without a single Journey Planner endpoint lookup. Only once corridor evidence could
plausibly change the outcome is it actually resolved, once per line, reused across every deviation
in the same request.

**Required failure semantics**: GTFS unavailable/stale/parse failure, a line with no or `"PARTIAL"`
topology, an unresolved/ambiguous station name, or a requested corridor that fails its own trust
check all degrade to `LINE_RELEVANT` — never a `500`, and never an incorrect `UNRELATED` from
incomplete evidence. Trafiklab's own GTFS Regional quota (as low as 50 calls/month on its lowest
tier) is protected by a daily refresh window, conditional `GET` (`ETag`/`If-None-Match`,
`Last-Modified`/`If-Modified-Since` — a `304` still counts as one real request, never assumed
free), and a shared, cross-instance, never-released "attempt claim" bounding even a sustained
total outage to roughly one wasted upstream attempt per ~24h, not one per worker tick.

**Deduplication**: SL Deviations are deduplicated by `deviation_case_id`; Journey Planner notices
by exact text (no stable id). Cross-source: when a Journey Planner notice's text exactly matches a
Deviation's own `message.header`, the merged entry keeps the DEVIATION's richer `id`/`details` —
never the text-only Journey Planner copy — with `relevance` upgraded to `CONFIRMED` if not already
(Journey Planner's own attachment to PRIMARY is itself confirming evidence).

## 1. Upstream architecture

The backend talks to two keyless, official SL/Trafiklab APIs:

- **SL Transport** (`https://transport.integration.sl.se/v1`) — sites, stop areas, stop
  points, lines, and real-time departures. Primary upstream for everything the app shows
  during a commute window.
- **SL Deviations** (`https://deviations.integration.sl.se/v1`) — the full disruption
  feed, with validity windows, multi-language messages, and a weblink. The backend fully
  implements and tests this contract (`/api/v1/disruptions`, §3 below), but the Android
  client does not currently call it or display disruptions anywhere — see
  `docs/Blick_Project_Documentation.md`'s "Current implementation status" for the
  authoritative account of what's actually built versus still planned.

Trafiklab Timetables and Trafiklab Stop Lookup, considered during initial architecture
review, were **dropped entirely**. They cover every Swedish operator (unnecessary scope
for an SL-only MVP), lack a stable numeric line ID, and use a different stop-ID
namespace than SL Deviations, which would have required an unverified ID conversion.

### Verified namespace result

During architecture review, live calls were made to confirm two things the docs alone
did not settle:

- `GET https://transport.integration.sl.se/v1/lines?transport_authority_id=1` returns
  line `id: 17` for designation "17" (Gröna linjen / METRO) and `id: 401` for designation
  "401" (bus).
- `GET https://deviations.integration.sl.se/v1/messages?site=9192&future=true` (site
  9192 = Slussen, SL Transport's own site ID) returned real, Slussen-relevant deviations
  whose `scope.lines` contained the exact same IDs (17, 18, 19, 13, 14, 401) and whose
  `scope.stop_areas` IDs (e.g. 1011, 11002) matched the `stop_area.id` values seen in a
  live `GET /v1/sites/9192/departures` call for the same site.

**Conclusion: `site`, `stop_area` and `line` IDs are the same namespace across SL
Transport and SL Deviations.** No conversion step exists anywhere in this codebase, and
none should ever be introduced — if a future change makes conversion necessary, that is
itself a sign something has gone wrong upstream.

One item could not be fully verified: the SL Transport OpenAPI spec was unreachable
during review (two plausible URLs returned nothing), so the complete `state` /
`consequence` enum is not confirmed from a spec, only from live examples. See §4,
Cancellation, for how this codebase handles that honestly.

## 2. Response envelope

Every successful response:

```json
{ "schemaVersion": 1, "data": { ... } }
```

Every error response:

```json
{ "schemaVersion": 1, "error": { "code": "VALIDATION_ERROR", "message": "..." } }
```

`code` is one of: `VALIDATION_ERROR` (400), `NOT_FOUND` (404),
`UPSTREAM_RATE_LIMITED` (503), `UPSTREAM_TIMEOUT` (504), `UPSTREAM_ERROR` (502),
`INTERNAL_ERROR` (500). (A separate, self-imposed `RATE_LIMITED` code was previously
reserved in this list but never actually produced by any code path — removed as dead code
during an audit.) See §8, "Upstream networking, runtime validation, and error handling",
for exactly what each error response does and does not contain.

## 3. Endpoints

### `GET /api/v1/health`

`data: { status: "ok", timestamp }`. `Cache-Control: no-store`.

### `GET /api/v1/stops/search?query=`

Searches a backend-cached snapshot of `GET /v1/sites?expand=true` (SL Transport has no
free-text search parameter on this endpoint, so the backend fetches the full site list
and searches it itself — see §6). `Cache-Control: public, s-maxage=3600,
stale-while-revalidate=86400` (site data changes rarely, per SL Transport's own docs).

| Normalized field | Upstream field (`RawSlSite`) | Notes |
|---|---|---|
| `sites[].siteId` | `id` | SL Transport Site ID — the one namespace used everywhere downstream |
| `.name` | `.name` | |
| `.note` | `.note` (nullable) | often a disambiguator, e.g. an island/locality name |
| `.lat` / `.lon` | `.lat` / `.lon` (nullable) | `null` when upstream omits or nulls them — confirmed some real sites have no coordinates at all, not just a fixture gap |
| `.stopAreaIds` | `.stop_areas` | child StopArea IDs — same namespace as Deviations' `scope.stop_areas.id` |

### `GET /api/v1/departures?siteId=&forecast=`

Proxies `GET /v1/sites/{siteId}/departures`.

`forecast` is optional: a positive integer, number of minutes to look ahead, forwarded
to SL Transport's own `forecast` query parameter unchanged. When omitted, nothing is
forwarded and SL Transport applies its own undocumented default (empirically ~60
minutes, matching this backend's pre-existing behavior). The maximum accepted value is
**1200** (20 hours) — empirically confirmed against the real upstream on 2026-08-01:
values above 1200 do not error, they silently return zero departures, so this backend
rejects anything above 1200 as a `VALIDATION_ERROR` rather than forwarding a value that
would silently produce an empty, misleading response. Android's routine-setup direction
discovery (`LiveDeparturesDirectionOptionsSource`) requests `forecast=1200` specifically
to surface lines/directions that aren't running right now but will be later today — the
live routine-details/notification polling paths do not pass this parameter at all and
keep relying on the short default window.

Response shape: `data: { fetchedAt, timeZone, siteId, departures, siteDeviations }`.

- `fetchedAt` is captured immediately after the upstream response body has been
  successfully received — never before the request was sent, and never regenerated for
  a cached response. Departures are not cached at this layer today (only the HTTP
  `Cache-Control` header below provides any caching), so every departures response is a
  fresh upstream call and a fresh `fetchedAt`; if a shared cache is introduced here in
  the future (see §7), it must follow the same rule disruptions already do (§3,
  disruptions, and §7): a cache hit returns the ORIGINAL fetch time, not a new one. This
  is also the value fed into the DST-disambiguation logic described in §5.
- `timeZone` is always the literal string `"Europe/Stockholm"`.
- `Cache-Control: public, s-maxage=30, stale-while-revalidate=30`.

| Normalized field | Upstream field (`RawDeparture`) | Notes |
|---|---|---|
| `departures[].departureId` | synthesized | `` `${journey.id}:${stopPoint.id}:${scheduledTime}` `` — see §4 |
| `.line.id` / `.designation` / `.transportMode` | `line.{id,designation,transport_mode}` | real numeric SL line ID, shared with Deviations |
| `.direction` / `.directionCode` | `direction` / `direction_code` (nullable) | |
| `.destination` / `.via` | `destination` / `via` (nullable) | |
| `.stopArea` | `stop_area.{id,name,type}` | `type` nullable |
| `.stopPoint` | `stop_point.{id,name,designation}` | `designation` nullable |
| `.scheduledTime` | `scheduled` | **required**; naive local → ISO 8601 + explicit offset, see §5 |
| `.expectedTime` | `expected` | **nullable** — SL Transport's OpenAPI spec could not be verified to guarantee this field is always present (see §1), so it is not assumed required. Clients must compute the effective time as `expectedTime ?? scheduledTime`. |
| `.state` | `state` | passed through as a plain string; unfamiliar/future values are not rejected (see §4) |
| `.isCancelled` | derived | see §4 |
| `.journey` | `journey.{id,state,prediction_state}` | kept as a full nested object, not just the ID, for transparency |
| `.tripDeviations` | `deviations[]` | `{importanceLevel, consequence, message}`, kept in its own distinct shape from `siteDeviations` |
| `siteDeviations` (top-level) | `stop_deviations[]` | site-wide, returned alongside departures in the same call; `{id, importanceLevel, message, affectedStopAreas, affectedStopPoints, affectedLines}`. Upstream's `href` fields are dropped — they were observed live to contain the literal broken value `"null/stop-areas/..."`, not a usable URL. |

**`minutesRemaining` is never returned by the backend, in any field, at any nesting
level.** Android computes and updates the visible countdown locally from
`expectedTime ?? scheduledTime`, refreshed on its own clock between backend polls; this
is enforced by a contract test that greps the entire serialized response for the string.

Embedded `siteDeviations`/`tripDeviations` here are a fast, free signal ("is anything
going on at this stop, right now") but are intentionally not treated as a substitute for
`/api/v1/disruptions` — see the comparison in §3.4.

### `GET /api/v1/disruptions?siteId=&lineId=&transportMode=&future=`

**Request validation and response shape are unchanged from the original per-query
design** (retained below) — what changed is where the disruption data actually comes
from. This route no longer forwards `siteId`/`lineId`/`transportMode`/`future` to SL
Deviations as upstream query parameters at all. Instead, exactly one shared,
network-wide `GET /v1/messages?future=true` snapshot (no `site`/`line`/`transport_mode`
filter) is fetched — coordinated across every Vercel instance via a distributed
lock/shared cache, see §7 below — and every request's own filters are applied **locally**
against that one snapshot (`src/services/deviationsFilter.ts`). This is what makes SL's
"at most one request per minute" fair-use guidance enforceable in aggregate, across every
distinct filter combination, not just repeat requests for the identical one (see §7 for
why the old per-query design could never actually guarantee that).

**`siteId` is required** (not optional): a routine always has a site, and requiring it
keeps every disruption request properly scoped to what it actually needs, even though the
underlying fetch itself is now always network-wide. Locally, `siteId` matches a deviation
whose `scope.stop_areas[].id` is either the site's own ID or one of its child stop-area
IDs (`Site.stopAreaIds`, from the same site directory `/api/v1/stops/search` uses) —
confirmed live during architecture review that a site's deviations are scoped by its
child stop areas' IDs (§1, "Verified namespace result").

A deviation with no `scope.stop_areas` at all (line-only or network-wide) has no station
to compare `siteId` against, so `siteId` is skipped for it — instead it is included only
when the request names one specific line: **both** `lineId` **and** `transportMode` are
given and both match one of the deviation's `scope.lines[]` entries. A bare `lineId` or a
bare `transportMode` alone is deliberately not enough — without a station to anchor it,
that single signal isn't enough to safely attribute a line-only deviation to one specific
routine, so it stays excluded. Deviations scoped to an unrelated station are unaffected by
this and remain excluded exactly as before: having any `scope.stop_areas` at all always
takes the `siteId`-matching path, never the line-only fallback
(`src/services/deviationsFilter.ts`, `matchesDeviationsQuery`).

`lineId` is an optional positive integer filter, matched locally against
`scope.lines[].id`. `transportMode` is an optional filter, strictly validated against the
closed set of modes SL actually documents as filterable (`BUS`, `METRO`, `TRAIN`, `TRAM`,
`SHIP`, `FERRY`, `TAXI`) — an unsupported, empty, or malformed value is a
`VALIDATION_ERROR` (400), not silently ignored or forwarded as-is — then matched locally
against `scope.lines[].transport_mode`. For a station-scoped deviation (one with
`scope.stop_areas`), these two apply as independent optional filters exactly as described
above; for a line-only deviation, see the `siteId` paragraph above — both are required
together there. `future` may only be absent, `"true"`, or
`"false"`; defaulting to `false` when absent — a normal active-routine request only sees
currently published disruptions, and a caller must opt in explicitly (`future=true`) to
also see disruptions published for the future. Any other value for `future` (e.g.
`"banana"`, `"True"`, empty) is a `VALIDATION_ERROR` (400) rather than being silently
coerced to `false`. Locally, a deviation is always excluded once `publish.upto` is in the
past regardless of `future`; `future=false` additionally excludes one whose `publish.from`
is still in the future (not yet started), `future=true` includes it. **This request-filter
validation is intentionally a different, stricter check than how the response's own
`transportMode` fields are validated** — see "Request validation vs. response
compatibility" below.

`Cache-Control: public, s-maxage=<remaining source freshness>, must-revalidate` (see §7,
fair use). The value is at most 60 seconds and is calculated from `fetchedAt`; a stale or
malformed source timestamp receives `s-maxage=0`. There is no independent edge stale
window because the shared snapshot service already owns stale fallback.

Response shape: `data: { fetchedAt, disruptions }`. `fetchedAt` is the shared snapshot's
own fetch time — the *original* upstream-fetch time, never a freshly generated one,
whenever the snapshot is served from cache (fresh or stale) rather than freshly fetched.
Concurrent requests, from any Vercel instance and with any filter combination, that all
land while the same snapshot is still fresh (or while a refresh is already in flight)
share exactly one upstream call and therefore one `fetchedAt` (see §7 and
`tests/fetchedAt.test.ts`, `tests/deviationsSnapshotService.test.ts`).

| Normalized field | Upstream field (`RawDeviation`) | Notes |
|---|---|---|
| `disruptionId` | `deviation_case_id` (stringified) | |
| `version` | `version` | |
| `createdAt` / `modifiedAt` | `created` / `modified` | already ISO 8601 with an explicit offset upstream — passed through unchanged |
| `validFrom` / `validUntil` | `publish.from` / `publish.upto` | |
| `priority` | `priority.{importance,influence,urgency}_level` | upstream documents these as sort hints only; no invented `severity` field exists |
| `effect` | *(derived)* | Blick's own deterministic classification of `message`, e.g. `"DELAYS"` — see "Disruption effect classification" below. Never an upstream field. |
| `message` | selected from `message_variants[]` | see §3.3 for selection logic; shape is `{header, details, scopeAlias, webLink, language}`, never the raw ambiguous upstream object |
| `affectedStopAreas[].id/name/type` | `scope.stop_areas[]` | same namespace as SL Transport `stop_area.id` |
| `affectedLines[].id/designation/transportMode/name` | `scope.lines[]` | same namespace as SL Transport `line.id` |
| `affectedModes` | *(derived)* | unique transport modes across `affectedLines`, computed and documented as such, not upstream-native |

#### 3.3 Message-variant selection

`selectMessageVariant` picks the entry where `language === "sv"`. It falls back to the
first available variant **only** when no Swedish variant exists — it never assumes
`message_variants[0]` is Swedish, because SL Deviations can and does return other
languages. Covered by `tests/disruption.test.ts`, including a case where the English
variant is listed before the Swedish one.

#### 3.4 Embedded deviations vs. the dedicated Deviations endpoint

SL Transport's departures response already embeds two lighter-weight disruption
signals: `stop_deviations[]` (site-wide) and each departure's own `deviations[]`
(trip-specific). Both are single flat `message` strings with an `importance_level`, no
publish window, no multi-language variants, and no weblink. The dedicated SL Deviations
endpoint adds real value beyond that: a stable `deviation_case_id` for
dedup/updates, `created`/`modified` timestamps, `publish.from/upto` (needed to correctly
hide expired disruptions), full `header`/`details`/`weblink`/`language`, and a three-part
priority breakdown. The MVP design calls both: the embedded fields are intended to be
surfaced as-is for a free, no-extra-request signal, with a full disruptions section of
the UI backed by `/api/v1/disruptions`.

**The dedicated `/api/v1/disruptions` endpoint is now integrated into the Android
client** — a Routine Details section (loading/no-disruptions/unavailable/list, ordered by
priority) and the highest-priority disruption's header/details in the ongoing
notification's expanded view, both backed by a shared, TTL-capped client-side cache (see
`docs/Blick_Project_Documentation.md`'s "Current implementation status" for the
authoritative account). **The lighter-weight embedded `siteDeviations`/`tripDeviations`
fields are still not read by the Android app** — they remain normalized and present in
the `/api/v1/departures` response only, with no current consumer. A third, separate embedded
signal — Journey Planner's own per-leg `infos`, normalized into each journey's
`disruptionNotices` — IS read by the Android app, but only for `EXACT_DESTINATION` routines; see
"Journey disruption notices (`disruptionNotices`)" above for why that case is structurally
different (no `siteId`/`lineId` to query `/api/v1/disruptions` with) and deliberately does not
reuse this endpoint. `EXACT_DESTINATION` routines now also resolve `disruptionNotices` together
with a second, separate lookup against the SAME underlying SL Deviations cache this endpoint reads
— see "Resolving Journey Planner notices + matched SL Deviations (`/api/v1/journeys/disruptions`)"
above — but that lookup is still a genuinely different route from this one, not a call to
`/api/v1/disruptions` itself.

#### 3.5 Request validation vs. response compatibility

Two deliberately different rules govern `transportMode`, depending on which side of the
API it appears on:

- **As a request filter** (`GET /api/v1/disruptions?transportMode=`), it is validated
  against a closed, strict list of the modes SL actually documents as filterable — `BUS`,
  `METRO`, `TRAIN`, `TRAM`, `SHIP`, `FERRY`, `TAXI` (`RequestTransportModeSchema` in
  `src/models/common.ts`). A client sending anything else, including an empty string or a
  wrong-case value, gets `VALIDATION_ERROR` (400): a filter value the backend doesn't
  understand is a bug in the caller, not something to guess about.
- **As a normalized response field** (`departures[].line.transportMode`,
  `siteDeviations[].affectedLines[].transportMode`, `disruptions[].affectedLines[].transportMode`,
  `disruptions[].affectedModes[]`), it is a plain non-empty string
  (`TransportModeSchema` in `src/models/common.ts`), not a closed enum. An unfamiliar
  mode SL adds in the future is passed through unchanged rather than rejected — a closed
  enum here would silently contradict this codebase's forward-compatibility guarantee
  the moment SL introduces a new mode. `asTransportMode` (`src/normalize/transportMode.ts`)
  guarantees this field is never an empty string by mapping a missing/empty upstream
  value to the literal `"UNKNOWN"` — that literal is reserved specifically for "upstream
  gave us nothing usable here", and is never substituted for a mode value SL did provide,
  however unfamiliar.

Request-side validation and response-side compatibility are intentionally not the same
schema, and must not be unified into one: doing so would either reject legitimate future
upstream data (if the strict enum were reused for responses) or accept nonsense request
filters (if the permissive schema were reused for requests).

#### Disruption effect classification

`disruptions[].effect` is Blick's own closed, deterministic classification of `message` into
one of nine passenger-facing effects — never an SL-provided field, and never an ML/AI
classification, cause classification, or confidence score. It exists specifically so the
Android ongoing-commute notification can show a short, useful summary (e.g. "⚠️ Delays · Tap
for details") instead of a generic "a disruption exists" indicator, without ever placing SL's
own header/details text into that notification (see the Android client's own notes on this).

```
DELAYS | NO_SERVICE | REDUCED_SERVICE | ROUTE_CHANGE | STOP_CHANGE
REPLACEMENT_SERVICE | STATION_ACCESS | ACCESSIBILITY_ISSUE | DISRUPTION
```

`DISRUPTION` is the conservative fallback — used whenever nothing more specific is confidently
recognized, and always for a non-Swedish selected `message` (§3.3): a generic label is
preferable to a confidently wrong one, and this classifier only implements hand-tuned Swedish
rules for v1 (`src/normalize/classifyDisruptionEffect.ts`).

Classification is a pure, synchronous, local rule match against the already-selected
`message` — no network call, no database, no AI, negligible cost next to the SL request itself
— and follows two fixed rules, both deliberately encoded as data (a single precedence-ordered
rule list), not ad hoc regex ordering:

1. **Header first, details only as a fallback.** SL's `header` normally states the passenger
   effect directly, while `details` often only adds a cause or secondary information. Example
   already committed in `fixtures/slDeviationsSlussen.sample.json`: header `"L401 försenat
   avgång med 5 minuter"` classifies as `DELAYS` on the header alone; its `details` separately
   mentions a bridge opening (the *cause*), which must never steal the classification away from
   the header's own wording. Only when the header matches nothing specific are `details`
   classified the same way — header and details are never concatenated into one search.
2. **Fixed precedence when a text could match more than one effect:** `NO_SERVICE` →
   `REPLACEMENT_SERVICE` → `REDUCED_SERVICE` → `ROUTE_CHANGE` → `STOP_CHANGE` →
   `ACCESSIBILITY_ISSUE` → `STATION_ACCESS` → `DELAYS`. Example: `"Ingen trafik mellan X och Y.
   Ersättningsbussar kör."` classifies as `NO_SERVICE`, not `REPLACEMENT_SERVICE` — the primary
   passenger impact is that normal service isn't running at all.

Individual rules are intentionally conservative and context-sensitive rather than broad
substring checks — notably, bare `"inställd"` (cancelled) is never on its own enough for
`NO_SERVICE` (`"En avgång är inställd"`, one cancelled departure, must not become "no
service"), and accessibility wording requires an actual stated problem alongside `"hiss"`/
`"rulltrappa"`, not just the word's existence. See `classifyDisruptionEffect.ts`'s own rule
table and doc comments for the exact wording each rule matches, and
`tests/classifyDisruptionEffect.test.ts` for the full behavioral contract (all nine outcomes,
precedence, header-vs-details, casing/whitespace/newline normalization, and both real fixture
disruptions).

### Removed: `/api/v1/lines`

Considered and explicitly **not** included in this scaffold. It has no immediate
consumer: direction/destination pairing is sourced from live departures (`direction`,
`directionCode`, `destination` on each `Departure`), not from the static line list, and
no screen in the MVP needs a standalone line browser. Per the instruction to not ship an
unused thin proxy, it is left out entirely rather than half-defined. If a future screen
needs it, it should be added with its own complete schema, validation, and tests at that
time — not resurrected as-is.

## 4. Cancellation

`isCancelled: boolean` is derived with a narrow, explicit rule and nothing else:

```
isCancelled = (departure.state === "CANCELLED")
           || tripDeviations.some(d => d.consequence === "CANCELLED")
```

The original `state`, `journey.state`, `journey.predictionState`, and every trip
deviation's `consequence` are preserved in the normalized response untouched, specifically
so that if this narrow rule turns out to be incomplete once the OpenAPI spec is finally
confirmed, the raw signal needed to correct it is already there — nothing was discarded
on the way in. Both `state` fields are typed as plain strings, not closed enums: an
unfamiliar future value (e.g. a state SL adds later) is passed through as-is and does
not fail deserialization or get miscategorized as a cancellation. Tested in
`tests/cancellation.test.ts`: ordinary departure, `state === "CANCELLED"`, a trip
deviation with `consequence === "CANCELLED"` while `state` itself says something else,
and an entirely unknown state string.

### Departure identity

```
departureId = `${journey.id}:${stopPoint.id}:${scheduledTime}`
```

`journey.id` alone appears to already be date-scoped in practice (e.g.
`2026070408514`), but the composite key defends against the theoretical case of one
journey visiting the same stop point twice (a loop route), and remains stable across
refreshes of the same departure since `scheduledTime` doesn't change between polls.

## 5. Stockholm timestamp handling

SL Transport's `scheduled`/`expected` fields are naive local strings
(`"2026-07-04T17:33:00"`, no offset, always Europe/Stockholm wall-clock). The backend
converts these to ISO 8601 with an explicit UTC offset via `src/lib/stockholmTime.ts`,
which does **not** rely on a date library's undocumented default DST disambiguation —
the resolution policy is implemented and tested explicitly.

**Calendar validation happens first, before any timezone reasoning.** A naive string is
parsed strictly and rejected — throwing `InvalidStockholmTimestampError` — if it is
malformed, names an out-of-range field (month 13, hour 24, minute 60, ...), or names a
day-of-month that does not exist for that month/year (30 February, or 29 February in a
non-leap year; `2026`, `2100`, and similar century years divisible by 100 but not 400 are
correctly treated as non-leap). This matters because native `Date.UTC`-style overflow
would otherwise silently "normalize" 30 February into 2 March without complaint — this
codebase never lets that happen. A departure whose `scheduled`/`expected` field fails
this check becomes a controlled `AppError("UPSTREAM_ERROR", ...)` (502) at the
`/api/v1/departures` route, not a generic crash (see §8, "Upstream networking, runtime validation, and error handling").

Once a naive string is confirmed calendar-valid:

- **Normal times** resolve to a single, unambiguous offset via an iterative guess (a
  single "treat naive as UTC, look up the offset there" pass is not reliable close to a
  transition, since the offset that applies at the guess instant can differ from the one
  that applies at the real target instant — the implementation iterates to a fixed
  point).
- **Spring-forward gap** (2026: the hour 02:00:00–02:59:59 on March 29 does not exist —
  clocks jump from 02:00 CET straight to 03:00 CEST): a naive local time in that gap is
  **rejected** (`InvalidStockholmTimestampError`), not resolved with a guessed offset.
  Earlier revisions of this backend manufactured an instant here using the
  post-transition (CEST) offset; that was changed deliberately — inventing a
  plausible-looking but fictitious instant for a wall-clock time that never happened is
  less honest than a controlled upstream-data error, and SL should never actually
  schedule a departure inside an hour that doesn't exist. This, too, surfaces as
  `AppError("UPSTREAM_ERROR", ...)` (502) at the route level (see §8).
- **Autumn duplicated hour** (2026: local 02:00:00–02:59:59 on October 25 occurs twice,
  once as CEST then again as CET) is still **resolved**, not rejected — both candidate
  instants are real. It uses the surrounding response's `fetchedAt` — since departures
  are always near-term relative to when they were fetched, the implementation picks
  whichever of the two candidate instants is not earlier than `fetchedAt` minus a
  five-minute grace buffer, preferring the earlier of the two if both qualify. Flagged
  with `anomaly: "ambiguous"`.

Every emitted ISO 8601 string's wall-clock portion, offset, and represented Instant are
guaranteed to agree, since all three are derived from the same resolved `Date` value —
never assembled from independently-computed pieces that could drift apart.

Tested in `tests/stockholmTime.test.ts` (23 tests) against real `Intl`/ICU timezone data
(not hand-derived expectations) for: a normal CET timestamp, a normal CEST timestamp,
times immediately before/after the 2026-03-29 spring transition, times immediately
before/after the 2026-10-25 autumn transition, the duplicated local hour resolved both
ways depending on `fetchedAt`, the nonexistent spring-gap local time now being rejected,
calendar validation (30 February, 29 February in both leap and non-leap years including
the divisible-by-100-not-400 case, out-of-range month/day/hour/minute/second, malformed
strings), and ISO round-trip consistency (the offset embedded in the output string
matches the real Stockholm offset at the represented instant).

## 6. Stop search behaviour

`GET /api/v1/stops/search?query=`:

- Input is trimmed; queries must be 1–64 characters after trimming, otherwise
  `VALIDATION_ERROR`.
- Matching is case-insensitive and diacritic-tolerant (`Fruängen` matches `fruangen`;
  implemented via Unicode NFD normalization + combining-mark stripping, not a hardcoded
  å/ä/ö table).
- Matches against `name` first, falling back to the nullable `note` field only when
  `name` doesn't match at all; a `note` match is ranked one tier behind an equivalent
  `name` match.
- Ranking tiers, best to worst: exact match, prefix match, token-prefix match (a word
  inside the name starts with the query), substring match.
- Ties are broken deterministically: by folded name alphabetically, then by `siteId`.
- Results are capped at 20.

Tested in `tests/search.test.ts`, including Swedish-character folding and deterministic
tie-breaking.

Because SL Transport's `/v1/sites` has no free-text search parameter, the backend
fetches the **full** site list once and searches it in memory (`siteDirectory.ts`). This
snapshot is cached for 24 hours (site data "changes at most once per day" per SL
Transport's own documentation) — but see §7: **this is a best-effort, per-instance
cache, not a guaranteed daily cache**, since a Vercel serverless instance can be
recycled at any time. Concurrent cold requests within one instance are deduplicated
(`InFlightDeduper`) so N simultaneous searches before the snapshot has loaded trigger
exactly one upstream fetch, not N — tested in `tests/cache.test.ts`.

## 7. Caching and fair use

| Route | `Cache-Control` | Why |
|---|---|---|
| `/api/v1/health` | `no-store` | never cache a liveness check |
| `/api/v1/stops/search` | `public, s-maxage=3600, stale-while-revalidate=86400` | site data changes rarely |
| `/api/v1/departures` | `public, s-maxage=30, stale-while-revalidate=30` | near-real-time data |
| `/api/v1/disruptions` | `public, s-maxage=<0..60>, must-revalidate` | remaining lifetime of the shared snapshot's source freshness; no compounding edge stale window |

The shared, cross-instance protection described below is scoped specifically to SL
Deviations — SL's own fair-use guidance is a per-minute limit unique to that upstream.
`/api/v1/stops/search`'s site-directory snapshot (§6 above) and `/api/v1/departures`
remain exactly as before: best-effort, per-instance `InMemoryCache`/`InFlightDeduper`,
not Redis-backed. Site data changes "at most once per day" per SL Transport's own docs
and departures are already near-real-time (30s `Cache-Control`), so neither is subject to
a per-minute fair-use constraint the way SL Deviations is.

SL Deviations' own guidance asks for **at most one request per minute**. Two distinct,
compounding gaps used to make that unenforceable in production — both are now closed:

1. **Per-instance, not global**, used to be true of the in-memory `Cache`/
   `InFlightDeduper` implementations: Vercel does not guarantee shared memory across
   invocations, so multiple cold instances could each independently decide it's time to
   call SL Deviations, each believing it is the only caller.
2. **Per query combination, not per upstream overall**, used to be true because the old
   60s cache/dedup key was `siteId:lineId:transportMode:future` — so the ≥60s TTL only
   limited repeat requests for the *same* filter combination to once a minute. Real
   traffic spanning many distinct site/line/transport-mode/future combinations within the
   same minute (expected: different users track different stops) could still exceed one
   request per minute in aggregate, even with that cache working exactly as designed.

**Both are fixed by fetching one shared, network-wide SL Deviations snapshot — never one
upstream call per query — and coordinating every Vercel instance's access to it through a
Redis-backed distributed lock and cache**, rather than trying to rate-limit each distinct
query combination separately:

- `src/services/slDeviationsClient.ts`'s `fetchAllDeviations()` calls
  `GET /v1/messages?future=true` with **no** `site`/`line`/`transport_mode` filter — the
  entire network's currently- and future-published deviations, in one response. Every
  request's own `siteId`/`lineId`/`transportMode`/`future` filters are applied **locally**
  against that one snapshot instead (`src/services/deviationsFilter.ts`, §3.2 above).
- `src/services/deviationsSnapshotService.ts` is the one thing across the whole backend
  allowed to call `fetchAllDeviations()`. It keeps the snapshot in a shared `Cache`
  (`RedisCache`, backed by Upstash Redis's REST API — `@upstash/redis` — in production;
  see "Redis setup" below) and treats it as fresh for 60 seconds from its own `fetchedAt`.
  Once no longer fresh, refreshing is coordinated by a `DistributedLock`
  (`src/lib/distributedLock.ts`, `RedisLock` in production): only the one instance that
  wins the lock actually calls `fetchAllDeviations()`; every other concurrent caller,
  across every instance and regardless of its own filters, either waits briefly for that
  winner's result or falls back to the last known-good snapshot — **never** makes an
  upstream call of its own.
- **The 60-second floor covers failed attempts too**, exactly as SL's guidance implies:
  before calling upstream, the winning instance claims a *separate* 60-second key
  (deliberately never released early — see the service's own doc for why) that blocks
  every instance, including itself, from attempting again for the rest of that window,
  whether the attempt that follows succeeds or fails.
- **A failed refresh serves the last successful snapshot instead of erroring**, with that
  snapshot's *original* `fetchedAt` untouched — kept in the shared cache for 6 hours (far
  longer than the 60s freshness window), a deliberately generous stale-fallback period so
  a prolonged SL Deviations outage degrades to "possibly-outdated disruption data" rather
  than a hard failure on every request. Only when there has never been a successful
  snapshot at all does the real upstream error (`UPSTREAM_ERROR`/`UPSTREAM_TIMEOUT`/
  `UPSTREAM_RATE_LIMITED`, unchanged and unwrapped) reach the client.

Tested in `tests/deviationsSnapshotService.test.ts` (concurrent requests from separate
simulated instances sharing one cache/lock produce exactly one upstream call; the 60s
limit, including a failed attempt's own cooldown; stale fallback with the original
`fetchedAt` preserved; the controlled error when no snapshot has ever succeeded; refresh-
lock expiry and recovery after a simulated stuck holder), `tests/deviationsFilter.test.ts`
(local `siteId`/`lineId`/`transportMode`/validity-period filtering, pure and
Android/Redis-independent), `tests/distributedLock.test.ts` (`DistributedLock`'s own
contract: safe expiry, and ownership-protected release — a late release from an
already-expired holder must never delete a different, legitimate new holder's lock), and
`tests/fetchedAt.test.ts` (concurrent requests with *different* site/line filters still
share one upstream call and one `fetchedAt`, alongside the pre-existing identical-request
case).

### `StopPointDirectory` (SL Transport `/v1/stop-points`)

A second, independently-keyed shared Redis snapshot, structurally similar to the SL Deviations one
above but for a genuinely different upstream and update cadence — see "Stop identity: Journey
Planner platform ids ↔ SL Transport `pattern_point_gid`" above for what it resolves and the full
live evidence. No documented strict per-minute fair-use ceiling applies to this endpoint (that
constraint is SL Deviations' own), so this service has no separate rate-limit key the way
`deviationsSnapshotService` does — only a 24-hour freshness window, a 7-day stale fallback, and the
same refresh-lock coordination pattern (`src/services/stopPointDirectory.ts`). Sharing the SAME
underlying Redis connection (`sharedRedisCache`/`sharedRedisLock` in `src/app.ts`) as Deviations
costs nothing extra in infrastructure — the two snapshots use distinct cache keys
(`sl-transport:stop-point-index:v1` vs `sl-deviations:snapshot:v1`) and never interact.

Tested in `tests/stopPointDirectory.test.ts`: resolution outcomes (RESOLVED/AMBIGUOUS/UNRESOLVED),
identity resolution using ONLY `pattern_point_gid` (no name/coordinate/substring fallback, proven
against the real Fridhemsplan/T-Centralen counterexample), caching, stale fallback, concurrency
across simulated separate instances, and best-effort lock release — mirroring
`tests/deviationsSnapshotService.test.ts`'s own coverage shape.

### Redis setup (Upstash)

`src/config/env.ts`'s `readRedisConfig` validates `UPSTASH_REDIS_REST_URL` and
`UPSTASH_REDIS_REST_TOKEN` — the exact variable names Vercel's own Upstash marketplace
integration populates automatically once a Redis database is connected to the project.
**In production (`NODE_ENV=production`), both are required — the backend refuses to
start without them**, rather than silently falling back to the in-memory
`InMemoryCache`/`InMemoryLock` implementations, which provide no cross-instance
protection at all (see `src/lib/cache.ts`, `src/lib/distributedLock.ts`, and each one's
own "best-effort, per-process" doc). Outside production — local development and the
automated test suite — both are optional and simply unset by default, which selects the
in-memory implementations instead; this is correct and expected there, never a
misconfiguration. See `backend/README.md`, "Redis (Upstash) setup", for the concrete
setup steps, and `.env.example` for the full variable documentation.

## 8. Upstream networking, runtime validation, and error handling

### Upstream networking

Every call to SL Transport or SL Deviations goes through one shared helper
(`fetchUpstreamJson` in `src/lib/upstreamFetch.ts`), which enforces a configurable
request timeout and maps failures to the documented error codes rather than leaving
each call site to reinvent this:

| Condition | Error code | HTTP status |
|---|---|---|
| Request does not complete within the timeout (headers OR body) | `UPSTREAM_TIMEOUT` | 504 |
| Any other network-level failure (DNS, connection reset, TLS, ...) | `UPSTREAM_ERROR` | 502 |
| Upstream responds `HTTP 429` | `UPSTREAM_RATE_LIMITED` | 503 |
| Any other non-2xx upstream HTTP status | `UPSTREAM_ERROR` | 502 |
| Upstream response body is not valid JSON (and did not time out) | `UPSTREAM_ERROR` | 502 |
| Upstream response body is valid JSON but fails schema validation (see below) | `UPSTREAM_ERROR` | 502 |

The timeout is configurable via `UPSTREAM_TIMEOUT_MS` (default `10000`, i.e. 10s; see
`.env.example`), implemented with `AbortController`/`AbortSignal` — not a fixed,
hardcoded value. **The timeout budget covers the whole operation, not just waiting for
response headers:** the same `AbortController` stays live through `response.json()`, so
a response whose headers arrive promptly but whose body then stalls still aborts and
reports `UPSTREAM_TIMEOUT`, rather than hanging past the configured timeout. A timeout is
distinguished from an ordinary network failure or ordinary malformed JSON by checking
whether the abort controller's own signal fired, so a genuine DNS failure, connection
reset, or a body that simply isn't valid JSON is never misreported as a timeout.

When upstream returns `429`, its `Retry-After` header is forwarded on the backend's own
`503` response (`AppError.retryAfter`, applied in `middleware/errorHandler.ts`) **only
when the header's value is valid** — a non-negative `delay-seconds` integer (e.g. `"30"`)
or a valid HTTP-date (IMF-fixdate, e.g. `"Wed, 21 Oct 2026 07:28:00 GMT"`), per RFC 9110
§10.2.3 (`src/lib/retryAfter.ts`). An invalid value (negative, decimal, garbage text, or a
non-HTTP-date string such as an ISO 8601 timestamp) is omitted rather than forwarded —
this backend never passes through an unvalidated upstream header value verbatim.

### Runtime validation of upstream data

Every upstream JSON payload is validated against a Zod schema (`src/services/upstreamTypes.ts`)
before being cast to a TypeScript type or handed to the normalization layer — there are
no unchecked `as T` casts of `response.json()` anywhere in this codebase. Every object
schema uses `.passthrough()`: fields this backend doesn't recognize are preserved, not
stripped or rejected, so an upstream addition never fails validation. A payload that is
missing a required field, has a wrong-typed field, or is not valid JSON at all becomes
the same controlled `UPSTREAM_ERROR` (502) described above — never an unchecked crash
deeper in the normalization pipeline. Tested in `tests/upstreamSchemas.test.ts`: valid
fixtures, a missing required field, a wrong-typed field, extra/unrecognized fields
(accepted), and malformed JSON.

### Error handling: what a client does and does not see

- A known `AppError`'s `message` is public-safe by construction — every `AppError` call
  site in this codebase is written to be shown to a caller — and is returned as-is.
- Any other (unexpected, unanticipated) error returns only the fixed generic message
  `"Unexpected internal error"` with code `INTERNAL_ERROR` (500). The real error —
  including any `AppError.cause_` (e.g. the original network exception or Zod issue) —
  is logged server-side via `console.error` for operators, but is never serialized into
  the response: no upstream URLs, raw exception messages, stack traces, or upstream
  response bodies reach the client this way. `AppError` messages themselves also never
  contain an upstream URL (see `src/lib/upstreamFetch.ts` — messages name the upstream,
  e.g. `"SL Transport"`, never the request URL).
- Every error response — known or unexpected, including `404`s — sets
  `Cache-Control: no-store`: an error must never be served stale from an edge cache.

Tested in `tests/networking.test.ts` (timeout/network-failure/429/malformed-response
mapping) and `tests/errorHandling.test.ts` (leakage prevention and `no-store` headers).

## 9. Licensing and attribution

Both current upstreams are keyless, but keyless does not mean obligation-free. Before
any public release, the applicable Trafiklab terms must be registered for/accepted
(Trafiklab's site-wide terms cover the SL APIs distributed through it). The Android
About screen (`ui/screens/about/AboutScreen`, reached via an info icon in the routine
list's top app bar) now carries visible attribution:

> Based on information from Trafiklab.se

...with a link to Trafiklab.se, and an explicit non-affiliation disclaimer. This
requirement is tracked here rather than only in code comments specifically so it isn't
lost before the first public release.

## 10. Platform-neutral design and future roadmap

This backend, its normalized models, its validation, and its contract tests are
deliberately independent of Android. The first client is native Android, but nothing in
`backend/` imports or assumes an Android-specific type, and the contract tests exercise
the HTTP layer directly (`app.request(...)`), not through any Android code path. Planned,
**not implemented** in this scaffold:

- A native iPhone client consuming this same backend contract. No Swift, SwiftUI,
  Flutter, React Native, or Kotlin Multiplatform code has been introduced now — the
  contract is the only thing shared today.
- Future investigation of ActivityKit / Live Activities, APNs, and iOS
  background-execution constraints, once an iPhone client is actually scoped.
- A possible Smart TV departure-board experience, preferably starting as a secure web or
  casting display rather than a native TV app, reusing this same backend.
- Cross-device routine synchronization or pairing — only if a real product need for it
  emerges later. No accounts, cloud sync, or pairing exist in this scaffold.

## 11. Known open limitation: direction discovery

`/api/v1/departures` only reflects lines and directions currently operating within SL
Transport's live forecast window. Routine setup now requests that window at its
empirically-confirmed maximum, `forecast=1200` (20 hours — see the departures endpoint
entry in §3 above), instead of the
short live-display default, so a route need only run at least once within roughly the
next 20 hours to appear as a selectable direction, not at the exact moment of setup. A
route running less often than that (e.g. a once-a-week special service) may still not
appear. This is a real, documented product limitation, not silently papered over — the
MVP does not add a Journey Planner or GTFS static-schedule dependency to solve it fully;
`/v1/lines` and `/v1/stop-points` were checked directly against the real upstream and
confirmed not to provide a static site/direction association that could close the gap
without one. (`/v1/stop-points` was later put to a different, unrelated use — see
`StopPointDirectory` above — but that is a platform-to-stop-area identity bridge for
exact-destination disruption relevance, not a site/direction association; this limitation
is unaffected.) Direction-option discovery is kept behind a `DirectionOptionsSource`-shaped
interface in the Android app (see `android/README.md`) so its data source can be
replaced later (e.g. with a static schedule fallback) without touching Room or the UI. A
saved routine's identity is platform-neutral (`siteId`, `lineId`, `transportMode`,
`directionCode`); destination text may be stored for display only and is never the sole
identity.

## 12. Deployment (Vercel)

Targets Vercel via `backend/api/index.ts` (Hono's documented default-export pattern) and
`backend/vercel.json` (a rewrite mapping every `/api/*` path to that one function, since
this backend's internal routing is handled by Hono, not by Vercel's filesystem router).
Full, exact deployment instructions (Root Directory setting, Node version pin, preview
vs. production commands, and the sandbox network limitation encountered while verifying
this) live in `backend/README.md`, "Deploying to Vercel", so they stay next to the
scripts and files they describe rather than duplicated here. In summary: no secrets are
required, the upstream base URLs remain overridable via environment variables, the
Node.js runtime major version is pinned to `22.x` via `package.json`'s `engines` field,
and the Vercel project's **Root Directory must be set to `backend`** when importing this
repository, since the repository root also contains the unrelated `android/` and `docs/`
directories.
