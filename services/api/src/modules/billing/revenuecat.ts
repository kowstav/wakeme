// RevenueCat is the subscription source of truth across App Store and Google
// Play. Webhooks are treated only as a signal to re-fetch the subscriber from
// the REST API, so out-of-order, replayed or forged webhook bodies cannot
// grant access by themselves.

export interface SubscriberEntitlement {
  productId: string | null;
  store: string | null;
  periodType: string | null;
  purchasedAt: Date | null;
  expiresAt: Date | null;
  gracePeriodExpiresAt: Date | null;
  willRenew: boolean | null;
  billingIssueAt: Date | null;
  unsubscribeAt: Date | null;
  refundedAt: Date | null;
  sandbox: boolean;
}

export interface RevenueCatClient {
  /** Returns the named entitlement for the app user, or null if they never had it. */
  entitlement(appUserId: string, entitlementId: string): Promise<SubscriberEntitlement | null>;
}

const date = (v: unknown): Date | null => (typeof v === 'string' && v ? new Date(v) : null);

export class RevenueCatRest implements RevenueCatClient {
  private readonly apiKey: string;
  private readonly origin: string;
  private readonly fetchImpl: typeof fetch;

  constructor(apiKey: string, origin: string, fetchImpl: typeof fetch = fetch) {
    this.apiKey = apiKey;
    this.origin = origin;
    this.fetchImpl = fetchImpl;
  }

  async entitlement(appUserId: string, entitlementId: string): Promise<SubscriberEntitlement | null> {
    const res = await this.fetchImpl(`${this.origin}/v1/subscribers/${encodeURIComponent(appUserId)}`, {
      headers: { authorization: `Bearer ${this.apiKey}`, accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`revenuecat_${res.status}`);
    const json = (await res.json()) as {
      subscriber?: {
        entitlements?: Record<string, { product_identifier?: string; expires_date?: string | null; grace_period_expires_date?: string | null; purchase_date?: string }>;
        subscriptions?: Record<string, { period_type?: string; store?: string; unsubscribe_detected_at?: string | null; billing_issues_detected_at?: string | null; is_sandbox?: boolean; refunded_at?: string | null; expires_date?: string | null }>;
      };
    };
    const ent = json.subscriber?.entitlements?.[entitlementId];
    if (!ent) return null;
    const sub = ent.product_identifier ? json.subscriber?.subscriptions?.[ent.product_identifier] : undefined;
    const unsubscribeAt = date(sub?.unsubscribe_detected_at);
    return {
      productId: ent.product_identifier ?? null,
      store: sub?.store ?? null,
      periodType: sub?.period_type ?? null,
      purchasedAt: date(ent.purchase_date),
      expiresAt: date(ent.expires_date),
      gracePeriodExpiresAt: date(ent.grace_period_expires_date),
      willRenew: sub ? unsubscribeAt === null && !sub.refunded_at : null,
      billingIssueAt: date(sub?.billing_issues_detected_at),
      unsubscribeAt,
      refundedAt: date(sub?.refunded_at),
      sandbox: sub?.is_sandbox === true,
    };
  }
}

/** Test double keyed by app user id. */
export class FakeRevenueCat implements RevenueCatClient {
  readonly subscribers = new Map<string, SubscriberEntitlement>();
  calls = 0;

  async entitlement(appUserId: string): Promise<SubscriberEntitlement | null> {
    this.calls++;
    return this.subscribers.get(appUserId) ?? null;
  }
}
