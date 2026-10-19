import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import type { Db } from './db.ts';

const MIGRATIONS_DIR = new URL('../../migrations/', import.meta.url);

export interface Migration {
  version: string;
  sql: string;
  checksum: string;
}

export function loadMigrations(): Migration[] {
  return readdirSync(MIGRATIONS_DIR)
    .filter((f) => /^\d{4}_[a-z0-9_]+\.sql$/.test(f))
    .sort()
    .map((f) => {
      const sql = readFileSync(new URL(f, MIGRATIONS_DIR), 'utf8');
      return { version: f.replace(/\.sql$/, ''), sql, checksum: createHash('sha256').update(sql).digest('hex') };
    });
}

type ExecDb = Db & { exec?: (sql: string) => Promise<void> };

/**
 * Applies pending migrations in order, each in its own transaction. Applied
 * migrations are checksummed; editing one after release is an error (write a
 * new migration instead). Run with migration credentials, never the runtime role.
 */
export async function migrate(db: ExecDb, log: (msg: string) => void = () => undefined): Promise<string[]> {
  await db.query(`create table if not exists schema_migrations (
    version text primary key,
    checksum text not null,
    applied_at timestamptz not null default now()
  )`);
  const applied = new Map(
    (await db.query<{ version: string; checksum: string }>('select version, checksum from schema_migrations')).rows.map((r) => [r.version, r.checksum]),
  );
  const done: string[] = [];
  for (const m of loadMigrations()) {
    const prior = applied.get(m.version);
    if (prior) {
      if (prior !== m.checksum) throw new Error(`migration ${m.version} was modified after being applied`);
      continue;
    }
    if (db.exec) {
      // PGlite: exec runs multi-statement SQL; wrap explicitly.
      await db.exec(`begin;\n${m.sql}\n;insert into schema_migrations(version, checksum) values ('${m.version}', '${m.checksum}');\ncommit;`);
    } else {
      await db.tx(async (q) => {
        await q.query(m.sql);
        await q.query('insert into schema_migrations(version, checksum) values ($1, $2)', [m.version, m.checksum]);
      });
    }
    log(`applied ${m.version}`);
    done.push(m.version);
  }
  return done;
}
