# 0003 — Messaging between accepted friends is not a grant

- Status: Accepted (2026-10-19)
- Context: earlier drafts listed `message.send` as a capability.

## Decision

Accepted friends can message each other without a grant. Messages cannot ring a phone:
they arrive as ordinary notifications on the Messages channel and never contain the text.
Blocking ends the conversation and hides the blocked person's messages; reporting is
available on every message from someone else.

## Rationale

A message has no phone-control effect, so a per-friend permission adds friction without
protecting anything. Abuse is handled by block, report and rate limits instead.

## Consequences

- The grant editor has no messaging row.
- If messages ever gain an attention effect (sound, bypass), that effect needs a capability.
