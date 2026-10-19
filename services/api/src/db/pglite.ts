import type { Db, Queryable, QueryResult } from './db.ts';

// Embedded PostgreSQL (PGlite, WASM) for tests and the zero-setup local dev
// server. It is a real PostgreSQL engine, but single-connection: concurrency
// tests must also run against a server PostgreSQL in CI (DATABASE_URL).

interface PGliteLike {
  query<T>(text: string, params?: unknown[]): Promise<{ rows: T[]; affectedRows?: number }>;
  exec(text: string): Promise<unknown>;
  transaction<T>(fn: (tx: { query<R>(text: string, params?: unknown[]): Promise<{ rows: R[]; affectedRows?: number }> }) => Promise<T>): Promise<T>;
  listen(channel: string, cb: (payload: string) => void): Promise<() => Promise<void>>;
  close(): Promise<void>;
}

export class PgliteDb implements Db {
  private readonly pg: PGliteLike;

  private constructor(pg: PGliteLike) {
    this.pg = pg;
  }

  static async create(dataDir?: string): Promise<PgliteDb> {
    const mod = (await import('@electric-sql/pglite')) as unknown as { PGlite: { create(dir?: string): Promise<PGliteLike> } };
    const pg = await mod.PGlite.create(dataDir);
    return new PgliteDb(pg);
  }

  async query<T>(text: string, params: readonly unknown[] = []): Promise<QueryResult<T>> {
    const r = await this.pg.query<T>(text, params as unknown[]);
    return { rows: r.rows, rowCount: Math.max(r.affectedRows ?? 0, r.rows.length) };
  }

  async exec(text: string): Promise<void> {
    await this.pg.exec(text);
  }

  async tx<T>(fn: (q: Queryable) => Promise<T>): Promise<T> {
    return this.pg.transaction(async (tx) =>
      fn({
        query: async <R>(text: string, params: readonly unknown[] = []) => {
          const r = await tx.query<R>(text, params as unknown[]);
          return { rows: r.rows, rowCount: Math.max(r.affectedRows ?? 0, r.rows.length) };
        },
      }),
    );
  }

  async listen(channel: string, onNotify: () => void): Promise<() => Promise<void>> {
    return this.pg.listen(channel, () => onNotify());
  }

  async close(): Promise<void> {
    await this.pg.close();
  }
}
