// Runtime configuration. Secrets come only from the environment (populated by
// the platform's secret manager); nothing secret is committed, logged or sent
// to clients. Every process validates its configuration before starting.

export interface ApnsConfig {
  keyId: string;
  teamId: string;
  /** PKCS#8 PEM of the .p8 token-signing key */
  privateKey: string;
  topic: string;
  /** Override for tests; defaults to Apple's production/sandbox hosts per device. */
  origin?: string;
}

export interface FcmConfig {
  projectId: string;
  clientEmail: string;
  privateKey: string;
  tokenUri?: string;
  sendOrigin?: string;
}

export interface S3Config {
  endpoint: string;
  region: string;
  bucket: string;
  accessKeyId: string;
  secretAccessKey: string;
  forcePathStyle: boolean;
}

export interface AiConfig {
  apiKey: string;
  model: string;
  maxOutputTokens: number;
  /** USD micro-dollars per million tokens, used for cost accounting. */
  inputPriceMicrosPerMTok: number;
  outputPriceMicrosPerMTok: number;
}

export interface Config {
  env: 'development' | 'test' | 'staging' | 'production';
  port: number;
  host: string;
  databaseUrl: string;
  databasePoolMax: number;
  auth: { jwksUrl: string; issuer: string; audience: string };
  /** Seconds since last sign-in required for account deletion. */
  reauthSecondsForDeletion: number;
  /** Seconds since last sign-in required to grant alarm/focus/location capabilities. */
  reauthSecondsForHighImpactGrants: number;
  adminUserIds: string[];
  challengeSigning: { keyId: string; privateKeyPem: string };
  locationKey: { keyId: string; keyBase64: string };
  inviteLinkBase: string;
  apns: ApnsConfig | null;
  fcm: FcmConfig | null;
  s3: S3Config | null;
  ai: AiConfig | null;
  revenueCat: { webhookAuth: string; apiKey: string; apiOrigin: string; entitlementId: string } | null;
  supabaseAdmin: { url: string; serviceRoleKey: string } | null;
  corsOrigins: string[];
}

export class ConfigError extends Error {}

function req(env: NodeJS.ProcessEnv, key: string): string {
  const v = env[key];
  if (!v || v.trim() === '') throw new ConfigError(`missing required environment variable ${key}`);
  return v;
}

function opt(env: NodeJS.ProcessEnv, key: string): string | undefined {
  const v = env[key];
  return v && v.trim() !== '' ? v : undefined;
}

function int(env: NodeJS.ProcessEnv, key: string, fallback: number, min: number, max: number): number {
  const raw = opt(env, key);
  if (raw === undefined) return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) throw new ConfigError(`${key} must be an integer ${min}-${max}`);
  return n;
}

