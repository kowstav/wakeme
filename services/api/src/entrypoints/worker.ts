import { loadConfig } from '../config.ts';
import { buildDeps } from '../bootstrap.ts';
import { createLogger } from '../lib/log.ts';
import { Worker } from '../worker/worker.ts';

const log = createLogger({ service: 'wakeme-worker' });
const config = loadConfig();
const deps = buildDeps(config, log, { role: 'worker' });
const worker = new Worker(deps);

const shutdown = async (signal: string) => {
  log.info({ signal }, 'worker stopping');
  await worker.stop();
  await deps.db.close();
  process.exit(0);
};
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));

log.info({ env: config.env }, 'worker started');
await worker.start();
