import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createPublicKey } from 'node:crypto';
import { verifyEnvelope } from '@wakeme/challenges';
import { createHarness, idem, WAKE_LIMITS, type Harness } from './support/harness.ts';
import { assembleDraft } from '../src/ai/assemble.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const quiz = {
  schemaVersion: 1,
  title: "The flat's morning quiz",
  kind: 'sequence',
  difficulty: 'easy',
  maxDurationSeconds: 90,
  steps: [
    { type: 'multiple_choice', id: 'q1', prompt: 'What do we call our Thursday dinner?', options: ['Chaos curry', 'Monday brunch', 'Library lunch'], correctIndex: 0, source: 'creator_provided' },
    { type: 'math', id: 'q2', operands: [7, 8], operator: '+', answer: 15 },
  ],
  verification: 'local_deterministic',
  fallback: { type: 'accessible_checkin' },
  permissionsRequired: [],
};

describe('manual challenges', () => {
  it('publishes retired verification keys after a signing-key rotation, current key first', async () => {
    const { generateKeyPairSync } = await import('node:crypto');
    const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const retired = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
    const cfgObj = h.deps.config as { challengeVerifyKeys: { keyId: string; spki: string }[] };
    cfgObj.challengeVerifyKeys = [{ keyId: 'old-1', spki: retired }, { keyId: h.deps.config.challengeSigning.keyId, spki: retired }];
    try {
      const cfg = await h.req('GET', '/v1/config');
      const ids = cfg.body.challengeSigningKeys.map((k: { keyId: string }) => k.keyId);
      assert.deepEqual(ids, [h.deps.config.challengeSigning.keyId, 'old-1'], 'current first; the current id is never shadowed by a retired entry');
      assert.equal(cfg.body.challengeSigningKeys[1].spki, retired);
    } finally {
      cfgObj.challengeVerifyKeys = [];
    }
  });

  it('requires a preview before publishing, then freezes a signed revision', async () => {
    const maya = await h.createUser('Maya');
    const c = await h.req('POST', '/v1/challenges', { user: maya, body: { body: quiz } });
    assert.equal(c.status, 201, JSON.stringify(c.body));
    const id = c.body.id;
    const early = await h.req('POST', `/v1/challenges/${id}/publish`, { user: maya, body: { revision: 1 } });
    assert.equal(early.body.error.code, 'preview_required');
    await h.req('POST', `/v1/challenges/${id}/revisions/1/previewed`, { user: maya });
    const pub = await h.req('POST', `/v1/challenges/${id}/publish`, { user: maya, body: { revision: 1 } });
    assert.equal(pub.body.publishedRevision, 1);
    const revId = pub.body.revisions[0].id;

    const env = await h.req('GET', `/v1/challenge-revisions/${revId}`, { user: maya });
    const cfg = await h.req('GET', '/v1/config');
    const key = createPublicKey({ key: Buffer.from(cfg.body.challengeSigningKeys[0].spki, 'base64'), format: 'der', type: 'spki' });
    const payload = verifyEnvelope(env.body, key) as any;
    assert.equal(payload.revisionId, revId);
    assert.equal(payload.definition.steps[1].answer, 15);

    await assert.rejects(h.db.query(`update challenge_revisions set body = '{}'::jsonb where id = $1`, [revId]), /immutable/);
    const tampered = { ...env.body, payload: Buffer.from(JSON.stringify({ ...payload, definition: { ...payload.definition, title: 'x' } })).toString('base64') };
    assert.throws(() => verifyEnvelope(tampered, key));
  });

  it('rejects unsafe or incorrect content with explanations', async () => {
    const maya = await h.createUser('Maya');
    const wrong = await h.req('POST', '/v1/challenges', { user: maya, body: { body: { ...quiz, steps: [{ ...quiz.steps[1], answer: 16 }] } } });
    assert.equal(wrong.status, 422);
    assert.equal(wrong.body.error.details.issues[0].code, 'wrong_answer');
    const unsafe = await h.req('POST', '/v1/challenges', {
      user: maya,
      body: { body: { ...quiz, steps: [{ type: 'self_attested', id: 'a', prompt: 'Before you stop the alarm', items: ['Drink five glasses of water'], category: 'general' }] } },
    });
    assert.equal(unsafe.status, 422);
    assert.equal(unsafe.body.error.details.safety[0].category, 'rapid_consumption');
    const code = await h.req('POST', '/v1/challenges', { user: maya, body: { body: { ...quiz, steps: [{ type: 'script', id: 'x', code: 'fetch("/v1/grants")' }] } } });
    assert.equal(code.status, 422);
  });

  it('the recipient sees an answer-free preview; strangers see nothing', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const sam = await h.createUser('Sam');
    const maya = await h.createUser('Maya');
    const eve = await h.createUser('Eve');
    await h.befriend(sam, maya);
    await h.grant(sam, maya, { capabilities: ['alarm.schedule', 'challenge.assign'], limits: WAKE_LIMITS });
    const c = await h.req('POST', '/v1/challenges', { user: maya, body: { body: quiz } });
    await h.req('POST', `/v1/challenges/${c.body.id}/revisions/1/previewed`, { user: maya });
    const pub = await h.req('POST', `/v1/challenges/${c.body.id}/publish`, { user: maya, body: { revision: 1 } });
    const revId = pub.body.revisions[0].id;
    assert.equal((await h.req('GET', `/v1/challenge-revisions/${revId}`, { user: eve })).status, 404);
    const plan = await h.req('POST', '/v1/alarm-plans', {
      user: maya,
      body: { ownerId: sam.id, localTime: '07:00', timeZone: 'America/New_York', timeZonePolicy: 'fixed', recurrence: { kind: 'weekly', days: [1, 2, 3] }, soundId: 'bundled:rise.gentle', ringSeconds: 60, challengeRevisionId: revId },
    });
    assert.equal(plan.status, 201, JSON.stringify(plan.body));
    const preview = JSON.stringify(plan.body.pending.challengePreview);
    for (const secret of ['Chaos curry', 'Thursday', 'Monday brunch']) assert.ok(!preview.includes(secret));
    assert.equal(plan.body.pending.challengePreview.verificationLabel, 'Checked by the app on your phone');
    // Once the routine is proposed to Sam, Sam's phone may download it for offline use.
    assert.equal((await h.req('GET', `/v1/challenge-revisions/${revId}`, { user: sam })).status, 200);
  });

  it('a wake request can only carry challenges within the accepted limits', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const sam = await h.createUser('Sam');
    const maya = await h.createUser('Maya');
    await h.befriend(sam, maya);
    await h.grant(sam, maya, { capabilities: ['alarm.request', 'challenge.assign'], limits: { ...WAKE_LIMITS, challenge: { ...WAKE_LIMITS.challenge, types: ['math'] } } });
    const c = await h.req('POST', '/v1/challenges', { user: maya, body: { body: quiz } });
    await h.req('POST', `/v1/challenges/${c.body.id}/revisions/1/previewed`, { user: maya });
    const pub = await h.req('POST', `/v1/challenges/${c.body.id}/publish`, { user: maya, body: { revision: 1 } });
    const r = await h.signed('POST', '/v1/commands', maya, { targetUserId: sam.id, capability: 'alarm.request', idempotencyKey: idem(), soundId: 'bundled:rise.gentle', ringSeconds: 60, challengeRevisionId: pub.body.revisions[0].id });
    assert.equal(r.body.error.code, 'challenge_not_allowed', 'multiple choice is not in the accepted types');
  });
});

