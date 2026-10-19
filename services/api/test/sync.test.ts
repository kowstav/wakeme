import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createHarness, type Harness, type TestUser } from './support/harness.ts';
import { appendSync } from '../src/modules/identity/repo.ts';
import { afterRestore } from '../src/modules/sync/repo.ts';
import { retention } from '../src/worker/periodic.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const sync = async (user: TestUser, cursor?: string) =>
  (await h.req('GET', `/v1/sync${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, { user })).body as {
    changes: { entity: string; id: string; op: string; data: any }[];
    cursor: string;
    hasMore: boolean;
    bootstrap: boolean;
    resetRequired: boolean | null;
  };

const cursorAt = (seq: number) => Buffer.from(`s:${seq}`, 'utf8').toString('base64url');
const seqOf = (cursor: string) => Number(Buffer.from(cursor, 'base64url').toString('utf8').slice(2));
const rename = (user: TestUser, displayName: string) => h.req('PATCH', '/v1/me', { user, body: { displayName } });

describe('sync feed', () => {
  it('starts a fresh phone at the head, then delivers each later change once, latest state only', async () => {
    const maya = await h.createUser('Maya');
    const boot = await sync(maya);
    assert.equal(boot.bootstrap, true);
    assert.deepEqual(boot.changes, []);
    await rename(maya, 'Maya B');
    await rename(maya, 'Maya C');
    const next = await sync(maya, boot.cursor);
    assert.equal(next.bootstrap, false);
    const me = next.changes.filter((c) => c.entity === 'me');
    assert.equal(me.length, 1, 'one change per entity per page');
    assert.equal(me[0]!.data.displayName, 'Maya C');
    const again = await sync(maya, next.cursor);
    assert.deepEqual(again.changes, []);
    assert.equal(again.cursor, next.cursor);
  });

  it('pages with hasMore, in the order changes were made', async () => {
    const maya = await h.createUser('Maya');
    const boot = await sync(maya);
    const ids = Array.from({ length: 5 }, () => randomUUID());
    await h.db.tx(async (q) => {
      for (const id of ids) await appendSync(q, [maya.id], 'circle', id);
    });
    const p1 = (await h.req('GET', `/v1/sync?cursor=${boot.cursor}&limit=3`, { user: maya })).body;
    assert.deepEqual([p1.changes.map((c: { id: string }) => c.id), p1.hasMore], [ids.slice(0, 3), true]);
    const p2 = (await h.req('GET', `/v1/sync?cursor=${p1.cursor}&limit=3`, { user: maya })).body;
    assert.deepEqual([p2.changes.map((c: { id: string }) => c.id), p2.hasMore], [ids.slice(3), false]);
  });

  it('after retention purged its position, a phone re-fetches once, then continues', async () => {
    const maya = await h.createUser('Maya');
    const seen = await sync(maya, (await sync(maya)).cursor);
    await rename(maya, 'Old news');
    await h.db.query('update sync_events set created_at = $2 where user_id = $1', [maya.id, new Date(h.clock.now.getTime() - 60 * 86_400_000)]);
    await retention(h.deps);
    const reset = await sync(maya, seen.cursor);
    assert.deepEqual([reset.resetRequired, reset.bootstrap], [true, true]);
    const settled = await sync(maya, reset.cursor);
    assert.equal(settled.resetRequired, null, 'no reset loop');
    await rename(maya, 'Fresh');
    assert.equal((await sync(maya, settled.cursor)).changes.find((c) => c.entity === 'me')?.data.displayName, 'Fresh');
  });

  it('a cursor past the head (a restore without the post-restore step) re-fetches instead of waiting', async () => {
    const maya = await h.createUser('Maya');
    const head = seqOf((await sync(maya)).cursor);
    const r = await sync(maya, cursorAt(head + 10_000));
    assert.deepEqual([r.resetRequired, seqOf(r.cursor)], [true, head]);
  });

  it('after a database restore every phone re-fetches once, then receives new changes', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const boot = await sync(maya);
    const alexPhone = await sync(alex);
    await rename(maya, 'Lost in the restore');
    const mayaPhone = await sync(maya, boot.cursor);
    assert.equal(mayaPhone.changes.length, 1);
    // The restored database never saw that change.
    await h.db.query('delete from sync_events where user_id = $1 and seq > $2', [maya.id, seqOf(boot.cursor)]);

    const floor = await afterRestore(h.db);
    for (const [user, phone] of [[maya, mayaPhone], [alex, alexPhone]] as const) {
      const reset = await sync(user, phone.cursor);
      assert.deepEqual([reset.resetRequired, seqOf(reset.cursor)], [true, floor], user.name);
      assert.equal((await sync(user, reset.cursor)).resetRequired, null, `${user.name}: no reset loop`);
    }
    const newPhone = await sync(maya);
    assert.equal(seqOf(newPhone.cursor), floor, 'a phone that signs in now starts at the floor');
    await rename(maya, 'After the restore');
    const fresh = await sync(maya, newPhone.cursor);
    assert.equal(fresh.changes.find((c) => c.entity === 'me')?.data.displayName, 'After the restore');
    assert.ok(seqOf(fresh.cursor) > floor);
  });
});

describe('sync feed ordering', () => {
  // Two transactions append for the same user; the one that started first commits last.
  // Needs real connections running at once (TEST_DATABASE_URL); the embedded engine has one.
  it('never skips a change that commits after a later one', { skip: !process.env['TEST_DATABASE_URL'] && 'needs a PostgreSQL server' }, async () => {
    const maya = await h.createUser('Maya');
    const start = (await sync(maya)).cursor;
    const [slowId, fastId] = [randomUUID(), randomUUID()];
    let inserted!: () => void;
    let release!: () => void;
    const insertedP = new Promise<void>((r) => (inserted = r));
    const releaseP = new Promise<void>((r) => (release = r));
    const slow = h.db.tx(async (q) => {
      await appendSync(q, [maya.id], 'alarm_plan', slowId);
      inserted();
      await releaseP;
    });
    let first;
    try {
      await insertedP;
      await h.db.tx((q) => appendSync(q, [maya.id], 'alarm_plan', fastId));
      first = await sync(maya, start);
    } finally {
      release();
      await slow;
    }
    assert.deepEqual(first.changes.map((c) => c.id), [fastId]);
    const second = await sync(maya, first.cursor);
    assert.deepEqual(second.changes.map((c) => c.id), [slowId], 'the slower transaction is delivered, not skipped');
  });
});
