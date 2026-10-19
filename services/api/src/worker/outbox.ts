import type { Deps } from '../deps.ts';

export interface OutboxJob {
  id: number;
  kind: string;
  dedupe_key: string;
  payload: Record<string, unknown>;
  attempts: number;
  max_attempts: number;
  due_at: Date;
}

/** Throw to retry later; anything else ends the job as failed. */
export class RetryLater extends Error {
  readonly delaySeconds: number | undefined;
  constructor(reason: string, delaySeconds?: number) {
    super(reason);
    this.delaySeconds = delaySeconds;
  }
}

export type JobHandler = (job: OutboxJob, deps: Deps) => Promise<void>;
export type GiveUpHandler = (job: OutboxJob, deps: Deps, reason: string) => Promise<void>;

export interface HandlerSet {
  handlers: Record<string, JobHandler>;
  onGiveUp?: Record<string, GiveUpHandler>;
}

export function backoffSeconds(attempt: number, hint?: number): number {
  if (hint !== undefined && hint > 0) return Math.min(3600, hint);
  const base = Math.min(3600, 5 * 2 ** Math.max(0, attempt - 1));
  return Math.round(base * (0.8 + Math.random() * 0.4));
}

/**
 * Transactional outbox processor. Jobs are leased with SKIP LOCKED so several
 * workers can run safely; a crashed worker's lease simply expires. Handlers
 * must be idempotent: a job can run more than once.
 */
export class OutboxProcessor {
  private readonly deps: Deps;
  private readonly set: HandlerSet;
  private readonly leaseSeconds: number;

  constructor(deps: Deps, set: HandlerSet, leaseSeconds = 60) {
    this.deps = deps;
    this.set = set;
    this.leaseSeconds = leaseSeconds;
  }

  // Scheduling uses the database clock throughout (enqueue, lease, retry), so
  // several workers agree on what is due regardless of their own clocks.
  async claim(batch: number): Promise<OutboxJob[]> {
    const r = await this.deps.db.query<OutboxJob & { id: number | string }>(
      `with c as (
         select id from outbox where processed_at is null and due_at <= now() and (lease_until is null or lease_until < now())
          order by due_at, id limit $1 for update skip locked
       )
       update outbox o set lease_until = now() + make_interval(secs => $2), attempts = o.attempts + 1 from c where o.id = c.id
       returning o.id, o.kind, o.dedupe_key, o.payload, o.attempts, o.max_attempts, o.due_at`,
      [batch, this.leaseSeconds],
    );
    return r.rows.map((j) => ({ ...j, id: Number(j.id) }));
  }

  async run(job: OutboxJob): Promise<'done' | 'retry' | 'failed'> {
    const handler = this.set.handlers[job.kind];
    if (!handler) {
      await this.deps.db.query('update outbox set processed_at = now(), lease_until = null, last_error = $2 where id = $1', [job.id, 'no_handler']);
      this.deps.log.error({ job: job.kind }, 'no handler for outbox job');
      return 'failed';
    }
    try {
      await handler(job, this.deps);
      await this.deps.db.query('update outbox set processed_at = now(), lease_until = null, last_error = null where id = $1', [job.id]);
      return 'done';
    } catch (e) {
      const reason = e instanceof Error ? e.message.slice(0, 200) : 'error';
      const retryable = e instanceof RetryLater;
      if (retryable && job.attempts < job.max_attempts) {
        const delay = backoffSeconds(job.attempts, (e as RetryLater).delaySeconds);
        await this.deps.db.query('update outbox set due_at = now() + make_interval(secs => $2), lease_until = null, last_error = $3 where id = $1', [job.id, delay, reason]);
        return 'retry';
      }
      await this.deps.db.query('update outbox set processed_at = now(), lease_until = null, last_error = $2 where id = $1', [job.id, `gave_up: ${reason}`]);
      const giveUp = this.set.onGiveUp?.[job.kind];
      if (giveUp) await giveUp(job, this.deps, reason).catch((err: unknown) => this.deps.log.error({ err, job: job.kind }, 'give-up handler failed'));
      this.deps.log.warn({ job: job.kind, reason }, 'outbox job failed');
      return 'failed';
    }
  }

  /** Process every currently due job (used by tests and by each worker tick). */
  async drain(maxJobs = 1000): Promise<number> {
    let n = 0;
    while (n < maxJobs) {
      const jobs = await this.claim(Math.min(25, maxJobs - n));
      if (jobs.length === 0) break;
      for (const j of jobs) {
        await this.run(j);
        n++;
      }
    }
    return n;
  }
}
