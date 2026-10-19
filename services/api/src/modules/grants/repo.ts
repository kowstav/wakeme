import Type from 'typebox';
import type { Capability, GrantLimits, GrantSnapshot } from '@wakeme/policy';
import type { Queryable } from '../../db/db.ts';
import { Instant, Nullable } from '../../http/route.ts';

export interface GrantRow {
  id: string;
  grantor_id: string;
  grantee_id: string;
  version: number;
  capabilities: Capability[];
  limits: GrantLimits;
  valid_from: Date;
  valid_until: Date;
  paused: boolean;
  revoked_at: Date | null;
  revoked_reason: string | null;
  last_used_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

export function toSnapshot(g: GrantRow): GrantSnapshot {
  return {
    id: g.id,
    grantorId: g.grantor_id,
    granteeId: g.grantee_id,
    version: g.version,
    capabilities: g.capabilities,
    limits: g.limits,
    validFrom: g.valid_from.toISOString(),
    validUntil: g.valid_until.toISOString(),
    paused: g.paused,
    revokedAt: g.revoked_at ? g.revoked_at.toISOString() : null,
  };
}

export const GrantDto = Type.Object({
  id: Type.String(),
  grantorId: Type.String(),
  granteeId: Type.String(),
  version: Type.Integer(),
  capabilities: Type.Array(Type.String()),
  limits: Type.Unknown(),
  validFrom: Instant,
  validUntil: Instant,
  paused: Type.Boolean(),
  revokedAt: Nullable(Instant),
  lastUsedAt: Nullable(Instant),
  updatedAt: Instant,
});

export function grantDto(g: GrantRow): Type.Static<typeof GrantDto> {
  return {
    id: g.id,
    grantorId: g.grantor_id,
    granteeId: g.grantee_id,
    version: g.version,
    capabilities: g.capabilities,
    limits: g.limits,
    validFrom: g.valid_from.toISOString(),
    validUntil: g.valid_until.toISOString(),
    paused: g.paused,
    revokedAt: g.revoked_at ? g.revoked_at.toISOString() : null,
    lastUsedAt: g.last_used_at ? g.last_used_at.toISOString() : null,
    updatedAt: g.updated_at.toISOString(),
  };
}

/** The live grant from grantor to grantee, if any. */
export async function liveGrant(q: Queryable, grantorId: string, granteeId: string, lock = false): Promise<GrantRow | null> {
  const r = await q.query<GrantRow>(
    `select * from grants where grantor_id = $1 and grantee_id = $2 and revoked_at is null${lock ? ' for update' : ''}`,
    [grantorId, granteeId],
  );
  return r.rows[0] ?? null;
}

export async function recordGrantVersion(q: Queryable, grantId: string, changedBy: string | null): Promise<void> {
  await q.query(
    `insert into grant_versions (grant_id, version, capabilities, limits, valid_from, valid_until, paused, revoked, changed_by)
     select id, version, capabilities, limits, valid_from, valid_until, paused, revoked_at is not null, $2 from grants where id = $1
     on conflict (grant_id, version) do nothing`,
    [grantId, changedBy],
  );
}
