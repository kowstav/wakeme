import Type from 'typebox';
import { BUNDLED_SOUNDS, CAPABILITIES, EXPIRY_BOUNDS, GRANT_BOUNDS, GLOBAL_LIMITS } from '@wakeme/policy';
import { publicKeyDerBase64 } from '@wakeme/challenges';
import { unavailable } from '../../http/errors.ts';
import { route, type RouteDef } from '../../http/route.ts';
import { soundFileName } from '../../push/gateway.ts';

export const operationsRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/healthz',
    operationId: 'healthz',
    summary: 'Liveness.',
    tags: ['operations'],
    auth: 'none',
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async () => ({ ok: true }),
  }),

  route({
    method: 'GET',
    url: '/readyz',
    operationId: 'readyz',
    summary: 'Readiness (database reachable).',
    tags: ['operations'],
    auth: 'none',
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ deps }) => {
      try {
        await deps.db.query('select 1');
        return { ok: true };
      } catch {
        throw unavailable('not_ready', 'Not ready.');
      }
    },
  }),

  route({
    method: 'GET',
    url: '/v1/config',
    operationId: 'publicConfig',
    summary: 'Client configuration: challenge signing keys, bundled sounds, limits and which remote features are on.',
    tags: ['operations'],
    auth: 'none',
    response: Type.Object({
      apiVersion: Type.String(),
      challengeSigningKeys: Type.Array(Type.Object({ keyId: Type.String(), alg: Type.String(), spki: Type.String() })),
      bundledSounds: Type.Array(Type.Object({ id: Type.String(), file: Type.String() })),
      capabilities: Type.Array(Type.String()),
      features: Type.Record(Type.String(), Type.Boolean()),
      limits: Type.Unknown(),
      legal: Type.Object({ privacyUrl: Type.String(), termsUrl: Type.String(), supportEmail: Type.String() }),
    }),
    handler: async ({ deps }) => {
      const disabled = await deps.flags.disabled();
      const keys = ['remote_dispatch.nudge', 'remote_dispatch.alarm', 'focus_requests', 'safety_requests', 'location', 'ai_generation', 'media_uploads'];
      return {
        apiVersion: '1',
        challengeSigningKeys: [{ keyId: deps.config.challengeSigning.keyId, alg: 'ES256', spki: publicKeyDerBase64(deps.challengeKey) }],
        bundledSounds: BUNDLED_SOUNDS.map((id) => ({ id, file: soundFileName(id) })),
        capabilities: [...CAPABILITIES],
        features: Object.fromEntries(keys.map((k) => [k, !disabled.has(k)])),
        limits: { grant: GRANT_BOUNDS, expiry: EXPIRY_BOUNDS, global: GLOBAL_LIMITS },
        legal: { privacyUrl: 'https://wakeme.app/privacy', termsUrl: 'https://wakeme.app/terms', supportEmail: 'support@wakeme.app' },
      };
    },
  }),
];
