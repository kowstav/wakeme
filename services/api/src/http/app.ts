import { randomUUID } from 'node:crypto';
import Fastify, { type FastifyInstance, type FastifyRequest } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import type { Deps } from '../deps.ts';
import { userBySubject, type DeviceRow, type UserRow } from '../modules/identity/repo.ts';
import { bearerToken, type AuthClaims } from './auth.ts';
import { verifyDeviceRequest } from './device-auth.ts';
import { AppError, forbidden, unauthorized, type ErrorBody } from './errors.ts';
import type { Ctx, RouteDef } from './route.ts';

declare module 'fastify' {
  interface FastifyRequest {
    rawBody: Buffer;
    wm: { claims: AuthClaims | null; user: UserRow | null; device: DeviceRow | null };
  }
}

/** Routes a suspended or deleting account may still call: read, export, delete, and revoke. */
const INACTIVE_ALLOWED = new Set(['getMe', 'exportAccount', 'deleteAccount', 'revokeGrant', 'listGrants', 'cancelAccountDeletion']);

function safeJsonParse(text: string): unknown {
  return JSON.parse(text, (key, value) => {
    if (key === '__proto__' || key === 'constructor' || key === 'prototype') throw new AppError(400, 'invalid_request', 'Request body is not allowed.');
    return value;
  });
}

export interface AppOptions {
  logger?: boolean;
  trustProxy?: boolean;
}