describe('AI drafting', () => {
  const facts = [{ question: 'Which cafe do we call HQ?', answer: 'Bean House', distractors: ['Cup & Co', 'The Grind'] }];

  it('drafts from creator facts using introductory credits, then asks for Plus', async () => {
    const maya = await h.createUser('Maya');
    const g1 = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'Make a quiz from our cafe joke', facts } });
    assert.equal(g1.status, 202);
    assert.equal(g1.body.creditsRemaining, 1);
    await h.drain();
    const done = await h.req('GET', `/v1/challenges/generations/${g1.body.id}`, { user: maya });
    assert.equal(done.body.status, 'succeeded');
    const def = await h.req('GET', `/v1/challenges/${done.body.definitionId}`, { user: maya });
    const step = def.body.revisions[0].body.steps[0];
    assert.equal(step.options[step.correctIndex], 'Bean House', 'the answer is the creator\'s, not the model\'s');
    assert.equal(def.body.revisions[0].state, 'draft', 'AI output is an editable draft, never active');
    assert.equal(h.ai.calls.at(-1)?.facts[0]?.answer, 'Bean House');
    await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'Another one', facts } });
    const third = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'One more', facts } });
    assert.equal(third.status, 402);
    assert.equal(third.body.error.code, 'credits_exhausted');
    assert.match(third.body.error.message, /build a quiz yourself/);
  });

  it('refuses unsafe requests before calling the model, without spending a credit', async () => {
    const maya = await h.createUser('Maya');
    const before = h.ai.calls.length;
    const r = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'First drink five glasses of water then answer a quiz' } });
    assert.equal(r.status, 422);
    assert.equal(r.body.error.code, 'request_unsafe');
    assert.equal(h.ai.calls.length, before);
    assert.equal((await h.req('GET', '/v1/me', { user: maya })).body.billing.credits.available, 2);
  });

  it('a safety-rejected draft uses the credit; a provider failure is refunded', async () => {
    const maya = await h.createUser('Maya');
    await h.drain();
    h.ai.next = [{ kind: 'draft', usage: { inputTokens: 1, outputTokens: 1, costMicros: 1, model: 'scripted' }, draft: { title: 'Wake', difficulty: 'easy', notes: '', steps: [{ type: 'checklist', prompt: 'Go', items: ['Do 100 push-ups'], category: 'movement', alternative: 'Sit' }] } }];
    const a = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'make me exercise' } });
    await h.drain();
    const ra = await h.req('GET', `/v1/challenges/generations/${a.body.id}`, { user: maya });
    assert.equal(ra.body.status, 'rejected');
    assert.equal(ra.body.rejection.safety[0].category, 'dangerous_physical');
    assert.equal(ra.body.creditsRemaining, 1);

    h.ai.next = [{ kind: 'failed', retryable: false, reason: 'provider_500' }];
    const b = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'a maths warm-up' } });
    assert.equal(b.body.creditsRemaining, 0);
    await h.drain();
    const rb = await h.req('GET', `/v1/challenges/generations/${b.body.id}`, { user: maya });
    assert.equal(rb.body.status, 'failed');
    assert.equal(rb.body.creditsRemaining, 1, 'system failures are refunded');
  });

  it('a draft that invents a personal fact is retried, then fails and refunds', async () => {
    const maya = await h.createUser('Maya');
    const invented = { kind: 'draft' as const, usage: { inputTokens: 1, outputTokens: 1, costMicros: 1, model: 'scripted' }, draft: { title: 'Quiz', difficulty: 'easy', notes: '', steps: [{ type: 'fact_short_answer', factId: 'f9', prompt: "What is Maya's middle name?" }] } };
    await h.drain();
    h.ai.next = [invented, invented];
    const g = await h.req('POST', '/v1/challenges/generations', { user: maya, body: { prompt: 'quiz about Maya', facts } });
    await h.drain();
    await h.makeJobsDue();
    await h.drain();
    const r = await h.req('GET', `/v1/challenges/generations/${g.body.id}`, { user: maya });
    assert.equal(r.body.status, 'failed');
    assert.equal(r.body.errorCode, 'invented_fact');
    assert.equal(r.body.creditsRemaining, 2);
  });
});

