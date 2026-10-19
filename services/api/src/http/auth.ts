import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from 'jose';
import { unauthorized } from './errors.ts';

export interface AuthClaims extends JWTPayload {
  sub: string;
  role?: string;
  aal?: string;
  amr?: { method: string; timestamp: number }[];
  is_anonymous?: boolean;
  session_id?: string;
  app_metadata?: { wakeme_role?: string };
}

export type TokenVerifier = (token: string) => Promise<AuthClaims>;

/**
 * Verifies Supabase Auth (or any OIDC-style) access tokens against the
 * provider's JWKS. Signature, issuer, audience and expiry are all checked
 * server-side; client-submitted profile data is never trusted as identity.
 * jose caches the key set and refetches on unknown `kid`, handling rotation.
 */
export function jwksVerifier(opts: { jwksUrl: string; issuer: string; audience: string }, keySet?: JWTVerifyGetKey): TokenVerifier {
  const jwks = keySet ?? createRemoteJWKSet(new URL(opts.jwksUrl), { cooldownDuration: 30_000, cacheMaxAge: 10 * 60_000 });
  return async (token: string) => {
    try {
      const { payload } = await jwtVerify(token, jwks, {
        issuer: opts.issuer,
        audience: opts.audience,
        algorithms: ['ES256', 'RS256', 'EdDSA'],
        clockTolerance: 30,
      });
      if (typeof payload.sub !== 'string' || payload.sub.length === 0) throw new Error('missing sub');
      return payload as AuthClaims;
    } catch {
      throw unauthorized('invalid_token', 'Your session has expired. Sign in again.');
    }
  };
}

/** Seconds since the user last actively authenticated (Supabase `amr`), or null if unknown. */
export function secondsSinceAuthentication(claims: AuthClaims, now: Date): number | null {
  const stamps = (claims.amr ?? []).map((a) => a.timestamp).filter((t) => Number.isFinite(t));
  const authTime = typeof claims['auth_time'] === 'number' ? (claims['auth_time'] as number) : undefined;
  if (authTime !== undefined) stamps.push(authTime);
  if (stamps.length === 0) return null;
  return Math.floor(now.getTime() / 1000) - Math.max(...stamps);
}

export function requireRecentAuth(claims: AuthClaims, now: Date, maxAgeSeconds: number): void {
  const age = secondsSinceAuthentication(claims, now);
  if (age === null || age > maxAgeSeconds) {
    throw unauthorized('reauth_required', 'For your safety, sign in again to confirm this change.');
  }
}

export function bearerToken(header: string | undefined): string {
  if (!header) throw unauthorized('missing_token', 'Sign in to continue.');
  const m = /^Bearer ([A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_.+/=]*)$/.exec(header);
  if (!m) throw unauthorized('invalid_token', 'Sign in to continue.');
  return m[1]!;
}
