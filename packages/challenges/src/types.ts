import type { ChallengeStepType, Difficulty } from '@wakeme/policy';

export type StepSource = 'creator_provided' | 'template' | 'generated_deterministic';

export interface MultipleChoiceStep {
  type: 'multiple_choice';
  id: string;
  prompt: string;
  options: string[];
  correctIndex: number;
  source: StepSource;
  factId?: string;
}

export interface ShortAnswerStep {
  type: 'short_answer';
  id: string;
  prompt: string;
  acceptedAnswers: string[];
  source: StepSource;
  factId?: string;
}

export type MathOperator = '+' | '-' | '*' | '/';

export interface MathStep {
  type: 'math';
  id: string;
  operands: number[];
  operator: MathOperator;
  answer: number;
}

export interface MemoryOrderStep {
  type: 'memory_order';
  id: string;
  prompt: string;
  items: string[];
  displaySeconds: number;
}

export interface ChessStep {
  type: 'chess';
  id: string;
  prompt: string;
  fen: string;
  solutions: string[];
  goal: 'mate_in_1';
  puzzleId?: string;
}

export interface TimerStep {
  type: 'timer';
  id: string;
  prompt: string;
  seconds: number;
  activity: 'practice' | 'rest' | 'reading' | 'stretch' | 'other';
}

export interface QrCodeStep {
  type: 'qr_code';
  id: string;
  prompt: string;
  codeHash: string;
}

export interface SelfAttestedStep {
  type: 'self_attested';
  id: string;
  prompt: string;
  items: string[];
  category: 'general' | 'movement' | 'hygiene' | 'practice';
  alternative?: string;
}

export type ChallengeStep =
  | MultipleChoiceStep
  | ShortAnswerStep
  | MathStep
  | MemoryOrderStep
  | ChessStep
  | TimerStep
  | QrCodeStep
  | SelfAttestedStep;

export interface ChallengeDefinition {
  schemaVersion: 1;
  title: string;
  kind: 'sequence';
  difficulty: Difficulty;
  maxDurationSeconds: number;
  steps: ChallengeStep[];
  verification: 'local_deterministic' | 'self_report';
  fallback: { type: 'accessible_checkin'; prompt?: string };
  permissionsRequired: 'camera'[];
}

export type { ChallengeStepType, Difficulty };

export interface ValidationIssue {
  /** JSON-pointer-like path into the definition */
  path: string;
  code: string;
  /** Safe, user-facing explanation */
  message: string;
}

export type SafetyCategory =
  | 'code_or_markup'
  | 'link'
  | 'rapid_consumption'
  | 'breath_or_choking'
  | 'pain_or_self_harm'
  | 'substances'
  | 'dangerous_physical'
  | 'sexual'
  | 'humiliation_or_coercion'
  | 'credentials'
  | 'financial'
  | 'location_exposure'
  | 'driving';

export interface SafetyFinding {
  category: SafetyCategory;
  path: string;
  message: string;
}
