import Type from 'typebox';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Deps } from '../deps.ts';
import type { AuthClaims } from './auth.ts';
import type { DeviceRow, UserRow } from '../modules/identity/repo.ts';

/**
 * none      - public
 * subject   - valid token, profile may not exist yet (account creation)
 * user      - valid token + active profile
 * device    - user + request signed by one of the user's registered devices
 * admin     - user + moderator/operator role + MFA + server allowlist
 * webhook   - provider-authenticated callback (checked by the handler)
 */
export type AuthMode = 'none' | 'subject' | 'user' | 'device' | 'admin' | 'webhook';

export interface Ctx<P, Q, B> {
  params: P;
  query: Q;
  body: B;
  readonly user: UserRow;
  device: DeviceRow | null;
  claims: AuthClaims | null;
  deps: Deps;
  now: Date;
  req: FastifyRequest;
  reply: FastifyReply;
  requestId: string;
}

export interface RouteDef {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  url: string;
  operationId: string;
  summary: string;
  description?: string;
  tags: string[];
  auth: AuthMode;
  params?: Type.TSchema;
  query?: Type.TSchema;
  body?: Type.TSchema;
  headers?: Type.TSchema;
  response: Type.TSchema;
  status: number;
  rateLimit?: { max: number; timeWindow: string };
  handler: (ctx: Ctx<any, any, any>) => Promise<unknown>;
}

type StaticOr<T, D> = T extends Type.TSchema ? Type.Static<T> : D;

export function route<
  P extends Type.TSchema | undefined = undefined,
  Q extends Type.TSchema | undefined = undefined,
  B extends Type.TSchema | undefined = undefined,
  R extends Type.TSchema = Type.TSchema,
>(def: {
  method: RouteDef['method'];
  url: string;
  operationId: string;
  summary: string;
  description?: string;
  tags: string[];
  auth: AuthMode;
  params?: P;
  query?: Q;
  body?: B;
  headers?: Type.TSchema;
  response: R;
  status?: number;
  rateLimit?: { max: number; timeWindow: string };
  handler: (ctx: Ctx<StaticOr<P, Record<string, never>>, StaticOr<Q, Record<string, never>>, StaticOr<B, undefined>>) => Promise<Type.Static<R>>;
}): RouteDef {
  return { ...def, status: def.status ?? 200, handler: def.handler as RouteDef['handler'] } as RouteDef;
}

// ---- schema helpers -------------------------------------------------------

/** Request objects reject unknown properties (no silent stripping). */
export function Obj<T extends Type.TProperties>(props: T, opts: { description?: string } = {}) {
  return Type.Object(props, { additionalProperties: false, ...opts });
}

export const Uuid = Type.String({ format: 'uuid' });
export const Instant = Type.String({ format: 'date-time' });
export const HhMm = Type.String({ pattern: '^([01]\\d|2[0-3]):[0-5]\\d$' });
export const Ok = Type.Object({ ok: Type.Boolean() });
export const IdParams = Obj({ id: Uuid });

export function Nullable<T extends Type.TSchema>(t: T) {
  return Type.Union([t, Type.Null()]);
}

export function iso(d: Date | string | null | undefined): string | null {
  if (d === null || d === undefined) return null;
  return (d instanceof Date ? d : new Date(d)).toISOString();
}

export function isoReq(d: Date | string): string {
  return (d instanceof Date ? d : new Date(d)).toISOString();
}
