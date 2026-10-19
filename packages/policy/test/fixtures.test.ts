import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  displayStatus,
  evaluateCommand,
  evaluateLocalExecution,
  isQuietTime,
  isWithinWindow,
  nextOccurrences,
  reduceDelivery,
  type CommandContext,
  type LocalExecutionContext,
} from '../src/index.ts';
import { deepMerge, loadFixture } from './fixture-support.ts';

describe('fixture: time-windows', () => {
  const fx = loadFixture('time-windows');
  for (const c of fx.cases) {
    it(c.name, () => {
      assert.equal(isWithinWindow(new Date(c.instant), c.timeZone, c.window), c.expected);
    });
  }
  for (const c of fx.quietCases) {
    it(`quiet: ${c.name}`, () => {
      assert.equal(isQuietTime(new Date(c.instant), c.timeZone, c.quiet), c.expected);
    });
  }
});

describe('fixture: occurrences', () => {
  const fx = loadFixture('occurrences');
  for (const c of fx.cases) {
    it(c.name, () => {
      const got = nextOccurrences(c.rule, new Date(c.after), c.count).map((o) => ({
        localDate: o.localDate,
        instant: o.instant.toISOString().replace('.000Z', 'Z'),
      }));
      assert.deepEqual(got, c.expected);
    });
  }
});

describe('fixture: local-execution', () => {
  const fx = loadFixture('local-execution');
  for (const c of fx.cases) {
    it(c.name, () => {
      const merged = deepMerge(fx.base, c.override) as any;
      const ctx: LocalExecutionContext = { ...merged, now: new Date(merged.now) };
      assert.deepEqual(evaluateLocalExecution(ctx), c.expected);
    });
  }
});

describe('fixture: command-decisions', () => {
  const fx = loadFixture('command-decisions');
  for (const c of fx.cases) {
    it(c.name, () => {
      const merged = deepMerge(fx.base, c.override) as any;
      const ctx: CommandContext = {
        ...merged,
        now: new Date(merged.now),
        disabledFeatures: new Set<string>(merged.disabledFeatures),
      };
      assert.deepEqual(evaluateCommand(ctx), c.expected);
    });
  }
});

describe('fixture: delivery-state', () => {
  const fx = loadFixture('delivery-state');
  for (const c of fx.cases) {
    it(c.name, () => {
      const state = reduceDelivery(c.events);
      assert.deepEqual(state, c.expected.state);
      const sentAt = new Date('2026-10-19T14:00:00Z');
      const now = new Date(sentAt.getTime() + c.options.secondsSinceSent * 1000);
      assert.equal(displayStatus(state, { now, sentAt, kind: c.options.kind, shareBypass: c.options.shareBypass }), c.expected.display);
    });
  }
});
