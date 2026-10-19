import { loadConfig } from '../config.ts';
import { buildDeps } from '../bootstrap.ts';
import { buildApp } from '../http/app.ts';
import { createLogger } from '../lib/log.ts';
import { allRoutes } from '../modules/index.ts';

const log = createLogger({ service: 'wakeme-api' });
const config = loadConfig();
const deps = buildDeps(config, log, { role: 'api' });
const app = await buildApp(deps, allRoutes(), { logger: true, trustProxy: process.env['TRUST_PROXY'] === 'true' });

const shutdown = async (signal: string) => {
  log.info({ signal }, 'shutting down');
  await app.close();
  await deps.db.close();
  process.exit(0);
};
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));

await app.listen({ port: config.port, host: config.host });
log.info({ port: config.port, env: config.env }, 'api listening');
