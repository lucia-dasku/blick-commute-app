# Blick — backend

TypeScript/Hono proxy and normalization layer in front of SL Transport, SL Deviations,
and SL Journey Planner, plus server-side Google Play purchase verification. See
`../docs/api-contract.md` for the full contract, upstream field mapping,
caching policy, error-code semantics, and known limitations.

## Local development

```
npm install
npm run dev        # starts a local Node server on :8787 (src/server.ts)
```

```
curl http://localhost:8787/api/v1/health
curl "http://localhost:8787/api/v1/stops/search?query=slussen"
curl "http://localhost:8787/api/v1/departures?siteId=9192"
curl "http://localhost:8787/api/v1/disruptions?siteId=9192"
curl "http://localhost:8787/api/v1/journeys/locations/search?query=slussen"
curl "http://localhost:8787/api/v1/journeys?originId=...&destinationId=...&transportModes=METRO,TRAIN,BUS"
```

No API key or `.env` values are required to exercise the transit endpoints locally — all
three SL upstreams are keyless (see `.env.example`). Billing verification deliberately fails
with a sanitized upstream error until `GOOGLE_PLAY_PACKAGE_NAME`,
`GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`, and `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` are set.
The shared Upstash
Redis cache/lock that protects SL Deviations in production (see
`../docs/api-contract.md`, "Caching and fair use") is **optional** here: with
`UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN` unset, `npm run dev` automatically
falls back to the in-memory `Cache`/`DistributedLock` implementations, which is correct
for local development (see "Redis (Upstash) setup" below for when you'd want to set them
locally anyway — mainly to reproduce a production-only bug). The other optional env vars
are `UPSTREAM_TIMEOUT_MS` (default `10000`), which controls how long the backend waits
for SL Transport/SL Deviations before returning `UPSTREAM_TIMEOUT` (504), and `PORT`
(default `8787`, local dev server only). All of these are validated eagerly at startup —
a non-numeric, non-positive, out-of-range, or (for the Redis pair) partially-set value
fails immediately with a clear error rather than silently coercing to `NaN`, an unusable
value, or an unprotected fallback (see `src/config/env.ts`).

## Validation

```
npm run typecheck   # tsc --noEmit
npm run lint        # eslint (flat config, eslint.config.js)
npm run build       # tsc, emits to dist/
npm test            # vitest — 268 tests
npm audit           # dependency vulnerability scan
```

