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
and acknowledges an unacknowledged valid purchase through the publisher API. Credentials never
enter the Android app or repository. Responses use the existing sanitized error envelope and
`Cache-Control: no-store`.

### `GET /api/v1/journeys/locations/search?query=`

Resolves a user-entered stop/location through Journey Planner Stop Finder and returns supported
global identifiers plus display names. Android must persist these identifiers; SL Transport's
numeric site IDs are not assumed to be compatible.

### `GET /api/v1/journeys?originId=&destinationId=&transportModes=METRO,TRAIN,BUS&searchUntil=&changesPreference=`

Calls Journey Planner Trips, normalizes complete legs (first public transport mode/line,
departure and final arrival, transfer count, realtime flags, stop names, disruptions, and each
leg's own canonicalized stop-area sequence — see `backend/src/domain/routePattern.ts`), and maps
the requested allow-list to SL's `incl_mot_*` parameters. Walking transfer legs are always
permitted; a journey using any unselected public mode is rejected defensively. `transportModes`
defaults to all regular modes for backward compatibility and rejects empty or unsupported
selections. `searchUntil` (an ISO-8601 instant) bounds how far forward the backend's own targeted
acquisition may search for NEXT/ALTERNATIVE; a malformed value is a validation error, but an
absent one is not — it means "answer from the initial acquisition alone", never an invented search
horizon. `changesPreference` (`DIRECT_ONLY` | `BOTH` | `WITH_CHANGES_ONLY`, defaulting to `BOTH`)
narrows the whole eligible candidate pool BEFORE PRIMARY/NEXT/ALTERNATIVE selection — see
`backend/src/services/candidateCollector.ts`'s own `JourneyChangesPreference` doc — so a
`DIRECT_ONLY` response's roles are always genuinely direct, never a mixed selection with
disallowed rows merely hidden afterward; an unrecognized value is a validation error. Upstream
timeout/network/schema failures retain the existing error-envelope behavior. Responses are
public-cacheable for 30 seconds so app/detail/widget consumers do not independently amplify
upstream traffic.

Each returned journey carries a `role` of `PRIMARY`, `NEXT`, or `ALTERNATIVE` — Android renders
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
widget's disruption strip, and Routine Details' own disruption cards, and why an
`EXACT_DESTINATION` routine never calls `/api/v1/disruptions` at all — that endpoint, and
everything described in §3 below, remains `LINE_DIRECTION`-only.

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

`Cache-Control: public, s-maxage=60, stale-while-revalidate=60` (see §7, fair use).

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
reuse this endpoint.

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
| `/api/v1/disruptions` | `public, s-maxage=60, stale-while-revalidate=60` | see fair use below |

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
without one. Direction-option discovery is kept behind a `DirectionOptionsSource`-shaped
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
