import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, type Harness } from './support/harness.ts';
import { PROMPT_REMOVED, retention } from '../src/worker/periodic.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const daysAgo = (n: number) => new Date(h.clock.now.getTime() - n * 86_400_000);

describe('retention', () => {
  it('removes what people typed for an AI draft after 30 days, keeping the cost record', async () => {
    const maya = await h.createUser('Maya');
    const insert = (createdAt: Date) =>
      h.db.query<{ id: string }>(
        `insert into challenge_generations (user_id, status, prompt, facts, difficulty, model, input_tokens, output_tokens, cost_micros, created_at, completed_at)
         values ($1, 'succeeded', 'Quiz about our flat: Thursday is chaos curry', '["Thursday dinner is chaos curry"]'::jsonb, 'easy', 'm', 100, 200, 4200, $2, $2) returning id`,
        [maya.id, createdAt],
      );
    const old = (await insert(daysAgo(31))).rows[0]!.id;
    const recent = (await insert(daysAgo(29))).rows[0]!.id;

    await retention(h.deps);

    const rows = await h.db.query<{ id: string; prompt: string; facts: unknown; cost_micros: string | number }>('select id, prompt, facts, cost_micros from challenge_generations where id = any($1)', [[old, recent]]);
    const byId = new Map(rows.rows.map((r) => [r.id, r]));
    assert.equal(byId.get(old)?.prompt, PROMPT_REMOVED);
    assert.deepEqual(byId.get(old)?.facts, []);
    assert.equal(Number(byId.get(old)?.cost_micros), 4200, 'the cost record stays for credit accounting');
    assert.equal(byId.get(recent)?.prompt, 'Quiz about our flat: Thursday is chaos curry');
  });

  it('keeps audit records for 365 days, then deletes them', async () => {
    await h.db.query(`insert into audit_events (actor_kind, action, created_at) values ('system', 'retention.old', $1), ('system', 'retention.recent', $2)`, [daysAgo(366), daysAgo(364)]);
    await retention(h.deps);
    const actions = (await h.db.query<{ action: string }>(`select action from audit_events where action like 'retention.%'`)).rows.map((r) => r.action);
    assert.deepEqual(actions, ['retention.recent']);
  });
});
