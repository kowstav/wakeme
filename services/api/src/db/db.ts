import pg from 'pg';

export interface QueryResult<T> {
  rows: T[];
  rowCount: number;
}

export interface Queryable {
  query<T = Record<string, unknown>>(text: string, params?: readonly unknown[]): Promise<QueryResult<T>>;
}

export interface Db extends Queryable {
  /** Runs fn in one transaction (READ COMMITTED + explicit row locks where invariants need them). */
  tx<T>(fn: (q: Queryable) => Promise<T>): Promise<T>;
  /** LISTEN for wake-ups (outbox). Optional: polling is the source of truth. */
  listen?(channel: string, onNotify: () => void): Promise<() => Promise<void>>;
  close(): Promise<void>;
}

export class PgDb implements Db {
  readonly pool: pg.Pool;

  constructor(connectionString: string, max: number) {
    this.pool = new pg.Pool({
      connectionString,
      max,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
      statement_timeout: 10_000,
      application_name: 'wakeme',
    });
  }

  async query<T>(text: string, params: readonly unknown[] = []): Promise<QueryResult<T>> {
    const r = await this.pool.query(text, params as unknown[]);
    return { rows: r.rows as T[], rowCount: r.rowCount ?? 0 };
  }

  async tx<T>(fn: (q: Queryable) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('begin');
      const q: Queryable = {
        query: async <R>(text: string, params: readonly unknown[] = []) => {
          const r = await client.query(text, params as unknown[]);
          return { rows: r.rows as R[], rowCount: r.rowCount ?? 0 };
        },
      };
      const result = await fn(q);
      await client.query('commit');
      return result;
    } catch (e) {
      await client.query('rollback').catch(() => undefined);
      throw e;
    } finally {
      client.release();
    }
  }

  async listen(channel: string, onNotify: () => void): Promise<() => Promise<void>> {
    if (!/^[a-z_]+$/.test(channel)) throw new Error('invalid channel');
    const client = await this.pool.connect();
    client.on('notification', () => onNotify());
    await client.query(`listen ${channel}`);
    return async () => {
      await client.query(`unlisten ${channel}`).catch(() => undefined);
      client.release();
    };
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}

/** True for unique-violation errors from either driver. */
export function isUniqueViolation(e: unknown, constraint?: string): boolean {
  const err = e as { code?: string; constraint?: string; message?: string };
  if (err?.code !== '23505') return false;
  return constraint === undefined || err.constraint === constraint || (err.message ?? '').includes(constraint);
}
