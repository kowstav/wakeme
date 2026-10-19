import {
  CHESS_PUZZLES,
  estimateStepSeconds,
  evaluateMath,
  normalizeAnswer,
  puzzleById,
  validateChallenge,
  withDerivedFields,
  type ChallengeDefinition,
  type ChallengeStep,
  type ChallengeValidationResult,
  type MathOperator,
} from '@wakeme/challenges';
import type { Difficulty } from '@wakeme/policy';

// The model is a challenge *author*: it proposes a draft using existing native
// primitives. This module turns that draft into a ChallengeDefinition using
// only fields we choose, recomputes every answer deterministically, takes
// chess positions only from the verified library, and requires every quiz
// answer to come from facts the creator supplied. Then full validation runs.

export interface CreatorFact {
  id: string;
  question: string;
  answer: string;
  distractors: string[];
}

/** Structured-output schema (subset supported by the API: no numeric/length bounds, anyOf not oneOf). */
export const DRAFT_OUTPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['title', 'difficulty', 'steps', 'notes'],
  properties: {
    title: { type: 'string', description: 'Short friendly title, at most 60 characters.' },
    difficulty: { type: 'string', enum: ['easy', 'medium', 'hard'] },
    notes: { type: 'string', description: 'One sentence on anything requested that the supported blocks cannot do, and the alternative used. Empty string if none.' },
    steps: {
      type: 'array',
      description: 'One to six steps.',
      items: {
        anyOf: [
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'factId', 'prompt', 'options', 'correctOption'],
            properties: {
              type: { const: 'fact_multiple_choice' },
              factId: { type: 'string', description: 'id of the creator fact this question is built from' },
              prompt: { type: 'string' },
              options: { type: 'array', items: { type: 'string' }, description: '2-4 options; must include the fact answer exactly and the supplied distractors when given.' },
              correctOption: { type: 'string', description: 'Exactly the fact answer.' },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'factId', 'prompt'],
            properties: {
              type: { const: 'fact_short_answer' },
              factId: { type: 'string' },
              prompt: { type: 'string' },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'operands', 'operator'],
            properties: {
              type: { const: 'math' },
              operands: { type: 'array', items: { type: 'integer' }, description: '2 or 3 integers' },
              operator: { type: 'string', enum: ['+', '-', '*', '/'] },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'puzzleId'],
            properties: {
              type: { const: 'chess' },
              puzzleId: { type: 'string', enum: CHESS_PUZZLES.map((p) => p.id) },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'prompt', 'items'],
            properties: {
              type: { const: 'memory_order' },
              prompt: { type: 'string' },
              items: { type: 'array', items: { type: 'string' }, description: '3-7 short distinct words' },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'prompt', 'seconds', 'activity'],
            properties: {
              type: { const: 'timer' },
              prompt: { type: 'string' },
              seconds: { type: 'integer', description: '10-600' },
              activity: { type: 'string', enum: ['practice', 'rest', 'reading', 'stretch', 'other'] },
            },
          },
          {
            type: 'object',
            additionalProperties: false,
            required: ['type', 'prompt', 'items', 'category', 'alternative'],
            properties: {
              type: { const: 'checklist' },
              prompt: { type: 'string' },
              items: { type: 'array', items: { type: 'string' }, description: '1-5 short items' },
              category: { type: 'string', enum: ['general', 'movement', 'hygiene', 'practice'] },
              alternative: { type: 'string', description: 'Low-effort or seated alternative; required for movement, else empty string.' },
            },
          },
        ],
      },
    },
  },
} as const;

type DraftStep =
  | { type: 'fact_multiple_choice'; factId: string; prompt: string; options: string[]; correctOption: string }
  | { type: 'fact_short_answer'; factId: string; prompt: string }
  | { type: 'math'; operands: number[]; operator: MathOperator }
  | { type: 'chess'; puzzleId: string }
  | { type: 'memory_order'; prompt: string; items: string[] }
  | { type: 'timer'; prompt: string; seconds: number; activity: 'practice' | 'rest' | 'reading' | 'stretch' | 'other' }
  | { type: 'checklist'; prompt: string; items: string[]; category: 'general' | 'movement' | 'hygiene' | 'practice'; alternative: string };

export interface ModelDraft {
  title: string;
  difficulty: Difficulty;
  notes: string;
  steps: DraftStep[];
}

export type AssembleResult =
  | { ok: true; definition: ChallengeDefinition; notes: string }
  | { ok: false; code: 'invalid_draft' | 'invented_fact' | 'validation_failed'; message: string; validation?: ChallengeValidationResult };

const str = (v: unknown): v is string => typeof v === 'string';