export async function buildApp(deps: Deps, routes: RouteDef[], opts: AppOptions = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger: opts.logger
      ? {
          level: process.env['LOG_LEVEL'] ?? 'info',
          redact: {
            paths: ['req.headers.authorization', 'req.headers["x-wakeme-signature"]', 'req.headers.cookie'],
            censor: '[redacted]',
          },
        }
      : false,
    genReqId: () => randomUUID(),
    requestIdHeader: false,
    bodyLimit: 64 * 1024,
    trustProxy: opts.trustProxy ?? false,
    ajv: { customOptions: { removeAdditional: false, coerceTypes: 'array', useDefaults: true, allErrors: false } },
  });

  app.decorateRequest('rawBody', null as unknown as Buffer);
  app.decorateRequest('wm', null as unknown as FastifyRequest['wm']);
  app.addHook('onRequest', async (req) => {
    req.wm = { claims: null, user: null, device: null };
    req.rawBody = Buffer.alloc(0);
  });

  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'buffer' }, (req, body, done) => {
    const buf = body as Buffer;
    req.rawBody = buf;
    if (buf.length === 0) return done(null, undefined);
    try {
      done(null, safeJsonParse(buf.toString('utf8')));
    } catch (e) {
      done(e instanceof AppError ? e : new AppError(400, 'invalid_json', 'Request body must be valid JSON.'), undefined);
    }
  });

  app.setErrorHandler((err, req, reply) => {
    const requestId = String(req.id);
    let body: ErrorBody;
    let status: number;
    if (err instanceof AppError) {
      status = err.status;
      body = { error: { code: err.code, message: err.message, retryable: err.retryable, requestId } };
      if (err.retryAfterSeconds !== undefined) {
        body.error.retryAfterSeconds = err.retryAfterSeconds;
        reply.header('retry-after', String(err.retryAfterSeconds));
      }
      if (err.details !== undefined) body.error.details = err.details;
      if (status >= 500) deps.log.error({ requestId, code: err.code }, 'request failed');
    } else if ((err as { validation?: unknown }).validation) {
      status = 400;
      const v = (err as unknown as { validation: { instancePath?: string; message?: string }[]; validationContext?: string }).validation;
      body = {
        error: {
          code: 'invalid_request',
          message: 'Some fields are missing or invalid.',
          retryable: false,
          requestId,
          details: v.slice(0, 10).map((x) => ({ path: x.instancePath ?? '', message: x.message ?? 'invalid' })),
        },
      };
    } else {
      const sc = (err as { statusCode?: number }).statusCode;
      if (sc && sc >= 400 && sc < 500) {
        status = sc;
        body = { error: { code: sc === 413 ? 'payload_too_large' : sc === 415 ? 'unsupported_media_type' : 'bad_request', message: 'The request could not be processed.', retryable: false, requestId } };
      } else {
        status = 500;
        deps.log.error({ requestId, err: { name: (err as Error).name, message: (err as Error).message } }, 'unhandled error');
        body = { error: { code: 'internal_error', message: 'Something went wrong on our side. Personal alarms on your phone are unaffected.', retryable: true, requestId } };
      }
    }
    reply.code(status).send(body);
  });

  app.setNotFoundHandler((req, reply) => {
    reply.code(404).send({ error: { code: 'route_not_found', message: 'Not found.', retryable: false, requestId: String(req.id) } } satisfies ErrorBody);
  });

  app.addHook('onSend', async (_req, reply) => {
    reply.header('cache-control', 'no-store');
    reply.header('x-content-type-options', 'nosniff');
    reply.header('referrer-policy', 'no-referrer');
    reply.header('x-frame-options', 'DENY');
  });

  await app.register(rateLimit, {
    global: true,
    max: 600,
    timeWindow: '1 minute',
    hook: 'preValidation',
    keyGenerator: (req) => (req.wm?.user ? `u:${req.wm.user.id}` : `ip:${req.ip}`),
    errorResponseBuilder: (_req, ctx) =>
      new AppError(429, 'rate_limited', 'Too many requests. Try again shortly.', { retryAfterSeconds: Math.max(1, Math.ceil(ctx.ttl / 1000)) }),
  });

  for (const r of routes) {
    app.route({
      method: r.method,
      url: r.url,
      schema: {
        ...(r.params ? { params: r.params } : {}),
        ...(r.query ? { querystring: r.query } : {}),
        ...(r.body ? { body: r.body } : {}),
        response: { [r.status]: r.response },
      },
      config: r.rateLimit ? { rateLimit: r.rateLimit } : {},
      onRequest: async (req) => {
        if (r.auth === 'none' || r.auth === 'webhook') return;
        const claims = await deps.verifyToken(bearerToken(req.headers.authorization));
        if (claims.is_anonymous) throw unauthorized('account_required', 'Create an account to use friends and cloud features.');
        req.wm.claims = claims;
        if (r.auth === 'subject') return;
        const user = await userBySubject(deps.db, claims.sub);
        if (!user) throw new AppError(403, 'profile_required', 'Finish creating your account first.');
        if (user.account_state !== 'active' && !INACTIVE_ALLOWED.has(r.operationId)) {
          throw forbidden(user.account_state === 'suspended' ? 'account_suspended' : 'account_deleting', user.account_state === 'suspended' ? 'This account is suspended. Contact support.' : 'This account is being deleted.');
        }
        req.wm.user = user;
      },
      preHandler: async (req) => {
        if (r.auth === 'device') {
          req.wm.device = await verifyDeviceRequest(deps.db, req.wm.user!, { method: req.method, url: req.url, headers: req.headers, rawBody: req.rawBody }, deps.now());
        } else if (r.auth === 'admin') {
          const claims = req.wm.claims!;
          const role = claims.app_metadata?.wakeme_role;
          const allowed = (role === 'moderator' || role === 'operator') && claims.aal === 'aal2' && deps.config.adminUserIds.includes(req.wm.user!.id);
          if (!allowed) throw new AppError(404, 'route_not_found', 'Not found.');
        }
      },
      handler: async (req, reply) => {
        const wm = req.wm;
        const ctx: Ctx<unknown, unknown, unknown> = {
          params: req.params,
          query: req.query,
          body: req.body,
          get user(): UserRow {
            if (!wm.user) throw unauthorized();
            return wm.user;
          },
          device: wm.device,
          claims: wm.claims,
          deps,
          now: deps.now(),
          req,
          reply,
          requestId: String(req.id),
        };
        const result = await r.handler(ctx);
        reply.code(r.status);
        return result;
      },
    });
  }

  return app;
}
