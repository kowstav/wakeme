import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { presetDraft, validateGrantDraft, validateWindow, zonedToInstant, nextOccurrences, type GrantDraft } from '../src/index.ts';

const now = new Date('2026-10-19T14:00:00Z');

describe('grant presets', () => {
  for (const preset of ['gentle_nudges', 'wake_buddy', 'focus_buddy'] as const) {
    it(`${preset} is valid`, () => {
      assert.deepEqual(validateGrantDraft(presetDraft(preset, now), now), []);
    });
  }
  it('no preset grants location or check-in requests', () => {
    for (const preset of ['gentle_nudges', 'wake_buddy', 'focus_buddy'] as const) {
      const caps = presetDraft(preset, now).capabilities;
      assert.ok(!caps.includes('location.request'));
      assert.ok(!caps.includes('safety.checkin.request'));
    }
  });
});

describe('grant validation', () => {
  const base = (): GrantDraft => presetDraft('gentle_nudges', now);

  it('rejects unknown capabilities', () => {
    const d = base();
    d.capabilities = ['nudge.send', 'phone.control'];
    assert.ok(validateGrantDraft(d, now).some((i) => i.message.includes('unknown capability')));
  });

  it('rejects unbounded validity', () => {
    const d = base();
    d.validUntil = new Date(now.getTime() + 400 * 86_400_000).toISOString();
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'validUntil'));
  });

  it('rejects past validity', () => {
    const d = base();
    d.validUntil = '2026-10-01T00:00:00Z';
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'validUntil'));
  });

  it('requires limits for granted capabilities', () => {
    const d = base();
    d.capabilities = ['nudge.send', 'alarm.request'];
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'limits.alarm'));
  });

  it('rejects limits for capabilities that are not granted', () => {
    const d = presetDraft('wake_buddy', now);
    d.capabilities = ['alarm.schedule'];
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'limits.challenge'));
  });

  it('bounds the nudge rate', () => {
    const d = base();
    d.limits.nudge!.maxPerHour = 100;
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'limits.nudge.maxPerHour'));
  });

  it('rejects arbitrary sound URLs', () => {
    const d = base();
    d.limits.nudge!.sounds = ['https://example.com/loud.mp3'];
    assert.ok(validateGrantDraft(d, now).some((i) => i.path === 'limits.nudge.sounds'));
  });

  it('rejects zero-length windows', () => {
    assert.ok(validateWindow({ days: [1], start: '07:00', end: '07:00' }).length > 0);
    assert.ok(validateWindow({ days: [8], start: '07:00', end: '08:00' }).length > 0);
    assert.ok(validateWindow({ days: [1, 1], start: '07:00', end: '08:00' }).length > 0);
    assert.equal(validateWindow({ days: [1], start: '23:00', end: '01:00' }).length, 0);
  });
});

describe('time arithmetic', () => {
  it('handles every hour of a DST transition day without throwing', () => {
    for (let h = 0; h < 24; h++) {
      for (const m of [0, 15, 30, 45]) {
        const t = zonedToInstant({ year: 2027, month: 3, day: 14 }, h, m, 'America/New_York');
        assert.ok(!Number.isNaN(t.getTime()));
      }
    }
  });

  it('returns ascending occurrences', () => {
    const occ = nextOccurrences(
      { localTime: '06:45', timeZone: 'Europe/Berlin', recurrence: { kind: 'weekly', days: [1, 3, 5] } },
      new Date('2026-10-20T00:00:00Z'),
      10,
    );
    assert.equal(occ.length, 10);
    for (let i = 1; i < occ.length; i++) assert.ok(occ[i]!.instant > occ[i - 1]!.instant);
  });
});
