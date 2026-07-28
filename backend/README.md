# Blick — backend

TypeScript/Hono proxy and normalization layer in front of SL Transport and SL
Deviations. See `../docs/api-contract.md` for the full contract, upstream field mapping,
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
```

No API key or `.env` values are required — both upstreams are keyless (see
`.env.example`). The optional env vars are `UPSTREAM_TIMEOUT_MS` (default `10000`),
which controls how long the backend waits for SL Transport/SL Deviations before
returning `UPSTREAM_TIMEOUT` (504), and `PORT` (default `8787`, local dev server only).
Both are validated eagerly at startup — a non-numeric, non-positive, or out-of-range
value fails immediately with a clear error rather than silently coercing to `NaN` or an
unusable value (see `src/config/env.ts`).

## Validation

```
npm run typecheck   # tsc --noEmit
npm run lint        # eslint (flat config, eslint.config.js)
npm run build       # tsc, emits to dist/
npm test            # vitest — 181 tests
npm audit           # dependency vulnerability scan
```

Test coverage (181 tests across 16 files): DST resolver (including calendar validation
— rejecting impossible dates and the spring DST gap, and ISO round-trip consistency),
cancellation derivation, search ranking, cache/dedup, `fetchedAt` semantics (fresh,
cached, and deduplicated-concurrent requests), request-filter validation (`future`,
`transportMode`, `siteId`, `lineId`), runtime (Zod) validation of upstream payloads
— including contract integer fields as safe integers (IDs, direction codes, versions,
priorities, importance levels), SL Deviations' `created`/`modified`/`publish.from`/
`publish.upto` as explicit-offset RFC 3339 timestamps (rejecting naive/offset-less
strings), and `message_variants` as non-empty — stop-search query validation running
BEFORE the site directory is loaded (an invalid/oversized query causes zero upstream
requests), `PORT`/`UPSTREAM_TIMEOUT_MS` env var validation, upstream networking (timeout
covering both headers and a stalled response body, vs. ordinary network failure,
vs. HTTP 429, vs. malformed response), `Retry-After` header validation (valid
delay-seconds/HTTP-date forwarded, invalid values omitted), error-response leakage
prevention (including malformed upstream data consistently mapping to a sanitized
`UPSTREAM_ERROR`/502, never an unexpected 500), disruption message selection,
contract/serialization (against real fixture data captured live during architecture
review), route validation/error-envelope behavior, and the actual Vercel entry point
(`api/index.ts`, imported directly rather than re-testing a copy of it).

All of the above passed clean in the authoring sandbox (0 type errors, 0 lint
errors/warnings, 181/181 tests passing, `npm audit`: 0 vulnerabilities). A live smoke
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

No API keys or secrets are required for either current upstream, so no environment
variables need to be configured in the Vercel project for a working deployment; the
upstream base URLs (`SL_TRANSPORT_BASE_URL`, `SL_DEVIATIONS_BASE_URL`) and
`UPSTREAM_TIMEOUT_MS` remain overridable via Vercel's Environment Variables settings if
ever needed (e.g. pointing at a mock upstream for a preview environment).

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