Test coverage (268 tests across 21 files): DST resolver (including calendar validation
— rejecting impossible dates and the spring DST gap, and ISO round-trip consistency),
cancellation derivation, search ranking, cache/dedup, `fetchedAt` semantics (fresh,
cached, and deduplicated-concurrent requests — including concurrent requests with
*different* filter combinations sharing one upstream call and one `fetchedAt`),
request-filter validation (`future`, `transportMode`, `siteId`, `lineId`, each also
proven to actually narrow the response via local filtering, not just accepted/rejected),
the departures endpoint's optional `forecast`
parameter (omitted-value passthrough, valid-value passthrough up to its 1200-minute
cap, and rejection of zero/negative/fractional/non-numeric/above-cap values), the real
(non-fake) `createSlTransportClient`'s own URL construction (confirming it actually
appends `?forecast=1200` to the real upstream URL, not just a hand-rolled fake client
elsewhere in the suite), runtime (Zod) validation of upstream payloads
— including contract integer fields as safe integers (IDs, direction codes, versions,
priorities, importance levels), SL Deviations' `created`/`modified`/`publish.from`/
`publish.upto` as explicit-offset RFC 3339 timestamps (rejecting naive/offset-less
strings), and `message_variants` as non-empty — stop-search query validation running
BEFORE the site directory is loaded (an invalid/oversized query causes zero upstream
requests), `PORT`/`UPSTREAM_TIMEOUT_MS`/Upstash Redis env var validation (including the
production-only hard-failure path when Redis credentials are missing or partially set),
the SL Deviations shared-snapshot service (`deviationsSnapshotService.ts` — concurrent
requests from separate simulated instances sharing one cache/lock producing exactly one
upstream call, the 60-second fair-use limit including a failed attempt's own cooldown,
stale fallback with the original `fetchedAt` preserved, the controlled error when no
snapshot has ever succeeded, refresh-lock expiry/recovery after a simulated stuck holder,
and a throwing `lock.release()` never turning an already-successful, already-cached fetch
into a failure), the local deviations filter (`deviationsFilter.ts` — `siteId` resolved via the
site directory's own child stop-area IDs, `lineId`/`transportMode`, the validity-period/
`future` window, and — since this is the one dimension with genuinely different
semantics depending on the deviation's own shape — line-only deviations with no
`scope.stop_areas` at all being included only when both `lineId` and `transportMode` are
requested and match, station-specific deviations continuing to require a `siteId` match
regardless of any line/mode overlap, deviations scoped to an unrelated station staying
excluded even when their line/mode also matches, and combined queries mixing
station-specific and line-only deviations selecting the correct subset of each), the
`DistributedLock` contract on its in-memory implementation (safe expiry, and
ownership-protected release — a late release from an already-expired holder must never
delete a different, legitimate new holder's lock), the `RedisCache`/`RedisLock` adapters
against a fake Redis client implementing real GET/SET/EVAL semantics (not just the
in-memory simulations — proving `RedisCache.get`/`.set` and `RedisLock.acquire`/
`.release` translate correctly into `{ex}`/`{nx, px}`/`eval(script, keys, args)`, TTL
expiry in both seconds and milliseconds, NX refusal, and the same ownership-protected
release guarantee as the in-memory lock),
upstream networking (timeout
covering both headers and a stalled response body, vs. ordinary network failure,
vs. HTTP 429, vs. malformed response), `Retry-After` header validation (valid
delay-seconds/HTTP-date forwarded, invalid values omitted), error-response leakage
prevention (including malformed upstream data consistently mapping to a sanitized
`UPSTREAM_ERROR`/502, never an unexpected 500), disruption message selection,
contract/serialization (against real fixture data captured live during architecture
review), route validation/error-envelope behavior, and the actual Vercel entry point
(`api/index.ts`, imported directly rather than re-testing a copy of it).

Also covers the `/api/v1/disruptions` and `/api/v1/stops/search` response envelopes
themselves (`DisruptionsResponseSchema`/`StopSearchResponseSchema` in `contract.test.ts`,
mirroring the same fixture-round-trip pattern `DeparturesResponseSchema` already followed)
— these two schemas previously existed but were never wired into a test, found and fixed
during a dead-code audit that also removed the unused, never-thrown `RATE_LIMITED` error
code and a handful of unused derived TypeScript type aliases whose underlying Zod schemas
remained genuinely in use.

All of the above passed clean in the authoring sandbox (0 type errors, 0 lint
errors/warnings, 268/268 tests passing, `npm audit`: 0 vulnerabilities). A live smoke
test of the running server against the real SL endpoints could not be completed from
that sandbox — its outbound network proxy blocks `transport.integration.sl.se` /
`deviations.integration.sl.se` / `vercel.com` / `api.vercel.com` by allowlist. The
contract/serialization tests instead exercise the full normalization pipeline against
real response bodies captured live from those endpoints (see `fixtures/`), which is why
fixture provenance is documented in `fixtures/README.md`. Run `npm run dev` and the curl
commands above on a machine with normal internet access to confirm live end-to-end
behavior.

## Deploying to Vercel

Targets Vercel (`vercel.json` + `api/index.ts`), using Hono's documented "export the app
as Vercel's default export" pattern — no separate adapter package is needed
(`hono.dev/docs/getting-started/vercel`). The Node.js runtime major version is pinned via
`"engines": { "node": "22.x" }` in `package.json` (Vercel currently supports 20.x, 22.x,
and 24.x; the `engines` field takes precedence over the dashboard's Project Settings
value).

**Root Directory:** when importing the `blick` repository into a new Vercel project, set
**Root Directory to `backend`** — the repository root also contains `android/` and
`docs/`, neither of which should be part of this deployment. With Root Directory set
correctly, only this directory's files are considered.

Neither current upstream (SL Transport, SL Deviations) requires an API key. **The Upstash
Redis credentials below ARE required for a production deployment** — see "Redis (Upstash)
setup" immediately below. The upstream base URLs (`SL_TRANSPORT_BASE_URL`,
`SL_DEVIATIONS_BASE_URL`) and `UPSTREAM_TIMEOUT_MS` remain optional and overridable via
Vercel's Environment Variables settings if ever needed (e.g. pointing at a mock upstream
for a preview environment).

