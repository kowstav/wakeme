import { PgDb } from '../db/db.ts';
import { migrate } from '../db/migrate.ts';

// Run with MIGRATION_DATABASE_URL (owner/migration role), never the runtime role.
const url = process.env['MIGRATION_DATABASE_URL'] ?? process.env['DATABASE_URL'];
if (!url) {
  console.error('MIGRATION_DATABASE_URL is required');
  process.exit(1);
}
const db = new PgDb(url, 1);
try {
  const applied = await migrate(db, (m) => console.log(m));
  console.log(applied.length ? `applied ${applied.length} migration(s)` : 'schema up to date');
} finally {
  await db.close();
}
