import Type from 'typebox';
import { unprocessable } from '../../http/errors.ts';
import { Nullable, Obj, route, type RouteDef } from '../../http/route.ts';
import { grantDto, type GrantRow } from '../grants/repo.ts';
import { planDto } from '../alarms/routes.ts';
import { commandDto, type CommandRow } from '../commands/routes.ts';
import type { PlanRow } from '../alarms/service.ts';
import { meDto } from '../identity/routes.ts';

function encodeCursor(seq: number): string {
  return Buffer.from(`s:${seq}`, 'utf8').toString('base64url');
}

function decodeCursor(c: string): number {
  const m = /^s:(\d{1,19})$/.exec(Buffer.from(c, 'base64url').toString('utf8'));
  if (!m) throw unprocessable('invalid_cursor', 'Sync cursor is invalid; start a fresh sync.');
  return Number(m[1]);
}

const Change = Type.Object({
  entity: Type.String(),
  id: Type.String(),
  op: Type.String(),
  /** Current state for grants, routines, commands and profile; null for deletes or for entities fetched via their list endpoint. */
  data: Type.Unknown(),
});

/**
 * Durable, user-scoped change feed. Push and foreground sockets are only
 * hints; a phone that missed every push still converges by calling this.
 * Without a cursor it returns the current head so the app can bootstrap
 * from the list endpoints and continue from there.
 */
export const syncRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/v1/sync',
    operationId: 'sync',
    summary: 'Changes since a cursor (grants, routines, requests, profile, friendships, circles, challenges, conversations).',
    tags: ['sync'],
    auth: 'user',
    query: Obj({ cursor: Type.Optional(Type.String({ maxLength: 64 })), limit: Type.Optional(Type.Integer({ minimum: 1, maximum: 200 })) }),
    response: Type.Object({ changes: Type.Array(Change), cursor: Type.String(), hasMore: Type.Boolean(), bootstrap: Type.Boolean(), resetRequired: Nullable(Type.Boolean()) }),
    handler: async ({ query, user, deps, now }) => {
      if (!query.cursor) {
        const head = await deps.db.query<{ seq: number | string | null }>('select max(seq) as seq from sync_events where user_id = $1', [user.id]);
        return { changes: [], cursor: encodeCursor(Number(head.rows[0]?.seq ?? 0)), hasMore: false, bootstrap: true, resetRequired: null };
      }
      const after = decodeCursor(query.cursor);
      const floor = await deps.db.query<{ floor_seq: number | string }>('select floor_seq from sync_floor where user_id = $1', [user.id]);
      if (floor.rows[0] && after < Number(floor.rows[0].floor_seq)) {
        // Events older than retention were purged: the app must re-fetch lists, then continue from head.
        const head = await deps.db.query<{ seq: number | string | null }>('select max(seq) as seq from sync_events where user_id = $1', [user.id]);
        return { changes: [], cursor: encodeCursor(Number(head.rows[0]?.seq ?? floor.rows[0].floor_seq)), hasMore: false, bootstrap: true, resetRequired: true };
      }
      const limit = query.limit ?? 100;
      const r = await deps.db.query<{ seq: number | string; entity: string; entity_id: string; op: string }>(
        'select seq, entity, entity_id, op from sync_events where user_id = $1 and seq > $2 order by seq limit $3',
        [user.id, after, limit + 1],
      );
      const rows = r.rows.slice(0, limit);
      const hasMore = r.rows.length > limit;
      // Keep only the latest op per entity in this page.
      const latest = new Map<string, { entity: string; id: string; op: string }>();
      for (const row of rows) latest.set(`${row.entity}:${row.entity_id}`, { entity: row.entity, id: row.entity_id, op: row.op });
      const changes = [];
      for (const c of latest.values()) {
        let data: unknown = null;
        if (c.op === 'upsert') {
          if (c.entity === 'grant') {
            const g = await deps.db.query<GrantRow>('select * from grants where id = $1 and (grantor_id = $2 or grantee_id = $2)', [c.id, user.id]);
            data = g.rows[0] ? grantDto(g.rows[0]) : null;
          } else if (c.entity === 'alarm_plan') {
            const p = await deps.db.query<PlanRow>('select * from alarm_plans where id = $1 and (owner_id = $2 or creator_id = $2)', [c.id, user.id]);
            data = p.rows[0] ? await planDto(deps.db, p.rows[0], now) : null;
          } else if (c.entity === 'command') {
            const cmd = await deps.db.query<CommandRow>('select * from commands where id = $1 and (actor_id = $2 or target_id = $2)', [c.id, user.id]);
            data = cmd.rows[0] ? await commandDto(deps.db, cmd.rows[0], now) : null;
          } else if (c.entity === 'me') {
            data = await meDto(deps.db, user, now);
          }
        }
        changes.push({ entity: c.entity, id: c.id, op: c.op, data });
      }
      const last = rows.at(-1);
      return { changes, cursor: encodeCursor(last ? Number(last.seq) : after), hasMore, bootstrap: false, resetRequired: null };
    },
  }),
];
