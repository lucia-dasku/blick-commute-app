# Google Play Premium release checklist

The code uses one non-consumable, one-time product: `blick_premium_lifetime`. The planned
Swedish base price is 49 SEK, but the app always displays Google Play's localized price.
Repository-side purchase verification is implemented, but launch is **not production-ready**
until every external provisioning, configuration and internal-track test below is complete.

## Play Console

- Create and activate the one-time product `blick_premium_lifetime` for Blick's package. Add a
  purchase option, set the Swedish price to 49 SEK, review Google's converted local prices, and
  provide the required title/description in release languages.
- Confirm Play App Signing and upload a signed Android App Bundle to an internal test track.
- Add license testers and test: successful purchase, user cancellation, slow/pending payment,
  acknowledgement, reinstall/automatic restore, explicit Restore purchase, refund and revoke.
- Test from a Play-installed build; Billing cannot be validated reliably from an ordinary
  side-loaded debug APK.

Official references: [one-time products](https://developer.android.com/google/play/billing/one-time-products),
[Billing integration](https://developer.android.com/google/play/billing/integrate), and
[purchase testing](https://developer.android.com/google/play/billing/test).

## Google Cloud and Play Developer API

- In a Google Cloud project, enable the Google Play Android Developer API and create a dedicated
  service account. Google documents these steps without requiring a Cloud Billing account, and a
  Cloud project can be created without attaching one. Do not enable Cloud Billing for the initial
  launch. If the console unexpectedly requires it for Android Publisher API access, stop and
  investigate before continuing. Do not place the service-account key in the Android app or
  commit it.
- In Play Console, grant that service account only the permissions needed to view orders and
  manage orders/acknowledgements for the Blick app.
- Configure the backend deployment with:
  - `GOOGLE_PLAY_PACKAGE_NAME`
  - `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`
  - `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` (preserve or restore PEM newlines)
  - `DATABASE_URL`
- Leave `GOOGLE_PLAY_RTDN_AUDIENCE` and `GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL` unset in the
  initial zero-cost mode. Their absence disables only RTDN; it does not disable verification or
  acknowledgement.
- Keep the existing production Upstash variables configured as documented in
  [`../backend/README.md`](../backend/README.md).
- Redeploy and confirm `/api/v1/billing/verify` validates a real test purchase and acknowledges
  it. Confirm invalid, cancelled, pending, refunded and revoked tokens do not grant access.

Official references: [Android Publisher API setup](https://developers.google.com/android-publisher/getting_started),
[Product Purchases v2 verification](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2),
and [secure backend integration](https://developer.android.com/google/play/billing/backend).

## Durable billing state for initial zero-cost production

The repository contains the PostgreSQL schema/migration, transaction-safe purchase store,
authenticated optional RTDN handler and Google revalidation. Complete these external steps:

- Provision a Neon Free PostgreSQL project without adding a payment method. Free currently
  includes 0.5 GB storage, 100 CU-hours per project each month, 5 GB public network transfer, and
  up to six hours or 1 GB of restore history. Idle compute scales to zero after five minutes and a
  query wakes it automatically. Free-limit exhaustion can restrict or suspend service rather than
  create an overage charge.
- Use the Neon pooled connection as the deployed Vercel `DATABASE_URL`. Run
  `npm run migrate:billing` once from the deployed revision with a direct connection supplied only
  to that migration process. Do not commit or print either connection string.
- Keep regular off-provider logical backups. Neon Free has no production SLA and its restore
  history is deliberately short; a cold start or free-limit suspension must fail verification
  closed until the database is available again.
- Confirm the migration created `google_play_purchases` and `google_play_rtdn_messages`, then
  exercise `/api/v1/billing/verify` before accepting production billing traffic. The RTDN table is
  harmless while the optional route is disabled.
- Verify completed purchase, pending-to-purchased after reopening the app, acknowledgement,
  restore, cancellation, refund and revoke with Play license testers. Confirm a successful Play
  query with no owned product clears Premium and a temporary backend/database failure does not
  manufacture entitlement.

The current SQL and store are Neon-compatible: they use standard PostgreSQL tables, partial
indexes, check constraints, `ON CONFLICT`, explicit transactions, and the built-in
`pg_advisory_xact_lock`/`hashtextextended` functions. The runtime disables prepared statements
and uses at most one database connection per function instance. The transaction-scoped advisory
lock remains within one pooled transaction.

Supabase Free is a workable standard-PostgreSQL fallback with a 500 MB database quota, shared CPU,
500 MB RAM and two active free projects. It does not charge Free-plan overages, but low-activity
projects are paused after about seven days and require a manual dashboard resume; requests do not
automatically wake them. Automatic backups are not included. That manual pause behavior makes it
less suitable than Neon Free for a tiny production billing verifier.

The app remains accountless: a purchaser restores through the Google account currently available
to BillingClient. The server intentionally permits repeated verification of the same legitimate
token and therefore cannot bind ownership to a Blick identity or distinguish people sharing the
same Google account/device access.

## Future enhanced mode: RTDN

Google documents RTDN as an optional listener that improves lifecycle synchronization; the Play
Developer API remains available for authoritative checks without it. Pub/Sub currently requires
a Cloud Billing account even though its first 10 GiB of monthly throughput is priced at zero, so
RTDN is intentionally disabled for the initial zero-cost launch.

Without RTDN, purchase and restore paths still acknowledge completed purchases while the app is
active. A pending purchase that completes while the app is closed is acknowledged on the next
startup/foreground refresh; if the user does not return within Google's three-day window, Play can
automatically refund the unacknowledged purchase. Refund and revocation removal may likewise wait
for the next successful lifecycle or explicit revalidation. The app does not start a second timer,
poll from commute workers, or globally poll stored tokens.

After revenue justifies Cloud Billing, enable enhanced mode with these steps:

- Create a Google Cloud Pub/Sub topic in the project linked to Play Console and grant the Google
  Play notifications service account permission to publish to it. Enter that topic in Play
  Console's Real-time developer notifications settings and send a test notification.
- Create a push subscription whose HTTPS endpoint is
  `https://<production-host>/api/v1/billing/rtdn`. Enable authenticated push with a dedicated,
  least-privilege service account. Grant the Pub/Sub service agent permission to mint OIDC tokens
  for that identity as required by Google Cloud.
- Set the push OIDC audience and `GOOGLE_PLAY_RTDN_AUDIENCE` to that exact endpoint URL, and set
  `GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL` to the selected push identity. Do not store a token,
  JSON key or private key for Pub/Sub in the repository.
- Confirm the test notification receives `204`, then monitor Pub/Sub delivery failures and backend
  Google API errors. Configure an appropriate retry policy/dead-letter handling operationally.
- Exercise completed purchase, pending-to-purchased, duplicate delivery, pending refund review,
  final refund, revoke and cancellation with Play license testers. Confirm the review receives a
  neutral response without changing entitlement, then confirm a final refund/revoke is reflected
  in PostgreSQL and Android on the next foreground verification.

The Voided Purchases API can support a later bounded reconciliation job for canceled, refunded or
charged-back purchases. Do not add a scheduled global poll for the initial release; RTDN remains
the preferred near-real-time enhancement.

Official references: [Billing integration](https://developer.android.com/google/play/billing/integrate),
[Real-time Developer Notifications](https://developer.android.com/google/play/billing/rtdn-reference),
[Pub/Sub prerequisites](https://cloud.google.com/pubsub/docs/publish-receive-messages-client-library),
[Voided Purchases API](https://developers.google.com/android-publisher/voided-purchases),
[Neon pricing](https://neon.com/pricing),
[Neon connection pooling](https://neon.com/docs/connect/connection-pooling),
[Supabase pricing](https://supabase.com/pricing), and
[Supabase free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).

## AdMob and privacy messaging

Repository-verified:

- GMA Next-Gen and UMP are integrated with a test-only debug banner unit and the production
  banner unit confined to release configuration.
- The publisher reports that Blick Commute's European regulations message is published in
  English and Swedish. Recheck this external AdMob console state during release sign-off.

Pending external release work:

- Review and update Play Data Safety answers for Google Mobile Ads and its merged permissions.
- Review the public privacy policy's advertising disclosure; the in-app summary has been updated,
  but the externally hosted policy requires its own publication and legal review.
- Deploy `app-ads.txt` through the website associated with Blick's Play developer listing, then
  verify that AdMob crawls and accepts it.
- Verify the AdMob app review/readiness and payment status in the AdMob console.
- Recheck the published European regulations message and both languages in the production AdMob
  app before release.

## Release sign-off

- Run Android unit tests, lint, assemble, migration tests on an emulator/device, and a real
  internal-track purchase matrix.
- Run backend typecheck, lint, tests, build and dependency audit with Node 22.
- Update the public privacy policy/store Data Safety answers for purchase tokens, IP/request logs,
  local routines and the absence of payment-card collection.
- Verify English and Swedish purchase/paywall/privacy copy on-device and with large font sizes.
