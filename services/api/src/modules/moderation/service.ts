import Type from 'typebox';
import type { Queryable } from '../../db/db.ts';

export const REPORT_REASONS = ['spam', 'harassment', 'unwanted_alerts', 'unsafe_challenge', 'impersonation', 'inappropriate_content', 'other'] as const;
export const ReportReason = Type.Union(REPORT_REASONS.map((r) => Type.Literal(r)));
export type ReportReasonT = (typeof REPORT_REASONS)[number];

/**
 * Reports keep minimal evidence: a copy of the reported item's text when the
 * reporter could see it, never the reporter's other conversations or location.
 */
export async function createReport(
  q: Queryable,
  r: { reporterId: string; targetUserId: string | null; targetKind: 'user' | 'message' | 'challenge' | 'media' | 'command'; targetId: string; reason: ReportReasonT; note: string | null; evidence: Record<string, unknown> },
): Promise<string> {
  const res = await q.query<{ id: string }>(
    `insert into reports (reporter_id, target_user_id, target_kind, target_id, reason, note, evidence) values ($1, $2, $3, $4, $5, $6, $7::jsonb) returning id`,
    [r.reporterId, r.targetUserId, r.targetKind, r.targetId, r.reason, r.note, JSON.stringify(r.evidence)],
  );
  return res.rows[0]!.id;
}
