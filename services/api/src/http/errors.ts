// Structured errors: stable `code`, safe user-facing `message`, `retryable`,
// and the request id. Status semantics follow the engineering blueprint:
// 401 auth, 403 known denial, 404 to avoid leaking unrelated objects,
// 409 versions/state, 422 invalid content, 429 quotas, 503 unavailable.

export interface ErrorBody {
  error: {
    code: string;
    message: string;
    retryable: boolean;
    requestId: string;
    retryAfterSeconds?: number;
    details?: unknown;
  };
}

export class AppError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryable: boolean;
  readonly retryAfterSeconds: number | undefined;
  readonly details: unknown;

  constructor(status: number, code: string, message: string, opts: { retryable?: boolean; retryAfterSeconds?: number; details?: unknown } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = opts.retryable ?? (status === 429 || status === 503);
    this.retryAfterSeconds = opts.retryAfterSeconds;
    this.details = opts.details;
  }
}

export const notFound = (what = 'resource'): AppError => new AppError(404, 'not_found', `That ${what} was not found.`);
export const forbidden = (code: string, message: string): AppError => new AppError(403, code, message);
export const conflict = (code: string, message: string, details?: unknown): AppError => new AppError(409, code, message, { details });
export const unprocessable = (code: string, message: string, details?: unknown): AppError => new AppError(422, code, message, { details });
export const unauthorized = (code = 'unauthenticated', message = 'Sign in again to continue.'): AppError => new AppError(401, code, message);
export const unavailable = (code: string, message: string): AppError => new AppError(503, code, message, { retryable: true });
export const tooMany = (code: string, message: string, retryAfterSeconds?: number): AppError =>
  new AppError(429, code, message, retryAfterSeconds === undefined ? { retryable: true } : { retryable: true, retryAfterSeconds });

/** User-facing copy for policy denials. Literal, never blaming, never leaking private settings. */
export const DENY_MESSAGES: Record<string, string> = {
  self_target: "You can't send that to yourself.",
  not_found: 'That friend was not found.',
  actor_inactive: "Your account can't send requests right now.",
  feature_disabled: 'This is temporarily unavailable. Personal alarms still work.',
  capability_not_granted: "They haven't turned this on for you. You can ask them to.",
  grant_changed: 'Their permissions just changed. Refresh to see what you can send.',
  grant_paused: 'They have paused requests from you for now.',
  invalid_expiry: 'That request has an invalid expiry.',
  sound_not_allowed: "That sound isn't in the set they approved.",
  duration_exceeds_limit: "That's longer than they allow.",
  challenge_not_allowed: "That challenge isn't within what they accepted.",
  recipient_paused: "They're not taking requests right now.",
  outside_allowed_window: "It's outside the hours they chose.",
  quiet_hours: "It's their quiet time.",
  rate_limited_sender: "You've reached the limit they set for now.",
  daily_limit_reached: "You've reached today's limit for this.",
  rate_limited_recipient: "They've had enough alerts for now. Try again later.",
  rate_limited_global: "You're sending a lot right now. Take a break and try later.",
  recipient_not_ready: "Their phone isn't set up for this yet.",
};
