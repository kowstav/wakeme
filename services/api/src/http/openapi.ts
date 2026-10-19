import type { RouteDef } from './route.ts';

const ErrorSchema = {
  type: 'object',
  required: ['error'],
  properties: {
    error: {
      type: 'object',
      required: ['code', 'message', 'retryable', 'requestId'],
      properties: {
        code: { type: 'string' },
        message: { type: 'string', description: 'Safe to show to the user.' },
        retryable: { type: 'boolean' },
        requestId: { type: 'string' },
        retryAfterSeconds: { type: 'integer' },
        details: {},
      },
    },
  },
};

function strip(schema: unknown): unknown {
  // TypeBox emits plain JSON Schema; drop undefined values for stable output.
  return JSON.parse(JSON.stringify(schema));
}

/** OpenAPI 3.1 document generated from the same route definitions the server uses. */
export function buildOpenApi(routes: RouteDef[], version: string): Record<string, unknown> {
  const paths: Record<string, Record<string, unknown>> = {};
  for (const r of [...routes].sort((a, b) => a.url.localeCompare(b.url) || a.method.localeCompare(b.method))) {
    const path = r.url.replace(/:([A-Za-z]+)/g, '{$1}');
    const op: Record<string, unknown> = {
      operationId: r.operationId,
      summary: r.summary,
      tags: r.tags,
      'x-wakeme-auth': r.auth,
    };
    if (r.description) op['description'] = r.description;
    const params: unknown[] = [];
    const p = r.params as { properties?: Record<string, unknown> } | undefined;
    for (const [name, schema] of Object.entries(p?.properties ?? {})) params.push({ name, in: 'path', required: true, schema: strip(schema) });
    const qs = r.query as { properties?: Record<string, unknown>; required?: string[] } | undefined;
    for (const [name, schema] of Object.entries(qs?.properties ?? {})) params.push({ name, in: 'query', required: (qs?.required ?? []).includes(name), schema: strip(schema) });
    if (r.auth === 'device') {
      params.push(
        { name: 'X-WakeMe-Device', in: 'header', required: true, schema: { type: 'string', format: 'uuid' } },
        { name: 'X-WakeMe-Timestamp', in: 'header', required: true, schema: { type: 'string', description: 'Unix seconds' } },
        { name: 'X-WakeMe-Signature', in: 'header', required: true, schema: { type: 'string', description: 'base64 DER ECDSA P-256/SHA-256 over WAKEME-V1\\nMETHOD\\nPATH?QUERY\\nTIMESTAMP\\nSHA256HEX(BODY)' } },
      );
    }
    if (params.length) op['parameters'] = params;
    if (r.body) op['requestBody'] = { required: true, content: { 'application/json': { schema: strip(r.body) } } };
    op['responses'] = {
      [String(r.status)]: { description: 'Success', content: { 'application/json': { schema: strip(r.response) } } },
      default: { description: 'Error', content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } } },
    };
    if (r.auth === 'none' || r.auth === 'webhook') op['security'] = [];
    else op['security'] = [{ bearer: [] }];
    paths[path] = { ...(paths[path] ?? {}), [r.method.toLowerCase()]: op };
  }
  return {
    openapi: '3.1.0',
    info: {
      title: 'Wake Me API',
      version,
      description:
        'Alert your friends. Wake each other up. All remote actions are authorized against recipient-owned, directional, bounded grants. Success responses report acceptance and recorded evidence; they never claim a phone rang or a person woke up.',
    },
    servers: [{ url: 'https://api.wakeme.app' }],
    components: {
      securitySchemes: { bearer: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT (Supabase Auth access token)' } },
      schemas: { Error: ErrorSchema },
    },
    paths,
  };
}
