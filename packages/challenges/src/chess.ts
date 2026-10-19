import { Chess, validateFen, type Square } from 'chess.js';

// Chess correctness comes from move generation, never from a model's claim.
// chess.js is BSD-2-Clause licensed; native apps only compare the player's
// move against the server-verified solution set, so no engine ships on-device.

export interface MateInOneAnalysis {
  ok: boolean;
  error?: string;
  /** All mating moves in UCI, sorted. */
  mates: string[];
  sideToMove: 'w' | 'b';
}

function kingSquare(chess: Chess, color: 'w' | 'b'): Square | null {
  const board = chess.board();
  for (const row of board) {
    for (const piece of row) {
      if (piece && piece.type === 'k' && piece.color === color) return piece.square;
    }
  }
  return null;
}

export function analyzeMateInOne(fen: string): MateInOneAnalysis {
  const v = validateFen(fen);
  const parts = fen.split(' ');
  const sideToMove = parts[1] === 'b' ? 'b' : 'w';
  if (!v.ok) return { ok: false, error: v.error ?? 'invalid FEN', mates: [], sideToMove };
  let chess: Chess;
  try {
    chess = new Chess(fen);
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'invalid FEN', mates: [], sideToMove };
  }
  const opponent = sideToMove === 'w' ? 'b' : 'w';
  const oppKing = kingSquare(chess, opponent);
  if (!oppKing) return { ok: false, error: 'missing king', mates: [], sideToMove };
  if (chess.isAttacked(oppKing, sideToMove)) {
    return { ok: false, error: 'side not to move is in check (illegal position)', mates: [], sideToMove };
  }
  if (chess.isGameOver()) return { ok: false, error: 'position is already finished', mates: [], sideToMove };

  const mates: string[] = [];
  for (const m of chess.moves({ verbose: true })) {
    chess.move({ from: m.from, to: m.to, promotion: m.promotion });
    if (chess.isCheckmate()) mates.push(`${m.from}${m.to}${m.promotion ?? ''}`);
    chess.undo();
  }
  mates.sort();
  return { ok: true, mates, sideToMove };
}

/** True when `solutions` is exactly the complete set of mating moves. */
export function verifyMateInOneSolutions(fen: string, solutions: readonly string[]): { ok: boolean; error?: string } {
  const a = analyzeMateInOne(fen);
  if (!a.ok) return { ok: false, error: a.error ?? 'invalid position' };
  if (a.mates.length === 0) return { ok: false, error: 'position has no mate in one' };
  const given = [...new Set(solutions.map((s) => s.toLowerCase()))].sort();
  if (given.length !== a.mates.length || given.some((s, i) => s !== a.mates[i])) {
    return { ok: false, error: `solutions must be exactly the mating moves (${a.mates.length})` };
  }
  return { ok: true };
}
