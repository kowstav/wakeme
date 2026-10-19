import { readFileSync } from 'node:fs';
import { Ajv2020, type ErrorObject, type ValidateFunction } from 'ajv/dist/2020.js';
import { difficultyRank, type Difficulty } from '@wakeme/policy';
import { verifyMateInOneSolutions } from './chess.ts';
import { puzzleById } from './puzzles.ts';
import { scanChallenge } from './safety.ts';
import type { ChallengeDefinition, ChallengeStep, SafetyFinding, ValidationIssue } from './types.ts';
import { evaluateMath } from './verify.ts';

export const challengeSchema: object = JSON.parse(
  readFileSync(new URL('../schema/challenge-definition.v1.schema.json', import.meta.url), 'utf8'),
);

let compiled: ValidateFunction | null = null;
function schemaValidator(): ValidateFunction {
  if (!compiled) {
    const ajv = new Ajv2020({ strict: true, discriminator: true, allErrors: true });
    compiled = ajv.compile(challengeSchema);
  }
  return compiled;
}

/** Seconds a typical user needs for each step; the sum must fit maxDurationSeconds. */
export function estimateStepSeconds(step: ChallengeStep): number {
  switch (step.type) {
    case 'multiple_choice':
      return 15;
    case 'short_answer':
      return 20;
    case 'math':
      return 15;
    case 'memory_order':
      return step.displaySeconds + 15;
    case 'chess':
      return 45;
    case 'timer':
      return step.seconds;
    case 'qr_code':
      return 30;
    case 'self_attested':
      return 10 + 5 * step.items.length;
  }
}

export function stepDifficulty(step: ChallengeStep): Difficulty {
  switch (step.type) {
    case 'math': {
      const max = Math.max(...step.operands.map((o) => Math.abs(o)));
      if (step.operands.length === 2 && (step.operator === '+' || step.operator === '-') && max <= 20) return 'easy';
      if (step.operands.length === 2 && max <= 100) return 'medium';
      if (step.operands.length === 2 && (step.operator === '+' || step.operator === '-') && max <= 999) return 'medium';
      return 'hard';
    }
    case 'memory_order':
      return step.items.length <= 4 ? 'easy' : step.items.length <= 6 ? 'medium' : 'hard';
    case 'chess':
      return (step.puzzleId && puzzleById(step.puzzleId)?.difficulty) || 'medium';
    default:
      return 'easy';
  }
}

export interface DerivedChallengeFacts {
  verification: 'local_deterministic' | 'self_report';
  permissionsRequired: 'camera'[];
  estimatedSeconds: number;
  actualDifficulty: Difficulty;
  stepTypes: ChallengeStep['type'][];
}

export function deriveFacts(def: ChallengeDefinition): DerivedChallengeFacts {
  const selfReport = def.steps.some((s) => s.type === 'self_attested' || s.type === 'timer');
  const camera = def.steps.some((s) => s.type === 'qr_code');
  let actual: Difficulty = 'easy';
  for (const s of def.steps) {
    const d = stepDifficulty(s);
    if (difficultyRank(d) > difficultyRank(actual)) actual = d;
  }
  return {
    verification: selfReport ? 'self_report' : 'local_deterministic',
    permissionsRequired: camera ? ['camera'] : [],
    estimatedSeconds: def.steps.reduce((sum, s) => sum + estimateStepSeconds(s), 0),
    actualDifficulty: actual,
    stepTypes: [...new Set(def.steps.map((s) => s.type))],
  };
}

export type ChallengeValidationResult =
  | { ok: true; value: ChallengeDefinition; derived: DerivedChallengeFacts }
  | { ok: false; issues: ValidationIssue[]; safety: SafetyFinding[] };

function schemaIssues(errors: ErrorObject[] | null | undefined): ValidationIssue[] {
  return (errors ?? []).slice(0, 20).map((e) => ({
    path: e.instancePath || '/',
    code: `schema.${e.keyword}`,
    message: e.message ?? 'invalid value',
  }));
}

/**
 * Full pipeline for any challenge, manual or AI-drafted:
 * schema (bounds, no unknown fields) -> semantic checks (answers are computed,
 * not trusted) -> safety rules -> derived facts must match declared ones.
 */
