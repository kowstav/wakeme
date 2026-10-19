# Wake Me privacy policy — DRAFT for legal review

> Draft describing actual behaviour on 2026-10-19. Placeholders in [brackets].
> Not legal advice; not published.

## 1. Who we are

Wake Me is provided by [legal entity, address]. Contact: [privacy@…]. Wake Me is for adults
(18 and over).

## 2. The short version

- Your friends can only do what you allow, on your phone, within limits you set. Being
  friends, or in a group, gives nobody any permission.
- We don't sell your data, show ads or track you across other apps.
- Location is shared only when you start a share, only with the people you pick, and only
  until it ends. Coordinates are deleted when the share ends.
- Messages are stored on our servers so they reach your friends; they are not end-to-end
  encrypted. Notifications never contain message text.
- You can export your data and delete your account in the app.

## 3. What we collect and why

| Data | Why | Where it comes from |
|---|---|---|
| Email address (or your Google account email, Android) | Sign-in | You, via our sign-in provider |
| Display name, time zone, language, confirmation that you're 18+ | Your profile; quiet hours and wake windows use your time zone | You |
| Friends, invitations, the permissions you give and receive, and their history | To let friends nudge or wake you only as you allowed, and to show you who can do what | You and your friends |
| Requests (quacks, wake-up requests, check-ins): who sent them, when, delivery status | To deliver them, enforce your limits, and show honest status ("their phone received it") | Your and your friends' phones |
| Routines (time, days, sound, challenge) and whether your phone accepted the alarm | To install your alarms on your phone and show whether they're ready | You and your friends (suggestions you accept) |
| Messages and reactions | To deliver them to the friend you're talking to | You |
| Challenges you make (quizzes, puzzles) and, if you use AI drafting, the text you typed for it | To run challenges; to draft a challenge with an AI model | You |
| Custom sounds you upload | To play them when you allowed a friend to use them | You |
| Location during a share you start: coordinates, accuracy, time captured | To show your chosen friends where you are until the share ends | Your phone, only while sharing |
| Device information: platform, OS version, app version, a device key, push token, which permissions your phone granted (alarms, notifications) | To deliver notifications to the right phone, verify that requests come from your phone, and tell you if alarms can't ring | Your phone |
| Subscription status | To provide Plus features | Apple or Google, via our billing provider |
| Reports you file and moderation decisions | To keep people safe | You and our moderators |
| Security and audit records (sign-ins to admin tools, permission changes, deletions) | Security, abuse investigation, legal obligations | Our systems |

We don't collect your contacts, phone number, advertising identifier or browsing history, and
we don't use your microphone. Alarm challenges are checked on your phone; we don't receive
camera images or sensor recordings.

## 4. Who we share it with

- **Your friends**: only what the app shows them (your name; what you allow them to do; the
  status of their requests; messages you send them; location during a share you started).
- **Service providers** that process data for us under contract: [Supabase] (sign-in and
  database hosting), [Apple Push Notification service, Firebase Cloud Messaging]
  (notifications; content is minimal and never message text), [Cloudflare R2 / storage
  provider] (custom sounds), [Anthropic] (only the text you type when you ask AI to draft a
  challenge; no other account data), [RevenueCat] (subscription status), Apple and Google
  (purchases).
- **Legal reasons**: when required by law or to protect someone's safety.

We don't sell or rent personal data and don't share it for advertising.

## 5. Your controls

Pause all requests, quiet hours, per-friend limits, pausing or removing a friend's
permissions, blocking (which removes permissions both ways at once), reporting, ending a
location share, signing out a lost phone, exporting your data (You → Export my data) and
deleting your account (You → Delete account).

Deleting your account deactivates it immediately (every permission revoked, requests
cancelled, devices signed out) and permanently deletes it after 7 days, unless you sign in
and keep it. Messages you sent are erased; friends see that a message was deleted.

## 6. How long we keep data (proposals pending legal review)

| Data | Kept |
|---|---|
| Location coordinates | Only the latest fix, only until the share ends (at most 8 hours) |
| Request records and location access records | 30 days |
| Text typed to draft an AI challenge | 30 days (the cost record is kept for billing) |
| Sync records used to update your phones | 45 days |
| Security and audit records | 365 days |
| Your account and content | Until you delete them or your account (then 7-day grace) |

Backups follow the same periods within [the backup window].

## 7. Security

Connections use TLS. Location coordinates are additionally encrypted in our database with a
key held separately. Requests that affect a phone are signed by a key that stays on that
phone (Secure Enclave on iPhone, Android Keystore). Admin tools require multi-factor sign-in
and are audited. No system is perfectly secure; tell us at [security@…] if you find a problem.

## 8. Your rights

Depending on where you live, you may have the right to access, correct, export or delete
your data, object to or restrict processing, and complain to a supervisory authority.
[Region-specific sections: GDPR/UK GDPR legal bases; US state rights; India DPDP.]

Legal bases (draft, if GDPR applies): contract (running the service you asked for),
consent (location sharing, notifications, AI drafting), legitimate interests (security,
abuse prevention), legal obligation.

## 9. Children

Wake Me is not for anyone under 18. We ask you to confirm your age when you sign up and
delete accounts we learn belong to children.

## 10. Changes

We'll tell you in the app before material changes take effect.
