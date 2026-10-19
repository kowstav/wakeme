// Delivery state is multidimensional. Events arrive late and out of order, so
// the state is reduced by precedence, never by "last event wins". Provider
// acceptance is not proof that a phone rang or that a person woke up.

export type CommandLifecycle = 'accepted' | 'expired' | 'cancelled';
export type TransportState = 'queued' | 'provider_accepted' | 'provider_rejected' | 'not_sent';
export type DeviceState = 'unknown' | 'received' | 'installed' | 'execution_reported';
export type HumanState = 'none' | 'opened' | 'acknowledged' | 'challenge_completed' | 'bypassed';

export type DeliveryEventKind =
  | 'accepted'
  | 'expired'
  | 'cancelled'
  | 'queued'
  | 'provider_accepted'
  | 'provider_rejected'
  | 'not_sent'
  | 'received'
  | 'installed'
  | 'execution_reported'
  | 'execution_refused'
  | 'opened'
  | 'acknowledged'
  | 'challenge_completed'
  | 'bypassed';

export const DELIVERY_EVENT_KINDS: readonly DeliveryEventKind[] = [
  'accepted',
  'expired',
  'cancelled',
  'queued',
  'provider_accepted',
  'provider_rejected',
  'not_sent',
  'received',
  'installed',
  'execution_reported',
  'execution_refused',
  'opened',
  'acknowledged',
  'challenge_completed',
  'bypassed',
];

/** Event kinds a recipient device may report. */
export const DEVICE_RECEIPT_KINDS: readonly DeliveryEventKind[] = [
  'received',
  'installed',
  'execution_reported',
  'execution_refused',
  'opened',
  'acknowledged',
  'challenge_completed',
  'bypassed',
];

export interface DeliveryEvent {
  kind: DeliveryEventKind;
  /** Server sequence number; used to order ties and pick the first terminal outcome. */
  seq: number;
  reason?: string;
}

export interface DeliveryState {
  lifecycle: CommandLifecycle;
  transport: TransportState;
  device: DeviceState;
  human: HumanState;
  refusedReason: string | null;
}

const TRANSPORT_RANK: Record<TransportState, number> = { queued: 1, not_sent: 2, provider_rejected: 2, provider_accepted: 3 };
const DEVICE_RANK: Record<DeviceState, number> = { unknown: 0, received: 1, installed: 2, execution_reported: 3 };
const HUMAN_RANK: Record<HumanState, number> = { none: 0, opened: 1, acknowledged: 2, challenge_completed: 3, bypassed: 3 };

export function initialDeliveryState(): DeliveryState {
  return { lifecycle: 'accepted', transport: 'queued', device: 'unknown', human: 'none', refusedReason: null };
}

export function reduceDelivery(events: readonly DeliveryEvent[]): DeliveryState {
  const sorted = [...events].sort((a, b) => a.seq - b.seq);
  const s = initialDeliveryState();
  let transportSeen = false;
  for (const e of sorted) {
    switch (e.kind) {
      case 'accepted':
        break;
      case 'expired':
      case 'cancelled':
        // First terminal lifecycle outcome wins.
        if (s.lifecycle === 'accepted') s.lifecycle = e.kind;
        break;
      case 'queued':
      case 'provider_accepted':
      case 'provider_rejected':
      case 'not_sent':
        if (!transportSeen || TRANSPORT_RANK[e.kind] > TRANSPORT_RANK[s.transport]) s.transport = e.kind;
        transportSeen = true;
        break;
      case 'received':
      case 'installed':
      case 'execution_reported':
        if (DEVICE_RANK[e.kind] > DEVICE_RANK[s.device]) s.device = e.kind;
        break;
      case 'execution_refused':
        if (s.refusedReason === null) s.refusedReason = e.reason ?? 'refused';
        if (DEVICE_RANK.received > DEVICE_RANK[s.device]) s.device = 'received';
        break;
      case 'opened':
      case 'acknowledged':
      case 'challenge_completed':
      case 'bypassed':
        // Terminal human outcomes (completed / bypassed) are mutually exclusive; keep the first.
        if (HUMAN_RANK[s.human] === 3) break;
        if (HUMAN_RANK[e.kind] > HUMAN_RANK[s.human]) s.human = e.kind;
        break;
    }
  }
  // Any human interaction implies the phone received the command.
  if (s.human !== 'none' && s.device === 'unknown') s.device = 'received';
  return s;
}

export type DisplayStatus =
  | 'sending'
  | 'sent'
  | 'no_confirmation_yet'
  | 'not_delivered'
  | 'phone_received'
  | 'alarm_scheduled_on_phone'
  | 'alarm_started'
  | 'not_played'
  | 'opened'
  | 'acknowledged'
  | 'challenge_completed'
  | 'stopped'
  | 'ended_without_challenge'
  | 'expired'
  | 'cancelled';

export interface DisplayOptions {
  now: Date;
  sentAt: Date;
  kind: 'nudge' | 'alarm' | 'other';
  /** Recipient chose to share bypass outcomes with helpers. */
  shareBypass: boolean;
  /** Seconds after provider acceptance before showing "No confirmation yet". */
  confirmationGraceSeconds?: number;
}

/**
 * Literal, evidence-based sender-facing status. "Phone received" is not "They
 * heard it"; "Challenge completed" is not "Guaranteed awake".
 */
export function displayStatus(state: DeliveryState, opts: DisplayOptions): DisplayStatus {
  switch (state.human) {
    case 'challenge_completed':
      return 'challenge_completed';
    case 'bypassed':
      return opts.shareBypass ? 'ended_without_challenge' : 'stopped';
    case 'acknowledged':
      return 'acknowledged';
    case 'opened':
      return 'opened';
    case 'none':
      break;
  }
  if (state.refusedReason !== null && state.device !== 'execution_reported') return 'not_played';
  if (state.device === 'execution_reported') return opts.kind === 'alarm' ? 'alarm_started' : 'phone_received';
  if (state.device === 'installed') return opts.kind === 'alarm' ? 'alarm_scheduled_on_phone' : 'phone_received';
  if (state.device === 'received') return 'phone_received';
  if (state.lifecycle === 'cancelled') return 'cancelled';
  if (state.lifecycle === 'expired') return state.transport === 'provider_rejected' || state.transport === 'not_sent' ? 'not_delivered' : 'expired';
  switch (state.transport) {
    case 'queued':
      return 'sending';
    case 'provider_rejected':
    case 'not_sent':
      return 'not_delivered';
    case 'provider_accepted': {
      const grace = (opts.confirmationGraceSeconds ?? 60) * 1000;
      return opts.now.getTime() - opts.sentAt.getTime() < grace ? 'sent' : 'no_confirmation_yet';
    }
  }
}
