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
  service account. Do not place its key in the Android app or commit it.
- In Play Console, grant that service account only the permissions needed to view orders and
  manage orders/acknowledgements for the Blick app.
- Configure the backend deployment with:
  - `GOOGLE_PLAY_PACKAGE_NAME`
  - `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`
  - `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` (preserve or restore PEM newlines)
  - `DATABASE_URL`
  - `GOOGLE_PLAY_RTDN_AUDIENCE`
  - `GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL`
- Keep the existing production Upstash variables configured as documented in
  [`../backend/README.md`](../backend/README.md).
- Redeploy and confirm `/api/v1/billing/verify` validates a real test purchase and acknowledges
  it. Confirm invalid, cancelled, pending, refunded and revoked tokens do not grant access.

Official references: [Android Publisher API setup](https://developers.google.com/android-publisher/getting_started),
[Product Purchases v2 verification](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2),
and [secure backend integration](https://developer.android.com/google/play/billing/backend).

## Durable billing state and RTDN before production

The repository contains the PostgreSQL schema/migration, transaction-safe purchase store,
authenticated RTDN handler and Google revalidation. Complete these external steps:

- Provision a production PostgreSQL database, set `DATABASE_URL`, and run
  `npm run migrate:billing` from the deployed revision before sending billing traffic.
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

The app remains accountless: a purchaser restores through the Google account currently available
to BillingClient. The server intentionally permits repeated verification of the same legitimate
token and therefore cannot bind ownership to a Blick identity or distinguish people sharing the
same Google account/device access.

Official reference: [Real-time Developer Notifications](https://developer.android.com/google/play/billing/rtdn-reference).

## Release sign-off

- Run Android unit tests, lint, assemble, migration tests on an emulator/device, and a real
  internal-track purchase matrix.
- Run backend typecheck, lint, tests, build and dependency audit with Node 22.
- Update the public privacy policy/store Data Safety answers for purchase tokens, IP/request logs,
  local routines and the absence of payment-card collection.
- Verify English and Swedish purchase/paywall/privacy copy on-device and with large font sizes.