/** PEM values in env vars often have literal "\n"; normalise them. */
function pem(value: string): string {
  return value.includes('\\n') ? value.replace(/\\n/g, '\n') : value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const mode = (opt(env, 'WAKEME_ENV') ?? 'development') as Config['env'];
  if (!['development', 'test', 'staging', 'production'].includes(mode)) throw new ConfigError('WAKEME_ENV is invalid');

  const locationKey = req(env, 'LOCATION_ENCRYPTION_KEY');
  if (Buffer.from(locationKey, 'base64').length !== 32) throw new ConfigError('LOCATION_ENCRYPTION_KEY must be 32 bytes, base64');

  const apnsKey = opt(env, 'APNS_PRIVATE_KEY');
  const fcmKey = opt(env, 'FCM_PRIVATE_KEY');
  const s3Bucket = opt(env, 'S3_BUCKET');
  const aiKey = opt(env, 'ANTHROPIC_API_KEY');
  const rcAuth = opt(env, 'REVENUECAT_WEBHOOK_AUTH');
  const supabaseKey = opt(env, 'SUPABASE_SERVICE_ROLE_KEY');

  const config: Config = {
    env: mode,
    port: int(env, 'PORT', 8080, 1, 65535),
    host: opt(env, 'HOST') ?? '0.0.0.0',
    databaseUrl: req(env, 'DATABASE_URL'),
    databasePoolMax: int(env, 'DATABASE_POOL_MAX', 10, 1, 100),
    auth: { jwksUrl: req(env, 'AUTH_JWKS_URL'), issuer: req(env, 'AUTH_ISSUER'), audience: opt(env, 'AUTH_AUDIENCE') ?? 'authenticated' },
    reauthSecondsForDeletion: int(env, 'REAUTH_SECONDS_DELETION', 900, 60, 86_400),
    reauthSecondsForHighImpactGrants: int(env, 'REAUTH_SECONDS_HIGH_IMPACT_GRANTS', 86_400, 60, 30 * 86_400),
    adminUserIds: (opt(env, 'ADMIN_USER_IDS') ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    challengeSigning: { keyId: opt(env, 'CHALLENGE_SIGNING_KEY_ID') ?? 'c1', privateKeyPem: pem(req(env, 'CHALLENGE_SIGNING_KEY')) },
    locationKey: { keyId: opt(env, 'LOCATION_ENCRYPTION_KEY_ID') ?? 'l1', keyBase64: locationKey },
    inviteLinkBase: opt(env, 'INVITE_LINK_BASE') ?? 'https://wakeme.app/i/',
    apns: apnsKey
      ? { keyId: req(env, 'APNS_KEY_ID'), teamId: req(env, 'APNS_TEAM_ID'), privateKey: pem(apnsKey), topic: req(env, 'APNS_TOPIC'), ...(opt(env, 'APNS_ORIGIN') ? { origin: opt(env, 'APNS_ORIGIN')! } : {}) }
      : null,
    fcm: fcmKey ? { projectId: req(env, 'FCM_PROJECT_ID'), clientEmail: req(env, 'FCM_CLIENT_EMAIL'), privateKey: pem(fcmKey) } : null,
    s3: s3Bucket
      ? {
          endpoint: req(env, 'S3_ENDPOINT'),
          region: opt(env, 'S3_REGION') ?? 'auto',
          bucket: s3Bucket,
          accessKeyId: req(env, 'S3_ACCESS_KEY_ID'),
          secretAccessKey: req(env, 'S3_SECRET_ACCESS_KEY'),
          forcePathStyle: (opt(env, 'S3_FORCE_PATH_STYLE') ?? 'true') === 'true',
        }
      : null,
    ai: aiKey
      ? {
          apiKey: aiKey,
          model: req(env, 'AI_MODEL'),
          maxOutputTokens: int(env, 'AI_MAX_OUTPUT_TOKENS', 2000, 256, 8000),
          inputPriceMicrosPerMTok: int(env, 'AI_INPUT_PRICE_MICROS_PER_MTOK', 4_000_000, 0, 1_000_000_000),
          outputPriceMicrosPerMTok: int(env, 'AI_OUTPUT_PRICE_MICROS_PER_MTOK', 20_000_000, 0, 1_000_000_000),
        }
      : null,
    revenueCat: rcAuth
      ? {
          webhookAuth: rcAuth,
          apiKey: req(env, 'REVENUECAT_API_KEY'),
          apiOrigin: opt(env, 'REVENUECAT_API_ORIGIN') ?? 'https://api.revenuecat.com',
          entitlementId: opt(env, 'REVENUECAT_ENTITLEMENT_ID') ?? 'plus',
        }
      : null,
    supabaseAdmin: supabaseKey ? { url: req(env, 'SUPABASE_URL'), serviceRoleKey: supabaseKey } : null,
    corsOrigins: (opt(env, 'CORS_ORIGINS') ?? '').split(',').map((s) => s.trim()).filter(Boolean),
  };

  if (config.env === 'production') {
    if (!config.auth.jwksUrl.startsWith('https://')) throw new ConfigError('AUTH_JWKS_URL must be https in production');
    if (!config.apns && !config.fcm) throw new ConfigError('production requires at least one push provider');
    if (!config.databaseUrl.includes('sslmode=') && !opt(env, 'DATABASE_SSL_DISABLED')) {
      throw new ConfigError('production DATABASE_URL must specify sslmode');
    }
  }
  return config;
}
