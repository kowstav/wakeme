import { createHash, createHmac } from 'node:crypto';
import type { S3Config } from '../config.ts';

// Private media storage (voices, alarm sounds). Buckets are never public:
// uploads and downloads use short-lived SigV4 presigned URLs. This minimal
// signer avoids a large SDK; it is verified against AWS's published example.

export interface PresignedRequest {
  url: string;
  method: 'PUT' | 'GET';
  headers: Record<string, string>;
  expiresAt: Date;
}

export interface ObjectStorage {
  presignPut(key: string, opts: { contentType: string; contentLength: number; expiresSeconds: number; now?: Date }): PresignedRequest;
  presignGet(key: string, opts: { expiresSeconds: number; now?: Date }): PresignedRequest;
  /** Returns null when missing; throws when larger than maxBytes. */
  getObject(key: string, maxBytes: number): Promise<Buffer | null>;
  deleteObject(key: string): Promise<void>;
}

const sha256hex = (data: string | Buffer) => createHash('sha256').update(data).digest('hex');
const hmac = (key: Buffer | string, data: string) => createHmac('sha256', key).update(data, 'utf8').digest();

/** RFC 3986 encoding as required by SigV4 (encodeURIComponent leaves !'()* unescaped). */
export function uriEncode(value: string, encodeSlash = true): string {
  let out = encodeURIComponent(value).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
  if (!encodeSlash) out = out.replace(/%2F/g, '/');
  return out;
}

function amzDate(d: Date): { amz: string; day: string } {
  const amz = d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  return { amz, day: amz.slice(0, 8) };
}

export interface SigV4Params {
  method: string;
  url: URL;
  region: string;
  service: string;
  accessKeyId: string;
  secretAccessKey: string;
  now: Date;
  headers: Record<string, string>;
}

export function signingKey(secret: string, day: string, region: string, service: string): Buffer {
  return hmac(hmac(hmac(hmac(`AWS4${secret}`, day), region), service), 'aws4_request');
}

