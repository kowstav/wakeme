import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { generateKeyPairSync } from 'node:crypto';
import {
  CHESS_PUZZLES,
  TEMPLATES,
  analyzeMateInOne,
  buildTemplate,
  canonicalJson,
  loadSigningKey,
  normalizeAnswer,
  recipientPreview,
  scanText,
  signEnvelope,
  validateChallenge,
  verifyEnvelope,
  verifyStep,
  withDerivedFields,
  type ChallengeDefinition,
} from '../src/index.ts';

const fixtures = new URL('../../policy-fixtures/fixtures/', import.meta.url);
const load = (name: string) => JSON.parse(readFileSync(new URL(`${name}.json`, fixtures), 'utf8'));

describe('fixture: challenge-verification', () => {
  const fx = load('challenge-verification');
  for (const c of fx.normalizationCases) {
    it(`normalizes ${JSON.stringify(c.input)}`, () => assert.equal(normalizeAnswer(c.input), c.expected));
  }
  for (const c of fx.cases) {
    it(c.name, () => assert.equal(verifyStep(c.step, c.response), c.expected));
  }
});

describe('fixture: challenge-safety-evals', () => {
  const fx = load('challenge-safety-evals');
  for (const c of fx.cases) {
    it(`${c.expected.length ? 'rejects' : 'allows'}: ${c.text}`, () => {
      const found = [...new Set(scanText(c.text, '/x', c.task).map((f) => f.category))].sort();
      assert.deepEqual(found, [...c.expected].sort());
    });
  }
});

describe('chess puzzle library', () => {
  for (const p of CHESS_PUZZLES) {
    it(`${p.id} solutions are exactly the mating moves`, () => {
      const a = analyzeMateInOne(p.fen);
      assert.ok(a.ok, a.error);
      assert.deepEqual(a.mates, [...p.solutions].sort());
    });
  }
  it('rejects a position where the side not to move is in check', () => {
    assert.equal(analyzeMateInOne('7k/8/6K1/8/8/8/8/Q7 w - - 0 1').ok, false);
  });
  it('rejects malformed FEN', () => {
    assert.equal(analyzeMateInOne('not a fen').ok, false);
  });
});

describe('templates', () => {
  for (const t of TEMPLATES) {
    for (const difficulty of ['easy', 'medium', 'hard'] as const) {
      for (const seed of [1, 7, 42, 1234]) {
        it(`${t.id} ${difficulty} seed ${seed} validates`, () => {
          const r = validateChallenge(buildTemplate(t.id, { difficulty, seed }));
          assert.ok(r.ok, JSON.stringify(!r.ok && { issues: r.issues, safety: r.safety }));
        });
      }
    }
  }
  it('is deterministic for a seed', () => {
    assert.deepEqual(buildTemplate('morning-math', { seed: 9 }), buildTemplate('morning-math', { seed: 9 }));
  });
});

function quiz(): ChallengeDefinition {
  return withDerivedFields({
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
  });
}

