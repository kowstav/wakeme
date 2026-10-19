import type { ChallengeDefinition, ChallengeStep } from './types.ts';
import { deriveFacts } from './validate.ts';

const STEP_LABELS: Record<ChallengeStep['type'], string> = {
  multiple_choice: 'Multiple-choice question',
  short_answer: 'Short typed answer',
  math: 'Quick sum',
  memory_order: 'Memory sequence',
  chess: 'Chess puzzle',
  timer: 'Timed activity',
  qr_code: 'Scan or type a code',
  self_attested: 'Self-check list',
};

export interface RecipientPreview {
  title: string;
  difficulty: ChallengeDefinition['difficulty'];
  steps: { type: ChallengeStep['type']; label: string; seconds?: number }[];
  estimatedSeconds: number;
  maxDurationSeconds: number;
  permissionsRequired: 'camera'[];
  verification: ChallengeDefinition['verification'];
  verificationLabel: string;
  escape: string;
  practiceExample: string;
}

/**
 * What a recipient sees before accepting a routine. Never includes questions,
 * options or answers, so an inside-joke quiz keeps its surprise.
 */
export function recipientPreview(def: ChallengeDefinition): RecipientPreview {
  const facts = deriveFacts(def);
  const firstType = def.steps[0]?.type;
  const practice =
    firstType === 'math'
      ? 'Example: 6 + 7 = ?'
      : firstType === 'chess'
        ? 'Example: find the one move that checkmates.'
        : firstType === 'memory_order'
          ? 'Example: remember "tea, keys, bus", then tap them in order.'
          : firstType === 'qr_code'
            ? 'Example: scan the code you stuck somewhere, or type the letters under it.'
            : firstType === 'timer' || firstType === 'self_attested'
              ? 'Example: tick each item when it is done.'
              : 'Example: "What colour is the sky on a clear day?"';
  return {
    title: def.title,
    difficulty: def.difficulty,
    steps: def.steps.map((s) => (s.type === 'timer' ? { type: s.type, label: STEP_LABELS[s.type], seconds: s.seconds } : { type: s.type, label: STEP_LABELS[s.type] })),
    estimatedSeconds: facts.estimatedSeconds,
    maxDurationSeconds: def.maxDurationSeconds,
    permissionsRequired: facts.permissionsRequired,
    verification: facts.verification,
    verificationLabel: facts.verification === 'local_deterministic' ? 'Checked by the app on your phone' : 'You confirm it yourself',
    escape: 'You can always stop the sound and use "I need to stop" without solving anything.',
    practiceExample: practice,
  };
}
