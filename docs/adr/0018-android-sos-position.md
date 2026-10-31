# ADR 0018: one position for Android SOS

Accepted 2026-10-20. Doc 06 distinguishes “share where I am now” from persistent safety sharing.
The earlier Android SOS implementation used the location button, then started the continuous
location foreground service. The button also lacked the stable view ID needed to deliver its
fallback permission result on older Android versions.

Android SOS now sends the alert first, then optionally requests **one** current position, bounded
to ten seconds. Only a fix with finite accuracy and a capture time within the past two minutes is
sent. An absent/revoked permission, unavailable fix or failed sample upload leaves the already-sent
SOS available with “No location available”; it never silently starts ongoing tracking. Ending the
session cancels a pending capture. This uses the existing signed sample API and does not change
the server's authorization or retention model. Ordinary timed shares keep the visible, stoppable
location foreground service.

The client checks permission at use time; the UI callback is not used as cached authorization.
The system button has a stable resource ID and a fresh callback. Recipient selection and the
share form survive activity recreation, without starting any session automatically. A rationale
and settings recovery are available for ordinary timed-share permission requests.

Primary references:

- [Location button and session use](https://developer.android.com/guide/topics/permissions/private-alternatives/location-button)
- [LocationButton view: fallback result delivery and view ID](https://developer.android.com/reference/androidx/core/locationbutton/LocationButton)

Verification: guarded snapshot unit tests and a native system-button tap/permission callback test
on the CI emulators. Results must be recorded in PROGRESS.md before being called verified.
Real location accuracy, permission lifetime and background behavior remain physical-device gates.
iOS currently retains its existing SOS location behavior and needs a separate parity review.
