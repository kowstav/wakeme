# Key and secret rotation

Rotate on a schedule (at least yearly, and when someone with access leaves) and immediately on
any suspected leak. Every value below lives in the platform secret manager only. Generate keys
inside the secret manager or on a trusted machine, never in a shared terminal or a chat.

On a suspected leak: open an incident ([incident-response.md](incident-response.md)), use the
matching kill switch while rotating, then rotate.

## Challenge signing key (`CHALLENGE_SIGNING_KEY`, `_ID`, `CHALLENGE_VERIFY_KEYS`)

Published challenges are ES256-signed once and verified on the phone before offline install
(ADR 0008). Rotation keeps content byte-identical and only re-signs it.

1. Generate the new key and pick the next ID (`c1` → `c2`):
   ```sh
   openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt   # new private key
   openssl ec -in old.pem -pubout -outform DER | base64 -w0                          # old public key (SPKI)
   ```
2. Deploy API and worker with `CHALLENGE_SIGNING_KEY=<new>`, `CHALLENGE_SIGNING_KEY_ID=c2`
   and, for a **scheduled** rotation only, `CHALLENGE_VERIFY_KEYS=c1=<old SPKI>`. New
   publications are signed with `c2`; `GET /v1/config` lists `c2` first, then `c1`.
3. Re-sign everything already published: `npm run resign-challenges -w @wakeme/api`. It
   prints the count, is safe to re-run, and refuses any row whose stored digest does not
   match (exit code 2, IDs listed: investigate those as possible tampering).
4. After 24 hours, remove `c1` from `CHALLENGE_VERIFY_KEYS` and redeploy.

Phones: Android refetches its key list when it meets an unknown key ID and at least daily, so
a removed key stops being trusted within a day. Challenges already installed were verified at
install time and stay installed. The iOS app has no challenge runner yet.

**Compromised key:** skip the `CHALLENGE_VERIFY_KEYS` entry in step 2 (never publish a
compromised key), run step 3 immediately, and review challenge revisions published since the
suspected leak. Phones fetch revisions only from the API over TLS, so a forged envelope also
needs a compromised API or TLS path.

Tests: `re-signs published revisions under a new key without changing their content`,
`publishes retired verification keys after a signing-key rotation, current key first`,
`config: retired challenge verification keys`, challenges package `re-signs the same bytes…`.

## Location encryption key (`LOCATION_ENCRYPTION_KEY`, `_ID`)

Coordinates are AES-256-GCM encrypted at rest; each sample records its key ID. Samples exist
only while a sharing session runs (at most 480 minutes) and are deleted when it ends.

1. Generate: `openssl rand -base64 32`; pick the next ID (`l1` → `l2`).
2. Deploy API and worker with the new key and ID.
3. Samples written under `l1` read as "no location" (never an error) until the sharing phone
   sends its next fix. Tell support this is expected for a few minutes.
4. Destroy `l1` after 8 hours. Backups then hold only unreadable `l1` ciphertext, which is
   the intended outcome.

**Compromised key:** turn off the `location` kill switch, rotate, destroy `l1` at once, turn
`location` back on. Anyone holding a database copy and `l1` could read samples from sessions
active at the time.

Test: `after a location-key rotation an old sample reads as "no location", never an error,
until replaced`.

## Push credentials

- **APNs** (`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`): Apple Developer → Keys →
  create a key with APNs enabled; deploy the worker; send a test quack between two test
  accounts; then revoke the old key in the portal.
- **FCM** (`FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`): Google Cloud IAM → the
  service account (role "Firebase Cloud Messaging API Admin" only) → add a key; deploy the
  worker; test; delete the old key. Prefer keyless workload identity if the host supports it.

During the switch a few pushes may fail and are retried by the outbox; expired attention
requests are never replayed late.

## Identity (Supabase)

- **Access-token signing keys:** rotate in the Supabase dashboard using its standby-key flow.
  The API verifies tokens against `AUTH_JWKS_URL` and refetches the key set when it sees an
  unknown key ID (cached up to 10 minutes), so no deploy is needed. Old tokens stop working
  when the old key is revoked; apps refresh their session.
- **Service role / secret key** (`SUPABASE_SERVICE_ROLE_KEY`, worker only, used to delete
  identities during account deletion): create a new secret key, deploy the worker, revoke the
  old one. Deletion jobs that fail meanwhile retry.

## Billing (RevenueCat)

- **Webhook secret** (`REVENUECAT_WEBHOOK_AUTH`): deploy the API with the new value, then set
  the same value in RevenueCat. Webhooks rejected in between are retried by RevenueCat, and
  the 6-hourly reconciliation re-verifies anything missed ([billing-reconciliation.md](billing-reconciliation.md)).
- **API key** (`REVENUECAT_API_KEY`): create a new secret key, deploy, revoke the old one.

## Storage, AI and database

- **S3/R2** (`S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`): new key scoped to the one bucket;
  deploy; upload and play a test sound; revoke the old key. Use `media_uploads` off if leaked.
- **Anthropic** (`ANTHROPIC_API_KEY`): new key in the Console with a spend limit; deploy;
  revoke the old one. Use `ai_generation` off if leaked.
- **Database roles**: `alter role wakeme_app password '<new>'`, update `DATABASE_URL` and
  roll API and worker; same for the migration role and `MIGRATION_DATABASE_URL`.

## People

When an operator or moderator leaves: remove their ID from `ADMIN_USER_IDS`, clear
`app_metadata.wakeme_role`, sign out their sessions in Supabase, and rotate any secret they
could read.

## Device keys

Each phone has its own non-exportable key (Android Keystore, iOS Secure Enclave). Operators do
not rotate these. A lost phone is signed out from another device (`DELETE /v1/devices/:id`);
a reinstall registers a new key ([account-deletion-and-export.md](account-deletion-and-export.md)).

## Drill log

Not yet exercised. Drill: in staging, publish a challenge, install it on an Android test phone,
rotate `c1` → `c2`, run `resign-challenges`, install a second challenge on the same phone,
remove `c1`, confirm both still run offline.