function canonicalQuery(params: URLSearchParams): string {
  return [...params.entries()]
    .map(([k, v]) => [uriEncode(k), uriEncode(v)] as const)
    .sort(([a, av], [b, bv]) => (a < b ? -1 : a > b ? 1 : av < bv ? -1 : av > bv ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join('&');
}

function canonicalHeaders(headers: Record<string, string>): { canonical: string; signed: string } {
  const entries = Object.entries(headers)
    .map(([k, v]) => [k.toLowerCase(), v.trim().replace(/\s+/g, ' ')] as const)
    .sort(([a], [b]) => (a < b ? -1 : 1));
  return { canonical: entries.map(([k, v]) => `${k}:${v}\n`).join(''), signed: entries.map(([k]) => k).join(';') };
}

/** Presigned URL (query-string auth). Signed headers always include host. */
export function presign(p: SigV4Params & { expiresSeconds: number }): string {
  const { amz, day } = amzDate(p.now);
  const scope = `${day}/${p.region}/${p.service}/aws4_request`;
  const headers = { host: p.url.host, ...p.headers };
  const { canonical, signed } = canonicalHeaders(headers);
  const url = new URL(p.url.toString());
  url.searchParams.set('X-Amz-Algorithm', 'AWS4-HMAC-SHA256');
  url.searchParams.set('X-Amz-Credential', `${p.accessKeyId}/${scope}`);
  url.searchParams.set('X-Amz-Date', amz);
  url.searchParams.set('X-Amz-Expires', String(p.expiresSeconds));
  url.searchParams.set('X-Amz-SignedHeaders', signed);
  const canonicalRequest = [p.method, uriEncode(decodeURIComponent(url.pathname), false), canonicalQuery(url.searchParams), canonical, signed, 'UNSIGNED-PAYLOAD'].join('\n');
  const stringToSign = ['AWS4-HMAC-SHA256', amz, scope, sha256hex(canonicalRequest)].join('\n');
  const signature = createHmac('sha256', signingKey(p.secretAccessKey, day, p.region, p.service)).update(stringToSign).digest('hex');
  return `${url.origin}${uriEncode(decodeURIComponent(url.pathname), false)}?${canonicalQuery(url.searchParams)}&X-Amz-Signature=${signature}`;
}

/** Header-authenticated request for server-side calls. */
export function signHeaders(p: SigV4Params & { payloadHash: string }): Record<string, string> {
  const { amz, day } = amzDate(p.now);
  const scope = `${day}/${p.region}/${p.service}/aws4_request`;
  const headers: Record<string, string> = { host: p.url.host, 'x-amz-content-sha256': p.payloadHash, 'x-amz-date': amz, ...p.headers };
  const { canonical, signed } = canonicalHeaders(headers);
  const canonicalRequest = [p.method, uriEncode(decodeURIComponent(p.url.pathname), false), canonicalQuery(p.url.searchParams), canonical, signed, p.payloadHash].join('\n');
  const stringToSign = ['AWS4-HMAC-SHA256', amz, scope, sha256hex(canonicalRequest)].join('\n');
  const signature = createHmac('sha256', signingKey(p.secretAccessKey, day, p.region, p.service)).update(stringToSign).digest('hex');
  const { host: _host, ...rest } = headers;
  return { ...rest, authorization: `AWS4-HMAC-SHA256 Credential=${p.accessKeyId}/${scope}, SignedHeaders=${signed}, Signature=${signature}` };
}

export class S3Storage implements ObjectStorage {
  private readonly cfg: S3Config;
  private readonly fetchImpl: typeof fetch;

  constructor(cfg: S3Config, fetchImpl: typeof fetch = fetch) {
    this.cfg = cfg;
    this.fetchImpl = fetchImpl;
  }

  private objectUrl(key: string): URL {
    if (!/^[a-zA-Z0-9/_.-]{1,512}$/.test(key) || key.includes('..')) throw new Error('invalid object key');
    const base = new URL(this.cfg.endpoint);
    if (this.cfg.forcePathStyle) return new URL(`${base.origin}${base.pathname.replace(/\/$/, '')}/${this.cfg.bucket}/${key}`);
    return new URL(`${base.protocol}//${this.cfg.bucket}.${base.host}/${key}`);
  }

  presignPut(key: string, opts: { contentType: string; contentLength: number; expiresSeconds: number; now?: Date }): PresignedRequest {
    const now = opts.now ?? new Date();
    const headers = { 'content-type': opts.contentType, 'content-length': String(opts.contentLength) };
    const url = presign({ method: 'PUT', url: this.objectUrl(key), region: this.cfg.region, service: 's3', accessKeyId: this.cfg.accessKeyId, secretAccessKey: this.cfg.secretAccessKey, now, headers, expiresSeconds: opts.expiresSeconds });
    return { url, method: 'PUT', headers, expiresAt: new Date(now.getTime() + opts.expiresSeconds * 1000) };
  }

  presignGet(key: string, opts: { expiresSeconds: number; now?: Date }): PresignedRequest {
    const now = opts.now ?? new Date();
    const url = presign({ method: 'GET', url: this.objectUrl(key), region: this.cfg.region, service: 's3', accessKeyId: this.cfg.accessKeyId, secretAccessKey: this.cfg.secretAccessKey, now, headers: {}, expiresSeconds: opts.expiresSeconds });
    return { url, method: 'GET', headers: {}, expiresAt: new Date(now.getTime() + opts.expiresSeconds * 1000) };
  }

  private async request(method: 'GET' | 'DELETE', key: string): Promise<Response> {
    const url = this.objectUrl(key);
    const headers = signHeaders({ method, url, region: this.cfg.region, service: 's3', accessKeyId: this.cfg.accessKeyId, secretAccessKey: this.cfg.secretAccessKey, now: new Date(), headers: {}, payloadHash: sha256hex('') });
    return this.fetchImpl(url, { method, headers, signal: AbortSignal.timeout(15_000), redirect: 'error' });
  }

  async getObject(key: string, maxBytes: number): Promise<Buffer | null> {
    const res = await this.request('GET', key);
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`storage_get_${res.status}`);
    const declared = Number(res.headers.get('content-length') ?? '0');
    if (declared > maxBytes) throw new Error('object_too_large');
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length > maxBytes) throw new Error('object_too_large');
    return buf;
  }

  async deleteObject(key: string): Promise<void> {
    const res = await this.request('DELETE', key);
    if (!res.ok && res.status !== 404) throw new Error(`storage_delete_${res.status}`);
  }
}

/** Development/test storage. Never used when S3 is configured. */
export class MemoryStorage implements ObjectStorage {
  readonly objects = new Map<string, Buffer>();

  presignPut(key: string, opts: { contentType: string; contentLength: number; expiresSeconds: number; now?: Date }): PresignedRequest {
    const now = opts.now ?? new Date();
    return { url: `memory://${key}`, method: 'PUT', headers: { 'content-type': opts.contentType, 'content-length': String(opts.contentLength) }, expiresAt: new Date(now.getTime() + opts.expiresSeconds * 1000) };
  }

  presignGet(key: string, opts: { expiresSeconds: number; now?: Date }): PresignedRequest {
    const now = opts.now ?? new Date();
    return { url: `memory://${key}`, method: 'GET', headers: {}, expiresAt: new Date(now.getTime() + opts.expiresSeconds * 1000) };
  }

  async getObject(key: string, maxBytes: number): Promise<Buffer | null> {
    const b = this.objects.get(key);
    if (!b) return null;
    if (b.length > maxBytes) throw new Error('object_too_large');
    return b;
  }

  async deleteObject(key: string): Promise<void> {
    this.objects.delete(key);
  }
}