describe('validation', () => {
  it('accepts the documented example', () => assert.ok(validateChallenge(quiz()).ok));

  const expectIssue = (mutate: (d: any) => void, code: string) => {
    const d: any = structuredClone(quiz());
    mutate(d);
    const r = validateChallenge(d);
    assert.equal(r.ok, false);
    if (!r.ok) assert.ok([...r.issues.map((i) => i.code), ...r.safety.map((s) => `safety.${s.category}`)].includes(code), JSON.stringify(r));
  };

  it('rejects unknown fields (no smuggled code)', () => expectIssue((d) => (d.steps[1].onComplete = 'fetch()'), 'schema.additionalProperties'));
  it('rejects unknown step types', () => expectIssue((d) => (d.steps[0] = { type: 'run_script', id: 'x', script: 'rm -rf' }), 'schema.discriminator'));
  it('rejects a wrong math answer', () => expectIssue((d) => (d.steps[1].answer = 16), 'wrong_answer'));
  it('rejects inexact division', () => expectIssue((d) => Object.assign(d.steps[1], { operands: [7, 2], operator: '/', answer: 3 }), 'inexact_division'));
  it('rejects floats', () => expectIssue((d) => (d.steps[1].operands = [7.5, 8]), 'schema.type'));
  it('rejects an out-of-range correct index', () => expectIssue((d) => (d.steps[0].correctIndex = 5), 'correct_index_out_of_range'));
  it('rejects unbounded duration', () => expectIssue((d) => (d.maxDurationSeconds = 3600), 'schema.maximum'));
  it('rejects too many steps', () => expectIssue((d) => (d.steps = Array.from({ length: 9 }, (_, i) => ({ ...d.steps[1], id: `s${i}` }))), 'schema.maxItems'));
  it('rejects overlong text', () => expectIssue((d) => (d.steps[0].prompt = 'x'.repeat(201)), 'schema.maxLength'));
  it('rejects duplicate step ids', () => expectIssue((d) => (d.steps[1].id = 'q1'), 'duplicate_step_id'));
  it('rejects a missing fallback', () => expectIssue((d) => delete d.fallback, 'schema.required'));
  it('rejects understated difficulty', () => expectIssue((d) => Object.assign(d.steps[1], { operands: [37, 41], operator: '*', answer: 1517 }), 'difficulty_understated'));
  it('rejects a duration shorter than the steps', () => expectIssue((d) => (d.maxDurationSeconds = 20), 'duration_too_short'));
  it('rejects misdeclared permissions', () => expectIssue((d) => (d.permissionsRequired = ['camera']), 'permissions_mismatch'));
  it('rejects misdeclared verification', () => expectIssue((d) => (d.verification = 'self_report'), 'verification_mismatch'));
  it('rejects unverified chess', () =>
    expectIssue((d) => (d.steps[1] = { type: 'chess', id: 'c1', prompt: 'Mate', fen: '6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1', solutions: ['a1a7'], goal: 'mate_in_1' }), 'chess_unverified'));
  it('rejects movement without a seated alternative', () =>
    expectIssue((d) => {
      d.steps[1] = { type: 'self_attested', id: 'a1', prompt: 'Move', items: ['10 squats'], category: 'movement' };
      d.verification = 'self_report';
      d.maxDurationSeconds = 120;
    }, 'missing_alternative'));
  it('rejects unsafe manual content', () =>
    expectIssue((d) => {
      d.steps[1] = { type: 'self_attested', id: 'a1', prompt: 'Wake up', items: ['Drink five glasses of water'], category: 'general' };
      d.verification = 'self_report';
    }, 'safety.rapid_consumption'));
});

describe('signing', () => {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
  const key = loadSigningKey(pem);

  it('round-trips and detects tampering', () => {
    const env = signEnvelope({ revision: 1, definition: quiz() }, key, 'k1');
    assert.deepEqual(verifyEnvelope(env, key), { revision: 1, definition: quiz() });
    const tampered = JSON.parse(Buffer.from(env.payload, 'base64').toString());
    tampered.definition.steps[0].correctIndex = 1;
    const bad = { ...env, payload: Buffer.from(canonicalJson(tampered)).toString('base64') };
    assert.throws(() => verifyEnvelope(bad, key));
  });

  it('canonicalizes key order', () => {
    assert.equal(canonicalJson({ b: 1, a: [true, null, 'x'] }), '{"a":[true,null,"x"],"b":1}');
    assert.throws(() => canonicalJson({ a: 1.5 }));
  });

  it('refuses non-P-256 keys', () => {
    const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
    assert.throws(() => loadSigningKey(rsa));
  });
});

describe('recipient preview', () => {
  it('never reveals questions or answers', () => {
    const preview = JSON.stringify(recipientPreview(quiz()));
    for (const secret of ['Chaos curry', 'Thursday', '15', 'Monday brunch']) assert.ok(!preview.includes(secret), secret);
  });
});