### Redis (Upstash) setup

SL Deviations' fair-use guidance allows **at most one request per minute, in aggregate
across every Vercel instance** (see `../docs/api-contract.md`, "Caching and fair use").
Enforcing that requires state shared across instances, which a Vercel serverless
deployment does not provide on its own — this backend uses
[Upstash Redis](https://upstash.com) (via its HTTP REST API, `@upstash/redis`, which
works from serverless/edge functions without a persistent TCP connection) as that shared
store.

**In production (`NODE_ENV=production`), `UPSTASH_REDIS_REST_URL` and
`UPSTASH_REDIS_REST_TOKEN` are required — the backend refuses to start without both set**
(`src/config/env.ts`, `readRedisConfig`), rather than silently falling back to the
in-memory `Cache`/`DistributedLock` implementations, which provide no cross-instance
protection at all. This is intentional: a production deployment that appeared to work but
silently violated SL's fair-use guidance under real traffic would be worse than one that
fails to start with a clear error.

Two ways to set them, either sets the exact same two variable names:

1. **Vercel's own Upstash integration (recommended)** — from the Vercel dashboard,
   Storage tab → Connect Database → Upstash → create (or link an existing) Redis
   database. This automatically creates the database and populates
   `UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN` as environment variables on the
   linked project — no manual copying of values.
2. **Manual setup** — create a Redis database directly at
   [upstash.com](https://upstash.com/), copy its REST URL and REST token from the
   Upstash console, and add both as environment variables in the Vercel project's
   Settings → Environment Variables (Production, and Preview if preview deployments
   should also share protection with production — see the note below).

**Preview deployments and the 60-second window are a real tradeoff to be aware of:** if a
preview deployment is configured with the *same* Redis database as production, its
disruption traffic shares the same 60-second budget as production's — a burst of preview
testing could theoretically delay a production refresh by a few tens of seconds, never
longer, since the worst case is simply "wait out the current fair-use window." If that
tradeoff is undesirable, point Preview environment variables at a *separate* Upstash
database (Upstash's free tier supports multiple databases) instead of sharing production's.

Local development and the automated test suite do **not** need any Redis configuration —
see "Local development" above.

From this directory, once the Vercel CLI is installed and you are logged in
(`vercel login`) and the project is linked (`vercel link`):

```
# One-time: link this directory to a Vercel project (interactive; choose the existing
# "blick" scope/project or create a new one). Root Directory must be `backend` if linking
# from the repository root instead of from inside backend/.
vercel link

# Preview deployment (safe default — does not affect production traffic):
vercel deploy

# Production deployment (after a preview looks correct):
vercel deploy --prod
```

`vercel build` (used internally by `vercel deploy`, or runnable standalone to inspect the
build output before deploying) requires network access to Vercel's own API
(`api.vercel.com`) to resolve the linked project and team, even before any code runs —
this was confirmed by running `vercel build` in the authoring sandbox, where it failed
with `Failed to fetch dist-tags from npm` / `TypeError: fetch failed` against
`api.vercel.com`, since that sandbox's proxy blocks the domain. This is a sandbox network
restriction, not a defect in this repository: `vercel build`/`vercel deploy` should be run
from a machine with normal internet access and an authenticated, linked Vercel CLI.
Local structural validation of the entry point itself (that `api/index.ts` exports a
working Hono app and routes every documented endpoint) is instead covered by an
automated test (`tests/vercelEntry.test.ts`), which imports `api/index.ts` directly and
does not require Vercel or network access.
