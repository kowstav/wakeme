import type { Difficulty } from '@wakeme/policy';

// Original, hand-composed mate-in-one positions (classic patterns, no third-party
// dataset). Every entry is checked by test/chess.test.ts with move generation:
// the listed solutions must be exactly the set of mating moves.

export interface ChessPuzzle {
  id: string;
  fen: string;
  solutions: string[];
  difficulty: Difficulty;
  /** Plain-language hint shown in the accessible alternative. */
  theme: string;
}

export const CHESS_PUZZLES: readonly ChessPuzzle[] = [
  { id: 'back-rank-rook', fen: '6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1', solutions: ['a1a8'], difficulty: 'easy', theme: 'Back rank' },
  { id: 'back-rank-queen', fen: '6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1', solutions: ['d1d8'], difficulty: 'easy', theme: 'Back rank' },
  { id: 'back-rank-black-rook', fen: 'r5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1', solutions: ['a8a1'], difficulty: 'easy', theme: 'Back rank' },
  { id: 'back-rank-black-queen', fen: '3q2k1/8/8/8/8/8/5PPP/6K1 b - - 0 1', solutions: ['d8d1'], difficulty: 'easy', theme: 'Back rank' },
  { id: 'queen-corner', fen: '7k/8/6K1/8/8/8/8/1Q6 w - - 0 1', solutions: ['b1b8'], difficulty: 'easy', theme: 'King and queen' },
  { id: 'rook-ladder-king', fen: 'k7/8/1K6/8/8/8/8/7R w - - 0 1', solutions: ['h1h8'], difficulty: 'easy', theme: 'King and rook' },
  { id: 'opposition-rook', fen: '4k3/R7/4K3/8/8/8/8/8 w - - 0 1', solutions: ['a7a8'], difficulty: 'easy', theme: 'King and rook' },
  { id: 'two-rooks', fen: '6k1/R7/8/8/8/8/8/1R4K1 w - - 0 1', solutions: ['b1b8'], difficulty: 'easy', theme: 'Two rooks' },
  { id: 'queen-kiss', fen: '6k1/5p1p/5PpQ/8/8/8/8/6K1 w - - 0 1', solutions: ['h6g7'], difficulty: 'medium', theme: 'Supported queen' },
  { id: 'queen-kiss-black', fen: '6k1/8/8/8/8/5pPq/5P1P/6K1 b - - 0 1', solutions: ['h3g2'], difficulty: 'medium', theme: 'Supported queen' },
  { id: 'smothered', fen: '6rk/6pp/8/6N1/8/8/8/6K1 w - - 0 1', solutions: ['g5f7'], difficulty: 'medium', theme: 'Smothered' },
  { id: 'arabian', fen: '7k/1R6/5N2/8/8/8/8/6K1 w - - 0 1', solutions: ['b7h7'], difficulty: 'medium', theme: 'Rook and knight' },
  { id: 'promotion', fen: 'k7/2P5/1K6/8/8/8/8/8 w - - 0 1', solutions: ['c7c8q', 'c7c8r'], difficulty: 'medium', theme: 'Promotion' },
  { id: 'scholars', fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4', solutions: ['h5f7'], difficulty: 'medium', theme: 'Weak f7' },
  { id: 'fools', fen: 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2', solutions: ['d8h4'], difficulty: 'medium', theme: 'Exposed king' },
];

export function puzzleById(id: string): ChessPuzzle | undefined {
  return CHESS_PUZZLES.find((p) => p.id === id);
}
