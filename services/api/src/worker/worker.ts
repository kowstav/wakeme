import type { Deps } from '../deps.ts';
import { workerHandlers } from './handlers.ts';
import { OutboxProcessor } from './outbox.ts';
import { endExpiredSafetySessions, expireCommands, missedCheckins, reconcileEntitlements, retention } from './periodic.ts';

/**
 * Worker process: drains the outbox (woken by LISTEN/NOTIFY, with polling as
 * the source of truth) and runs periodic jobs. Several instances can run.
 */
export class Worker {
  readonly outbox: OutboxProcessor;
  private readonly deps: Deps;
  private stopped = false;
  private wake: (() => void) | null = null;
  private unlisten: (() => Promise<void>) | null = null;
  private lastMinute = 0;
  private lastHour = 0;
  private lastSixHours = 0;

  constructor(deps: Deps) {
    this.deps = deps;
    this.outbox = new OutboxProcessor(deps, workerHandlers);
  }

  async tickPeriodic(force = false): Promise<void> {
    const now = Date.now();
    const run = async (name: string, fn: () => Promise<unknown>) => {
      try {
        await fn();
      } catch (e) {
        this.deps.log.error({ err: e, task: name }, 'periodic task failed');
      }
    };
    if (force || now - this.lastMinute >= 30_000) {
      this.lastMinute = now;
      await run('expireCommands', () => expireCommands(this.deps));
      await run('missedCheckins', () => missedCheckins(this.deps));
      await run('endExpiredSafetySessions', () => endExpiredSafetySessions(this.deps));
    }
    if (force || now - this.lastHour >= 3_600_000) {
      this.lastHour = now;
      await run('retention', () => retention(this.deps));
    }
    if (force || now - this.lastSixHours >= 6 * 3_600_000) {
      this.lastSixHours = now;
      await run('reconcileEntitlements', () => reconcileEntitlements(this.deps));
    }
  }

  async start(): Promise<void> {
    if (this.deps.db.listen) {
      this.unlisten = await this.deps.db.listen('outbox', () => this.wake?.());
    }
    while (!this.stopped) {
      try {
        await this.outbox.drain(200);
        await this.tickPeriodic();
      } catch (e) {
        this.deps.log.error({ err: e }, 'worker loop error');
      }
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, 1000);
        this.wake = () => {
          clearTimeout(t);
          resolve();
        };
      });
      this.wake = null;
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.wake?.();
    await this.unlisten?.();
  }
}
