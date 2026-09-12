# Privacy publication source

Updated: 11 September 2026

`blick-privacy.html` is the reconciled publication source for
https://blick-labs.vercel.app/blick-privacy. It was copied from the existing website
snapshot under `artifacts/blick-website-new-production-20260902` and updated against
the billing, reviewer-access, and advertising implementation. The website's existing styles and assets
remain external to this directory. This is not a standalone website or deployment.

The public page was still dated 2 September 2026 when checked. Committing this source
does not update the live site. Publication requires a separate authorized website update.

The shorter in-app summary is maintained in the English and Swedish `about_privacy_*`
Android resources. Keep material claims aligned with this policy and the billing and
reviewer-access mechanisms documented in `../api-contract.md`.

Provider verification remains necessary for hosting/database/Redis logs and backups,
Pub/Sub retention and dead-letter configuration, advertising/consent-provider retention,
and the operational handling of identifiable purchase-deletion requests. Application
cleanup and Redis expiry do not establish deletion timing for provider-held copies.