describe('draft assembly (model output is untrusted)', () => {
  const facts = [{ id: 'f1', question: 'Which cafe is HQ?', answer: 'Bean House', distractors: ['Cup & Co'] }];

  it('recomputes maths and ignores the model\'s answers', () => {
    const r = assembleDraft({ title: 'M', difficulty: 'easy', notes: '', steps: [{ type: 'math', operands: [12, 9], operator: '+', answer: 99 }] }, [], 'easy');
    assert.ok(r.ok);
    if (r.ok) assert.equal((r.definition.steps[0] as any).answer, 21);
  });

  it('takes chess only from the verified library', () => {
    assert.ok(assembleDraft({ title: 'C', difficulty: 'easy', notes: '', steps: [{ type: 'chess', puzzleId: 'back-rank-rook' }] }, [], 'easy').ok);
    const r = assembleDraft({ title: 'C', difficulty: 'easy', notes: '', steps: [{ type: 'chess', puzzleId: 'made-up', fen: '8/8/8/8/8/8/8/8 w - - 0 1' }] }, [], 'easy');
    assert.equal(r.ok, false);
  });

  it('uses the creator answer text and rejects quizzes that drop it', () => {
    const ok = assembleDraft({ title: 'Q', difficulty: 'easy', notes: '', steps: [{ type: 'fact_multiple_choice', factId: 'f1', prompt: 'HQ?', options: ['bean house', 'Cup & Co'], correctOption: 'bean house' }] }, facts, 'easy');
    assert.ok(ok.ok);
    if (ok.ok) assert.equal((ok.definition.steps[0] as any).options[0], 'Bean House');
    const dropped = assembleDraft({ title: 'Q', difficulty: 'easy', notes: '', steps: [{ type: 'fact_multiple_choice', factId: 'f1', prompt: 'HQ?', options: ['Starbucks', 'Cup & Co'], correctOption: 'Starbucks' }] }, facts, 'easy');
    assert.equal(dropped.ok, false);
  });

  it('cannot smuggle permissions, code or unknown step types', () => {
    const r = assembleDraft({ title: 'X', difficulty: 'easy', notes: '', permissionsRequired: ['location'], steps: [{ type: 'math', operands: [1, 2], operator: '+' }], grant: { capabilities: ['location.request'] } }, [], 'easy');
    assert.ok(r.ok);
    if (r.ok) {
      assert.deepEqual(r.definition.permissionsRequired, []);
      assert.ok(!('grant' in r.definition));
    }
    assert.equal(assembleDraft({ title: 'X', difficulty: 'easy', notes: '', steps: [{ type: 'run_js', source: 'alert(1)' }] }, [], 'easy').ok, false);
    const markup = assembleDraft({ title: '<script>x</script>', difficulty: 'easy', notes: '', steps: [{ type: 'math', operands: [1, 2], operator: '+' }] }, [], 'easy');
    assert.equal(markup.ok, false);
  });
});
