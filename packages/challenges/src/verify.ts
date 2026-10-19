import { createHash } from 'node:crypto';
import type { ChallengeStep, MathOperator } from './types.ts';

/**
 * Answer normalization shared with the native apps (challenge-verification
 * fixtures): NFKC; strip combining marks after NFD; lowercase; Unicode
 * punctuation and symbols become spaces; collapse whitespace; trim.
 */
export function normalizeAnswer(input: string): string {
  return input
    .normalize('NFKC')
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[\p{P}\p{S}]/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim();
}

/** QR payloads look like "WAKEME:K7P2QX9M"; the same code can be typed as "k7p2-qx9m". */
export function normalizeCode(input: string): string {
  return input
    .trim()
    .replace(/^wakeme:/i, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '');
}

export function codeHash(code: string): string {
  return createHash('sha256').update(normalizeCode(code), 'utf8').digest('hex');
}

/** Left-to-right evaluation; returns null for inexact division or division by zero. */
export function evaluateMath(operands: readonly number[], operator: MathOperator): number | null {
  if (operands.length === 0) return null;
  let acc = operands[0]!;
  for (let i = 1; i < operands.length; i++) {
    const v = operands[i]!;
    switch (operator) {
      case '+':
        acc += v;
        break;
      case '-':
        acc -= v;
        break;
      case '*':
        acc *= v;
        break;
      case '/':
        if (v === 0 || acc % v !== 0) return null;
        acc = acc / v;
        break;
    }
  }
  return acc;
}

export type StepResponse =
  | { index: number }
  | { text: string }
  | { order: string[] }
  | { move: string }
  | { elapsedSeconds: number }
  | { checked: boolean[] };

function has<K extends string>(r: unknown, key: K): r is Record<K, unknown> {
  return typeof r === 'object' && r !== null && key in r;
}

/** Offline verification of one step. Pure and deterministic; mirrored on iOS and Android. */
export function verifyStep(step: ChallengeStep, response: StepResponse): boolean {
  switch (step.type) {
    case 'multiple_choice':
      return has(response, 'index') && response.index === step.correctIndex;
    case 'short_answer': {
      if (!has(response, 'text') || typeof response.text !== 'string') return false;
      const given = normalizeAnswer(response.text);
      return given.length > 0 && step.acceptedAnswers.some((a) => normalizeAnswer(a) === given);
    }
    case 'math': {
      if (!has(response, 'text') || typeof response.text !== 'string') return false;
      const t = response.text.trim();
      if (!/^[+-]?\d{1,9}$/.test(t)) return false;
      return Number.parseInt(t, 10) === step.answer;
    }
    case 'memory_order':
      return (
        has(response, 'order') &&
        Array.isArray(response.order) &&
        response.order.length === step.items.length &&
        response.order.every((v, i) => v === step.items[i])
      );
    case 'chess': {
      if (!has(response, 'move') || typeof response.move !== 'string') return false;
      const m = response.move.trim().toLowerCase();
      return step.solutions.includes(m);
    }
    case 'timer':
      return has(response, 'elapsedSeconds') && typeof response.elapsedSeconds === 'number' && response.elapsedSeconds >= step.seconds;
    case 'qr_code':
      return has(response, 'text') && typeof response.text === 'string' && codeHash(response.text) === step.codeHash;
    case 'self_attested':
      return (
        has(response, 'checked') &&
        Array.isArray(response.checked) &&
        response.checked.length === step.items.length &&
        response.checked.every((c) => c === true)
      );
  }
}
