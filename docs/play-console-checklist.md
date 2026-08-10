# Google Play Premium release checklist

The code uses one non-consumable, one-time product: `blick_premium_lifetime`. The planned
Swedish base price is 49 SEK, but the app always displays Google Play's localized price.
Purchase verification is **not production-ready** until every applicable item below is done.

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
- Keep the existing production Upstash variables configured as documented in
  [`../backend/README.md`](../backend/README.md).
- Redeploy and confirm `/api/v1/billing/verify` validates a real test purchase and acknowledges
  it. Confirm invalid, cancelled, pending, refunded and revoked tokens do not grant access.

Official references: [Android Publisher API setup](https://developers.google.com/android-publisher/getting_started),
[Product Purchases v2 verification](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2),
and [secure backend integration](https://developer.android.com/google/play/billing/backend).

## Refund/revocation freshness before production

The current repository re-queries owned purchases and re-verifies tokens at startup, on app
foreground, after purchase updates, and through Restore purchase. That is sufficient for local
feature testing but does not provide server-initiated, real-time revocation while the app remains
closed. Before calling purchase verification production-ready:

- Configure Google Play Real-time Developer Notifications (RTDN) with Pub/Sub.
- Add durable purchase-token ownership/state storage and an authenticated RTDN consumer in the
  backend, then re-query Product Purchases v2 for every relevant event. Never trust notification
  payload fields alone as entitlement proof.
- Define token retention/deletion and operational alerting. The current app has no Blick account,
  so cross-user token ownership and abuse controls need an explicit backend design.

Official reference: [Real-time Developer Notifications](https://developer.android.com/google/play/billing/rtdn-reference).

## Release sign-off

- Run Android unit tests, lint, assemble, migration tests on an emulator/device, and a real
  internal-track purchase matrix.
- Run backend typecheck, lint, tests, build and dependency audit with Node 22.
- Update the public privacy policy/store Data Safety answers for purchase tokens, IP/request logs,
  local routines and the absence of payment-card collection.
- Verify English and Swedish purchase/paywall/privacy copy on-device and with large font sizes.
