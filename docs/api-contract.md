# Blick — API contract and upstream mapping

This document is the source of truth for the backend's public contract and for how
every normalized field maps back to the upstream SL APIs. It is kept in sync with the
implementation under `backend/src`; the normalization code links back to this file in
comments at the points where a non-obvious decision was made.

## 1. Upstream architecture

The backend talks to two keyless, official SL/Trafiklab APIs:

- **SL Transport** (`https://transport.integration.sl.se/v1`) — sites, stop areas, stop
  points, lines, and real-time departures. Primary upstream for everything the app shows
  during a commute window.
- **SL Deviations** (`https://deviations.integration.sl.se/v1`) — the full disruption
  feed, with validity windows, multi-language messages, and a weblink. Used for the
  disruptions section of the UI.

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

`code` is one of: `VALIDATION_ERROR` (400), `NOT_FOUND` (404), `RATE_LIMITED` (429),
`UPSTREAM_RATE_LIMITED` (503), `UPSTREAM_TIMEOUT` (504), `UPSTREAM_ERROR` (502),
`INTERNAL_ERROR` (500). See §8, "Upstream networking, runtime validation, and error
handling", for exactly what each error response does and does not contain.

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

### `GET /api/v1/departures?siteId=`

Proxies `GET /v1/sites/{siteId}/departures`.

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

Proxies `GET /v1/messages` on SL Deviations, using the same site/line IDs SL Transport
already returned — no conversion. **`siteId` is required** (not optional): a routine
always has a site, and requiring it keeps every disruption request properly scoped
instead of allowing a call that pulls the entire SL network's deviations at once.

`lineId` is an optional positive integer filter. `transportMode` is an optional filter,
strictly validated against the closed set of modes SL actually documents as filterable
(`BUS`, `METRO`, `TRAIN`, `TRAM`, `SHIP`, `FERRY`, `TAXI`) — an unsupported, empty, or
malformed value is a `VALIDATION_ERROR` (400), not silently ignored or forwarded as-is.
`future` may only be absent, `"true"`, or `"false"`; defaulting to `false` when absent —
a normal active-routine request only sees currently published disruptions, and a caller
must opt in explicitly (`future=true`) to also see disruptions published for the future.
Any other value for `future` (e.g. `"banana"`, `"True"`, empty) is a `VALIDATION_ERROR`
(400) rather than being silently coerced to `false`. **This request-filter validation is
intentionally a different, stricter check than how the response's own `transportMode`
fields are validated** — see "Request validation vs. response compatibility" below.

`Cache-Control: public, s-maxage=60, stale-while-revalidate=60` (see §7, fair use).

Response shape: `data: { fetchedAt, disruptions }`. `fetchedAt` and `disruptions` are
cached and deduplicated together, as one unit, keyed by the full filter set
(`siteId`/`lineId`/`transportMode`/`future`): a cache hit returns the *original*
upstream-fetch time, never a freshly generated one, and concurrent identical requests
(via the in-flight deduper) share exactly one upstream call and therefore one
`fetchedAt` (see §7 and `tests/fetchedAt.test.ts`).

| Normalized field | Upstream field (`RawDeviation`) | Notes |
|---|---|---|
| `disruptionId` | `deviation_case_id` (stringified) | |
| `version` | `version` | |
| `createdAt` / `modifiedAt` | `created` / `modified` | already ISO 8601 with an explicit offset upstream — passed through unchanged |
| `validFrom` / `validUntil` | `publish.from` / `publish.upto` | |
| `priority` | `priority.{importance,influence,urgency}_level` | upstream documents these as sort hints only; no invented `severity` field exists |
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
priority breakdown. The MVP calls both: the embedded fields are surfaced as-is for a
free, no-extra-request signal; the full disruptions section of the UI is still backed by
`/api/v1/disruptions`.

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

SL Deviations' own guidance asks for **at most one request per minute**. This backend
does **not** currently guarantee that limit is honored in production, for two distinct,
compounding reasons — this is a known gap, not a solved problem, and it is a **blocker
for public production traffic** until addressed:

1. **Per-instance, not global.** The in-memory `Cache`/`InFlightDeduper` implementations
   are best-effort and scoped to a single serverless instance. Vercel does not guarantee
   shared memory across invocations or even across concurrent invocations of the "same"
   function — under real traffic, multiple cold instances could each independently
   decide it's time to call SL Deviations, each believing it is the only caller.
2. **Per query combination, not per upstream overall.** The 60s cache/dedup key is
   `siteId:lineId:transportMode:future` (see `createDisruptionsRoute` in
   `src/routes/disruptions.ts`) — so the ≥60s TTL only limits repeat requests for the
   *same* filter combination to once a minute. If real traffic spans many distinct
   site/line/transport-mode/future combinations within the same minute (which is
   expected: different users track different stops), the aggregate request rate to SL
   Deviations across all combinations can still exceed one request per minute, even with
   this cache working exactly as designed and even on a single instance.

The HTTP `Cache-Control` headers in the table above are real and do help at the edge/CDN
layer, and the in-process cache/dedup do eliminate redundant calls for the identical
filter set within one instance's lifetime — neither is fake. But **do not read either of
those as full fair-use compliance**: this backend, as it stands, cannot promise SL
Deviations sees at most one request per minute in aggregate once there is real,
multi-user, multi-instance traffic. **Before any significant public deployment, a shared
cache (e.g. Upstash Redis) plus a real global rate limiter in front of the SL Deviations
call path is a production-readiness requirement, not an optional enhancement** — the
`Cache` interface exists specifically so that swap can happen without touching call
sites. Preview/development-scale traffic (a handful of manual testers) stays well under
SL's guidance in practice even with today's implementation; the risk is specifically
about uncoordinated production-scale traffic.

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
(Trafiklab's site-wide terms cover the SL APIs distributed through it). The future
Android About/Settings screen must carry visible attribution, e.g.:

> Based on information from Trafiklab.se

...with a link to Trafiklab.se where practical. The app must not imply affiliation with
or endorsement by SL or Trafiklab. This requirement is tracked here rather than only in
code comments specifically so it isn't lost before the first public release; the Android
scaffold's placeholder settings screen has a TODO pointing back to this section.

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
Transport's live forecast window. During routine setup, a route that isn't running right
now (e.g. a weekday rush-hour-only bus, checked on a Sunday) may not appear as a
selectable direction. This is a real, documented product limitation, not silently papered
over — the MVP does not add a Journey Planner or GTFS static-schedule dependency to solve
it. Direction-option discovery is kept behind a `DirectionOptionsSource`-shaped interface
in the Android app (see `android/README.md`) so its data source can be replaced later
(e.g. with a static schedule fallback) without touching Room or the UI. A saved routine's
identity is platform-neutral (`siteId`, `lineId`, `transportMode`, `directionCode`);
destination text may be stored for display only and is never the sole identity.

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
