# Billing reconciliation

Plus status comes only from RevenueCat's API, never from a webhook body or the app.
Subscriptions never gate stopping audio, revoking grants or removing restrictions, and a
billing outage never blocks free features.

## How state stays correct

- **Webhook** `POST /v1/billing/webhooks/revenuecat`: authenticated by the shared secret,
  deduplicated by event ID (`billing_events`), then the server re-fetches the subscriber from
  RevenueCat for every user ID in the event (including transfers).
- **Reconciler** (worker, every 6 hours): re-verifies entitlements expiring within 3 days
  either side of now, and any not synced for a day.
- **On demand**: the app calls `POST /v1/billing/sync` after a purchase or "Restore
  purchases" (10 per hour per person).

Tests (against a fake RevenueCat): `a forged webhook body cannot grant Plus`, `rejects
unauthenticated webhooks`, `grants Plus from verified state…`, `billing outages never block
free features`.

## Procedures

**"I paid but I don't have Plus."** Ask them to tap Restore purchases (same account as the
purchase). If still wrong: check RevenueCat's customer page for the app user ID (the Wake Me
user ID) and the store transaction. If RevenueCat shows the entitlement, the server picks it
up on the next sync; if RevenueCat does not, it is a store or RevenueCat issue.

**Missed webhooks (RevenueCat or our API was down).** Nothing to do for correctness: the
reconciler catches up within 6 hours, and RevenueCat retries failed webhooks. To speed up,
redeploying the worker runs reconciliation on its first tick. Check failures with
`select event_id, event_type, received_at from billing_events where processed_at is null`.

**Refunds and chargebacks.** RevenueCat sends an event; the server re-fetches and Plus ends.
Plus-only creation stops; existing routines, alarms and permissions keep working (doc 04:
downgrade future premium creation gracefully).

**Account deletion with an active subscription.** Deletion does not cancel a store
subscription. Android's deletion confirmation says to cancel in Google Play; the iOS deletion
screen (not built yet) must say the same for the App Store. Webhooks for a deleted user are
recorded and ignored.

## Monthly check

Compare the count of active Plus entitlements (`select count(*) from entitlements where
expires_at > now()`) with RevenueCat's active subscriber count; investigate a gap above 1%.

## Drill log

Not yet exercised (no RevenueCat project yet). Drill: sandbox purchase, refund, restore on a
second phone, and a webhook outage covered by the reconciler.
