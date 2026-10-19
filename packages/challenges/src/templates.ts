import type { Difficulty } from '@wakeme/policy';
import { CHESS_PUZZLES } from './puzzles.ts';
import type { ChallengeDefinition, ChallengeStep, MathOperator } from './types.ts';
import { withDerivedFields } from './validate.ts';
import { evaluateMath } from './verify.ts';

/** mulberry32: tiny deterministic PRNG so a seed always yields the same challenge. */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function intBetween(rand: () => number, min: number, max: number): number {
  return min + Math.floor(rand() * (max - min + 1));
}

export function mathStep(id: string, difficulty: Difficulty, rand: () => number): ChallengeStep {
  let operands: number[];
  let operator: MathOperator;
  switch (difficulty) {
    case 'easy':
      operator = rand() < 0.5 ? '+' : '-';
      // Larger operand first: number pads often have no minus key.
      operands = [intBetween(rand, 2, 20), intBetween(rand, 2, 20)].sort((a, b) => b - a);
      break;
    case 'medium':
      operator = rand() < 0.5 ? '+' : '*';
      operands = operator === '*' ? [intBetween(rand, 3, 12), intBetween(rand, 3, 12)] : [intBetween(rand, 21, 99), intBetween(rand, 21, 99)];
      break;
    case 'hard':
      operator = '*';
      operands = [intBetween(rand, 12, 40), intBetween(rand, 3, 9), intBetween(rand, 2, 5)];
      break;
  }
  const answer = evaluateMath(operands, operator);
  if (answer === null) throw new Error('template produced inexact math');
  return { type: 'math', id, operands, operator, answer };
}

const MEMORY_WORDS = ['sun', 'kettle', 'shoes', 'bus', 'window', 'toast', 'keys', 'socks', 'guitar', 'tea', 'cloud', 'bike', 'lamp', 'apple'];

export type TemplateId = 'morning-math' | 'mate-in-one' | 'memory-sequence' | 'guitar-practice' | 'movement-break' | 'gentle-wake';

export interface TemplateInfo {
  id: TemplateId;
  title: string;
  description: string;
}

export const TEMPLATES: readonly TemplateInfo[] = [
  { id: 'morning-math', title: 'Morning maths', description: 'A few quick sums, generated and checked on the device.' },
  { id: 'mate-in-one', title: 'Mate in one', description: 'One verified chess puzzle with a single winning move set.' },
  { id: 'memory-sequence', title: 'Memory sequence', description: 'Remember a short list, then tap it back in order.' },
  { id: 'guitar-practice', title: 'Guitar practice', description: 'A ten-minute practice timer with a self-check. It cannot hear your playing.' },
  { id: 'movement-break', title: 'Movement break', description: 'A short, comfortable stretch with a seated option.' },
  { id: 'gentle-wake', title: 'Gentle wake', description: 'Open the curtains, sit up and get ready. Self-check only.' },
];

export function buildTemplate(id: TemplateId, opts: { difficulty?: Difficulty; seed?: number } = {}): ChallengeDefinition {
  const difficulty = opts.difficulty ?? 'easy';
  const rand = seededRandom(opts.seed ?? 1);
  const base = {
    schemaVersion: 1 as const,
    kind: 'sequence' as const,
    fallback: { type: 'accessible_checkin' as const, prompt: "Tap \"I'm up\" when you're ready to start your day." },
    verification: 'local_deterministic' as const,
    permissionsRequired: [] as 'camera'[],
  };
  let def: ChallengeDefinition;
  switch (id) {
    case 'morning-math': {
      const count = difficulty === 'hard' ? 3 : 2;
      const steps = Array.from({ length: count }, (_, i) => mathStep(`m${i + 1}`, difficulty, rand));
      def = { ...base, title: 'Morning maths', difficulty, maxDurationSeconds: 60, steps };
      break;
    }
    case 'mate-in-one': {
      const pool = CHESS_PUZZLES.filter((p) => p.difficulty === (difficulty === 'hard' ? 'medium' : difficulty));
      const puzzle = pool[Math.floor(rand() * pool.length)] ?? CHESS_PUZZLES[0]!;
      const side = puzzle.fen.split(' ')[1] === 'b' ? 'Black' : 'White';
      def = {
        ...base,
        title: 'Mate in one',
        difficulty: puzzle.difficulty,
        maxDurationSeconds: 90,
        steps: [{ type: 'chess', id: 'c1', prompt: `${side} to move and checkmate in one.`, fen: puzzle.fen, solutions: [...puzzle.solutions], goal: 'mate_in_1', puzzleId: puzzle.id }],
      };
      break;
    }
    case 'memory-sequence': {
      const n = difficulty === 'easy' ? 4 : difficulty === 'medium' ? 5 : 7;
      const words = [...MEMORY_WORDS];
      const items: string[] = [];
      while (items.length < n) items.push(words.splice(Math.floor(rand() * words.length), 1)[0]!);
      def = {
        ...base,
        title: 'Memory sequence',
        difficulty,
        maxDurationSeconds: 60,
        steps: [{ type: 'memory_order', id: 'o1', prompt: 'Remember these, then tap them in the same order.', items, displaySeconds: 6 }],
      };
      break;
    }
    case 'guitar-practice':
      def = {
        ...base,
        title: 'Guitar practice',
        difficulty: 'easy',
        maxDurationSeconds: 600 + 30,
        steps: [
          { type: 'timer', id: 't1', prompt: 'Ten minutes with your guitar. Scales, a song, anything.', seconds: 600, activity: 'practice' },
          { type: 'self_attested', id: 'a1', prompt: 'How did it go?', items: ['I practised'], category: 'practice' },
        ],
      };
      break;
    case 'movement-break':
      def = {
        ...base,
        title: 'Movement break',
        difficulty: 'easy',
        maxDurationSeconds: 180,
        steps: [
          { type: 'timer', id: 't1', prompt: 'Two minutes of gentle stretching, at your own pace.', seconds: 120, activity: 'stretch' },
          {
            type: 'self_attested',
            id: 'a1',
            prompt: 'Done moving?',
            items: ['I stretched in a way that felt comfortable'],
            category: 'movement',
            alternative: 'Seated option: roll your shoulders and stretch your arms from your chair.',
          },
        ],
      };
      break;
    case 'gentle-wake':
      def = {
        ...base,
        title: 'Gentle wake',
        difficulty: 'easy',
        maxDurationSeconds: 60,
        steps: [{ type: 'self_attested', id: 'a1', prompt: 'Small steps to start the day', items: ['Curtains or blinds open', 'Sitting up', 'Phone off silent'], category: 'general' }],
      };
      break;
  }
  return withDerivedFields(def);
}