export function validateChallenge(input: unknown): ChallengeValidationResult {
  const validate = schemaValidator();
  if (!validate(input)) return { ok: false, issues: schemaIssues(validate.errors), safety: [] };
  const def = input as ChallengeDefinition;
  const issues: ValidationIssue[] = [];

  const ids = new Set<string>();
  def.steps.forEach((step, i) => {
    const p = `/steps/${i}`;
    if (ids.has(step.id)) issues.push({ path: `${p}/id`, code: 'duplicate_step_id', message: 'Step ids must be unique.' });
    ids.add(step.id);
    switch (step.type) {
      case 'multiple_choice': {
        if (step.correctIndex >= step.options.length) {
          issues.push({ path: `${p}/correctIndex`, code: 'correct_index_out_of_range', message: 'The correct answer must be one of the options.' });
        }
        const normalized = new Set(step.options.map((o) => o.trim().toLowerCase()));
        if (normalized.size !== step.options.length) {
          issues.push({ path: `${p}/options`, code: 'duplicate_options', message: 'Options must be different from each other.' });
        }
        break;
      }
      case 'math': {
        const value = evaluateMath(step.operands, step.operator);
        if (value === null) issues.push({ path: p, code: 'inexact_division', message: 'Division must come out to a whole number.' });
        else if (value !== step.answer) issues.push({ path: `${p}/answer`, code: 'wrong_answer', message: 'The answer does not match the calculation.' });
        break;
      }
      case 'chess': {
        const check = verifyMateInOneSolutions(step.fen, step.solutions);
        if (!check.ok) issues.push({ path: p, code: 'chess_unverified', message: `Chess puzzle failed verification: ${check.error}` });
        if (step.puzzleId) {
          const known = puzzleById(step.puzzleId);
          if (!known || known.fen !== step.fen) {
            issues.push({ path: `${p}/puzzleId`, code: 'unknown_puzzle', message: 'Puzzle id does not match the verified library.' });
          }
        }
        break;
      }
      case 'self_attested':
        if (step.category === 'movement' && !step.alternative) {
          issues.push({ path: `${p}/alternative`, code: 'missing_alternative', message: 'Movement tasks need a seated or low-effort alternative.' });
        }
        break;
      case 'short_answer':
        if (step.acceptedAnswers.some((a) => a.trim().length === 0)) {
          issues.push({ path: `${p}/acceptedAnswers`, code: 'empty_answer', message: 'Accepted answers cannot be blank.' });
        }
        break;
      case 'memory_order':
      case 'timer':
      case 'qr_code':
        break;
    }
  });

  const derived = deriveFacts(def);
  if (derived.estimatedSeconds > def.maxDurationSeconds) {
    issues.push({
      path: '/maxDurationSeconds',
      code: 'duration_too_short',
      message: `These steps take about ${derived.estimatedSeconds} seconds, longer than the ${def.maxDurationSeconds}-second limit.`,
    });
  }
  if (difficultyRank(derived.actualDifficulty) > difficultyRank(def.difficulty)) {
    issues.push({ path: '/difficulty', code: 'difficulty_understated', message: `This challenge is ${derived.actualDifficulty}, not ${def.difficulty}.` });
  }
  if (def.verification !== derived.verification) {
    issues.push({ path: '/verification', code: 'verification_mismatch', message: `Verification must be ${derived.verification}.` });
  }
  const declaredPerms = [...def.permissionsRequired].sort().join(',');
  if (declaredPerms !== derived.permissionsRequired.join(',')) {
    issues.push({ path: '/permissionsRequired', code: 'permissions_mismatch', message: 'Declared permissions must match the steps.' });
  }

  const safety = scanChallenge(def);
  if (issues.length > 0 || safety.length > 0) return { ok: false, issues, safety };
  return { ok: true, value: def, derived };
}

/** Fill derived fields for an authored draft so creators never hand-edit them. */
export function withDerivedFields(def: ChallengeDefinition): ChallengeDefinition {
  const d = deriveFacts(def);
  return { ...def, verification: d.verification, permissionsRequired: d.permissionsRequired };
}
