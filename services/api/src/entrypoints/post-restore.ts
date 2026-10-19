import { PgDb } from '../db/db.ts';
import { afterRestore } from '../modules/sync/repo.ts';

// Restore step (docs/runbooks/restore.md): run once against the restored
// database, before the API and worker take traffic again.
const url = process.env['MIGRATION_DATABASE_URL'] ?? process.env['DATABASE_URL'];
if (!url) {
  console.error('MIGRATION_DATABASE_URL is required');
  process.exit(1);
}
const db = new PgDb(url, 1);
try {
  const floor = await db.tx((q) => afterRestore(q));
  console.log(`sync feed moved to ${floor}; every phone re-fetches its lists on its next sync`);
} finally {
  await db.close();
}
