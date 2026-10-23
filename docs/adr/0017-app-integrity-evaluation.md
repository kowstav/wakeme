# ADR 0017: App Attest and Play Integrity evaluation

Evaluated 2026-10-20. Decision: add neither as an enforcement gate in the unconfigured prototype.
This is an evaluation, not an attestation implementation or a production security sign-off.

Wake Me already authenticates the account, signs phone-affecting requests with a device key,
checks recipient-owned versioned grants on the server and again on the recipient phone, bounds
requests, and deduplicates receipts. A device signature demonstrates possession of that key; it
does not prove that the official, unmodified application is running. Automated account creation,
AI-credit farming and modified clients remain reasons to consider an additional signal.

For Android, evaluate **Play Integrity standard requests** in an owner-configured Play test track.
Bind the token's request hash to the canonical API action and verify it on the backend, including
package/certificate identity and freshness. Keep the existing authorization controls. Start with
observation, measure missing/unevaluated verdicts, false rejections and latency, then consider
bounded additional friction for online abuse-prone actions such as AI generation and new device
registration. Never put an online integrity request on ringing, stopping, escape, revocation,
existing local alarms or safety-session shutdown. Do not treat one integrity verdict as proof
that a person or request is safe.

For iOS, evaluate **App Attest** with server-issued single-use challenges, server-side validation,
and per-key assertion counters. Keep development and distribution environments separate. Roll
out gradually with a documented unsupported/service-outage path. The owner's registered App ID,
signing configuration and a backend implementation are prerequisites.

Before enforcement, record real test-track devices, supported/unsupported environments, replay
and body-substitution tests, service outages, quota/operational costs, privacy retention and an
appeal/recovery path. Those measurements and the owner's distribution configuration are absent;
reporting this app as attested would be incorrect. No new SDK or data collection was added by this
decision. Revisit before a public pilot after abuse and device findings are available.

Primary references:

- [Google: Play Integrity overview](https://developer.android.com/google/play/integrity/overview)
- [Google: standard requests and content binding](https://developer.android.com/google/play/integrity/standard)
- [Apple: establishing your app's integrity](https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity)
- [Apple: validating apps that connect to your server](https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-server)
