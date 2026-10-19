import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stringify } from 'yaml';
import { buildOpenApi } from '../src/http/openapi.ts';
import { allRoutes } from '../src/modules/index.ts';

describe('API contract', () => {
  const routes = allRoutes();

  it('every operation has a unique operationId and a unique method+path', () => {
    const ids = routes.map((r) => r.operationId);
    assert.equal(new Set(ids).size, ids.length);
    const keys = routes.map((r) => `${r.method} ${r.url}`);
    assert.equal(new Set(keys).size, keys.length);
  });

  it('only health, config and provider webhooks are reachable without a user token', () => {
    const open = routes.filter((r) => r.auth === 'none' || r.auth === 'webhook').map((r) => r.url).sort();
    assert.deepEqual(open, ['/healthz', '/readyz', '/v1/billing/webhooks/revenuecat', '/v1/config']);
  });

  it('device receipts and phone-affecting writes are device-signed', () => {
    const signed = routes.filter((r) => r.auth === 'device').map((r) => r.operationId).sort();
    for (const op of ['createCommand', 'postCommandReceipt', 'reportAlarmInstallation', 'reportAlarmOccurrence', 'startChallengeSession', 'endChallengeSession', 'postLocationSample', 'updateDevice']) {
      assert.ok(signed.includes(op), op);
    }
  });

  it('every write rejects unknown body fields (except provider webhooks)', () => {
    for (const r of routes) {
      if (!r.body || r.auth === 'webhook') continue;
      assert.equal((r.body as { additionalProperties?: boolean }).additionalProperties, false, r.operationId);
    }
  });

  it('the committed OpenAPI document matches the route definitions', () => {
    const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version: string };
    const expected = `# Generated from services/api route definitions. Do not edit by hand.\n${stringify(buildOpenApi(routes, pkg.version), { lineWidth: 0 })}`;
    const committed = readFileSync(new URL('../../../packages/contracts/openapi.yaml', import.meta.url), 'utf8');
    assert.equal(committed, expected, 'run: npm run generate:openapi');
  });
});
