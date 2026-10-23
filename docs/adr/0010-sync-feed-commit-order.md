# 0010 — Sync feed positions follow commit order

- Status: Accepted (2026-10-19)

## Context

Phones converge through `GET /v1/sync`: changes with a sequence number above the phone's
cursor. Push is only a hint. The sequence number was fixed when a change row was inserted.
Two transactions appending for the same person could commit in the opposite order; a phone
that synced between the two commits moved its cursor past the slower change and never
received it (a cancelled routine could keep ringing, a revoked grant could stay cached).
`test/sync.test.ts` reproduced this on PostgreSQL 17.11 with two concurrent transactions.

## Decision

- Migration 0003: a deferred constraint trigger renumbers each change row at commit under
  one transaction-scoped advisory lock (key 7428935201). The lock is held from the first
  renumbering until the commit finishes, so every later commit gets a higher number than
  every change a reader can already see. Inserts run in parallel; only the commit tail is
  serialised.
- Cursors never exceed the person's feed position (latest change or floor). A cursor below
  the floor (retention) or above the position (a database restore) answers
  `resetRequired: true`: the app re-fetches its lists and continues from the position.
- After a restore, `npm run post-restore -w @wakeme/api` moves the sequence and every floor
  past any cursor issued before the incident (docs/runbooks/restore.md).

## Consequences

- Each change row is written twice (insert, renumber) and commits that carry changes queue
  briefly on one lock. That is acceptable for launch volumes; revisit with a load test if
  commit latency under contention matters, for example with per-person feeds.
- The fix is server-only; cursor format and client behaviour are unchanged.
