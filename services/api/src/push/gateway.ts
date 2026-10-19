// Push is a best-effort hint, never proof of delivery. Payloads carry opaque
// ids and harmless display text only: no message bodies, quiz content or
// coordinates. Provider acceptance is recorded as such, not as "ringing".

export interface PushTarget {
  deviceId: string;
  platform: 'ios' | 'android';
  provider: 'apns' | 'fcm';
  token: string;
  environment: 'sandbox' | 'production' | null;
}

export interface PushAlert {
  title: string;
  body: string;
  /** Localization keys rendered by the app bundle (iOS Localizable.strings / Android resources). */
  titleLocKey?: string;
  locKey?: string;
  locArgs?: string[];
  /** Bundled sound file name, or null for silent. */
  sound: string | null;
  interruption: 'passive' | 'active' | 'time-sensitive';
  category?: string;
  threadId?: string;
}

export interface PushMessage {
  priority: 'high' | 'normal';
  /** Provider TTL. A nudge must never be delivered after this. */
  expiresAt: Date | null;
  collapseId?: string;
  /** Rendered by APNs on iOS. Android always receives data and renders locally after re-validation. */
  alert?: PushAlert;
  /** content-available background update (iOS) / normal data message (Android). */
  background?: boolean;
  /** Opaque string fields, e.g. { t: 'command', id: '...' }. */
  data: Record<string, string>;
}

export type PushResult =
  | { status: 'accepted'; providerId?: string }
  | { status: 'rejected'; reason: string; tokenInvalid: boolean }
  | { status: 'retry'; reason: string; retryAfterSeconds?: number };

export interface PushGateway {
  send(target: PushTarget, message: PushMessage): Promise<PushResult>;
}

export interface ProviderClient {
  send(target: PushTarget, message: PushMessage): Promise<PushResult>;
}

export class RoutingPushGateway implements PushGateway {
  private readonly apns: ProviderClient | null;
  private readonly fcm: ProviderClient | null;

  constructor(apns: ProviderClient | null, fcm: ProviderClient | null) {
    this.apns = apns;
    this.fcm = fcm;
  }

  async send(target: PushTarget, message: PushMessage): Promise<PushResult> {
    const client = target.provider === 'apns' ? this.apns : this.fcm;
    if (!client) return { status: 'rejected', reason: 'provider_not_configured', tokenInvalid: false };
    if (message.expiresAt && message.expiresAt.getTime() <= Date.now()) return { status: 'rejected', reason: 'expired_before_send', tokenInvalid: false };
    try {
      return await client.send(target, message);
    } catch (e) {
      return { status: 'retry', reason: e instanceof Error ? e.name : 'transport_error' };
    }
  }
}

/** Test/dev gateway: records messages and returns scripted results. */
export class RecordingPushGateway implements PushGateway {
  readonly sent: { target: PushTarget; message: PushMessage }[] = [];
  next: PushResult[] = [];

  async send(target: PushTarget, message: PushMessage): Promise<PushResult> {
    this.sent.push({ target, message });
    return this.next.shift() ?? { status: 'accepted', providerId: `fake-${this.sent.length}` };
  }

  clear(): void {
    this.sent.length = 0;
    this.next = [];
  }
}

/** Maps bundled sound ids to the file names shipped in both apps. */
export function soundFileName(soundId: string): string {
  if (soundId.startsWith('bundled:')) return `${soundId.slice('bundled:'.length).replace(/\./g, '_')}.wav`;
  return 'default';
}
