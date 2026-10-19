# Wake Me threat model

Last reviewed: 2026-10-19. Baseline: OWASP MASVS (a checklist, not a certification).
Source: 02-ENGINEERING-BLUEPRINT.md §10. Status words follow PROGRESS.md: **verified** (a named
automated test passes), **implemented** (code exists, no direct test), **gap** (not done).
Tests named below are in `services/api/test/*.test.ts` unless stated otherwise. Nothing here
has had an external security review yet (a launch gate).

## Assets

Phone attention (sound, alarms), grants, location samples, messages, challenge content and
answers, device keys and session tokens, billing state, audit logs.

## Threats, controls and evidence

| Threat | Control in this codebase | Evidence | Status |
|---|---|---|---|
| A friend repeatedly wakes someone | Recipient-owned limits per capability (rate, windows, sounds, duration); per-recipient sound budget across all senders; quiet hours; pause; block/revoke | `enforces the per-friend hourly limit with Retry-After`; `five friends together cannot exceed the recipient sound budget`; `concurrent senders cannot race past the recipient budget` (run on PostgreSQL 17.11); `respects quiet hours and wake windows as explicit consent`; `pausing a friend cancels their queued requests` | verified |
| Object-ID substitution | Authorization on every object read and write; one indistinguishable not-found for others' objects | `cross-user access: strangers and third parties learn nothing and cannot forge receipts`; `the recipient sees an answer-free preview; strangers see nothing`; messaging and safety suites | verified |
| Grant escalation | Only the grantor writes a grant; versioned edits; strict schemas reject unknown fields | `only the grantor can edit a grant; edits need the current version`; `rejects out-of-range or unknown grant terms`; `every write rejects unknown body fields`; `a friend cannot exceed what the owner granted` | verified |
| Friendship or circle implies power | No capability without an explicit grant | `friendship alone grants no capability`; `circle membership never grants phone permissions`; `a recipient grant is directional` | verified |
| Revoked action still delivered | Worker re-checks before dispatch; phone re-validates against its cached grant (ADR 0002) | `revocation racing a queued command: the worker re-checks and never sends`; local-execution fixtures on server, Android `:core`, iOS `WakeMeCore` | verified (server, fixtures); device behaviour not yet observed |
| Replay or duplicate delivery | Idempotency keys, command IDs, expiry, processed-ID check on the phone; signed device requests are single use | `idempotency: same key and payload returns the same command`; `an expired nudge is never sent late`; `a replayed signed request is rejected`; `duplicate` local-execution fixture | verified |
| Forged device receipt | Device key bound to an authenticated registration; receipts and phone-affecting writes are device-signed over method, path, timestamp and body hash | `device signatures: required, bound to body, single use, clock-checked, revocable`; `device receipts and phone-affecting writes are device-signed`; iOS `testSignedRequestsVerifyOverTheCanonicalString` | verified (server and client signing); key storage on devices: Android Keystore, iOS Secure Enclave — implemented, not device-tested |
| Token theft | Short-lived access tokens; refresh token in Keychain / encrypted store; sign-out revokes on the identity provider; deletion needs a recent sign-in | `deletion needs a recent sign-in…`; `high-impact capabilities need a recent sign-in` | verified (server); client storage implemented |
| Malicious upload or URL | Upload size/type/duration checks, hash verification, path-traversal refusal; no server-side fetch of user URLs | `verifies size, hash, format and duration before a sound can be used`; `refuses path traversal in object keys`; `reads WAV and M4A durations and rejects malformed files`; `enforces upload limits` | verified |
| AI prompt injection | Prompt is data; no tools or secrets; schema validator; safe primitive allowlist; maths recomputed; chess only from the verified library | `cannot smuggle permissions, code or unknown step types`; `refuses unsafe requests before calling the model`; `recomputes maths and ignores the model`; `takes chess only from the verified library`; 59-case safety eval set | verified (with a scripted model; not against the live model) |
| Challenge tampering on the phone | ES256-signed revisions verified before offline install (ADR 0008) | 7 shared envelope vectors on server, Android, iOS | verified |
| Signing-key compromise or rotation | Re-sign every published revision under a new key (content unchanged, enforced by trigger); retired public keys published only while needed; phones refresh their key list on an unknown key ID | `re-signs published revisions under a new key without changing their content`; `publishes retired verification keys after a signing-key rotation, current key first`; `config: retired challenge verification keys`; runbook `docs/runbooks/key-rotation.md` | verified (server); Android refresh compiled, not run; drill not yet exercised |
| Stale permissions on a phone after a missed change or a restore | Sync positions follow commit order (ADR 0010); cursors outside the feed force a re-fetch; post-restore step | `never skips a change that commits after a later one` (PostgreSQL 17.11); `after a database restore every phone re-fetches once, then receives new changes`; `after retention purged its position…` | verified (server); restore drill not yet exercised |
| Stalking or coercion | No standing location read; owner-started time-boxed sessions; visible indicator with Stop; per-viewer revocation; stale samples rejected; samples encrypted at rest (ADR 0004) | `shares only with chosen friends, encrypted at rest, with capture time and accuracy`; `rejects stale samples; revoking a viewer or ending the session cuts access`; `blocking atomically revokes grants in both directions` | verified (server); Android service implemented, not device-tested |
| Harassment through messages | Block ends the conversation; reports with minimal evidence; pushes never contain message text | `friends can message; pushes never contain the text`; `client ids make sends idempotent; blocking ends the conversation`; `reports keep minimal evidence…` | verified |
| Admin compromise | Admin routes need MFA plus a server allowlist; kill switches audited; no routine support access to location or messages | `admin routes are invisible to normal users and require MFA plus the server allowlist`; `kill switches are admin-controlled and audited` | verified (routes); operational MFA/JIT process is a gap |
| Billing spoof | Entitlement from the provider's API, not the webhook body; authenticated webhooks; idempotent ledger; outages never block free features | `a forged webhook body cannot grant Plus`; `rejects unauthenticated webhooks`; `grants Plus from verified state…`; `billing outages never block free features` | verified (against a fake RevenueCat) |
| Push provider misuse | Expiring, correctly addressed pushes; dead tokens marked; a new install never inherits another account's push | `sends a correctly addressed, expiring, localizable alert`; `marks a dead push token…`; `a new phone install never inherits push delivery for a previous account` | verified (fake APNs/FCM servers only) |
| Database role misuse | API enforces authorization; runtime and migration roles separated in infra | Role split in `infra/` | implemented, not exercised in a deployed environment |
| Supply-chain compromise | Pinned dependency versions; lockfile; `npm audit` in CI | CI step "Production dependency audit" | **gap**: GitHub Action tags not pinned to SHAs; no SBOM; no secret scanning configured |
| Sensitive data in logs | Logger scrubs tokens, coordinates, answers, prompts and message bodies | logger `scrub` in `services/api/src/lib/log.ts` | implemented; no dedicated test |

## Known gaps and decisions still open

- External security review (launch gate), with focus on cross-user authority and location boundaries.
- App Attest / Play Integrity as risk signals for high-abuse endpoints: not implemented.
- Screens with sensitive content (location, messages): app-switcher masking / `FLAG_SECURE` not yet evaluated.
- Certificate pinning: not adopted (platform trust only); revisit only after a threat review, with backup pins.
- End-to-end encryption: not implemented and must not be claimed. Messages are server-readable under a disclosed policy.
- Revoking a viewer stops future reads; it cannot erase what a viewer already saw. Consent copy must not promise otherwise.
- Action SHA pinning, SBOM and secret scanning for CI (see Supply-chain row).