export function assembleDraft(raw: unknown, facts: readonly CreatorFact[], requestedDifficulty: Difficulty): AssembleResult {
  if (typeof raw !== 'object' || raw === null) return { ok: false, code: 'invalid_draft', message: 'The draft was empty.' };
  const d = raw as Partial<ModelDraft>;
  if (!str(d.title) || !Array.isArray(d.steps) || d.steps.length === 0 || d.steps.length > 6) {
    return { ok: false, code: 'invalid_draft', message: 'The draft did not have a usable set of steps.' };
  }
  const factById = new Map(facts.map((f) => [f.id, f]));
  const steps: ChallengeStep[] = [];
  for (const [i, s] of d.steps.entries()) {
    const id = `s${i + 1}`;
    switch (s?.type) {
      case 'fact_multiple_choice': {
        const fact = factById.get(s.factId);
        if (!fact) return { ok: false, code: 'invented_fact', message: 'A question did not come from the facts you supplied.' };
        if (!Array.isArray(s.options) || !s.options.every(str)) return { ok: false, code: 'invalid_draft', message: 'Options were malformed.' };
        // The correct answer is always the creator's own answer text, never the model's.
        const options = [...new Set(s.options.map((o) => o.trim()).filter(Boolean))];
        const answerIdx = options.findIndex((o) => normalizeAnswer(o) === normalizeAnswer(fact.answer));
        if (answerIdx < 0) return { ok: false, code: 'invented_fact', message: 'A question did not use your answer.' };
        options[answerIdx] = fact.answer;
        steps.push({ type: 'multiple_choice', id, prompt: str(s.prompt) && s.prompt.trim() ? s.prompt.trim() : fact.question, options: options.slice(0, 6), correctIndex: answerIdx, source: 'creator_provided', factId: fact.id });
        break;
      }
      case 'fact_short_answer': {
        const fact = factById.get(s.factId);
        if (!fact) return { ok: false, code: 'invented_fact', message: 'A question did not come from the facts you supplied.' };
        steps.push({ type: 'short_answer', id, prompt: str(s.prompt) && s.prompt.trim() ? s.prompt.trim() : fact.question, acceptedAnswers: [fact.answer], source: 'creator_provided', factId: fact.id });
        break;
      }
      case 'math': {
        if (!Array.isArray(s.operands) || !s.operands.every((n) => Number.isInteger(n))) return { ok: false, code: 'invalid_draft', message: 'A sum was malformed.' };
        const answer = evaluateMath(s.operands, s.operator);
        if (answer === null) return { ok: false, code: 'invalid_draft', message: 'A sum did not divide evenly.' };
        steps.push({ type: 'math', id, operands: s.operands, operator: s.operator, answer });
        break;
      }
      case 'chess': {
        const p = puzzleById(s.puzzleId);
        if (!p) return { ok: false, code: 'invalid_draft', message: 'Only verified chess puzzles can be used.' };
        const side = p.fen.split(' ')[1] === 'b' ? 'Black' : 'White';
        steps.push({ type: 'chess', id, prompt: `${side} to move and checkmate in one.`, fen: p.fen, solutions: [...p.solutions], goal: 'mate_in_1', puzzleId: p.id });
        break;
      }
      case 'memory_order':
        if (!Array.isArray(s.items) || !s.items.every(str)) return { ok: false, code: 'invalid_draft', message: 'A memory list was malformed.' };
        steps.push({ type: 'memory_order', id, prompt: str(s.prompt) ? s.prompt : 'Remember these, then tap them in order.', items: s.items.map((x) => x.trim()), displaySeconds: 6 });
        break;
      case 'timer':
        steps.push({ type: 'timer', id, prompt: str(s.prompt) ? s.prompt : 'Timer', seconds: Math.round(Number(s.seconds)), activity: s.activity });
        break;
      case 'checklist': {
        if (!Array.isArray(s.items) || !s.items.every(str)) return { ok: false, code: 'invalid_draft', message: 'A checklist was malformed.' };
        const step: ChallengeStep = { type: 'self_attested', id, prompt: str(s.prompt) ? s.prompt : 'Checklist', items: s.items.map((x) => x.trim()), category: s.category };
        if (str(s.alternative) && s.alternative.trim()) step.alternative = s.alternative.trim();
        steps.push(step);
        break;
      }
      default:
        return { ok: false, code: 'invalid_draft', message: 'The draft used a step type that is not supported.' };
    }
  }

  const estimated = steps.reduce((sum, s) => sum + estimateStepSeconds(s), 0);
  const difficulty = (['easy', 'medium', 'hard'] as const).includes(d.difficulty as Difficulty) ? (d.difficulty as Difficulty) : requestedDifficulty;
  const definition = withDerivedFields({
    schemaVersion: 1,
    title: d.title.trim().slice(0, 60) || 'Our challenge',
    kind: 'sequence',
    difficulty,
    maxDurationSeconds: Math.min(900, Math.max(30, Math.ceil((estimated * 1.5) / 10) * 10)),
    steps,
    verification: 'local_deterministic',
    fallback: { type: 'accessible_checkin' },
    permissionsRequired: [],
  });
  const v = validateChallenge(definition);
  if (!v.ok) return { ok: false, code: 'validation_failed', message: 'The draft did not pass safety and correctness checks.', validation: v };
  return { ok: true, definition: v.value, notes: str(d.notes) ? d.notes.slice(0, 200) : '' };
}
