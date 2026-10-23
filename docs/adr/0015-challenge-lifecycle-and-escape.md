# ADR 0015 — Challenge state and escape during window changes

Date: 2026-10-20. Status: implemented on Android; validation recorded in PROGRESS.md.

Docs 02 §9, 03 §8 and 06 require offline challenges, an accessible escape, finite duration,
and usable alarms/quiz keyboards/chess boards in resizable windows.

The Android runner now has a scrolling task area and a separate control area. The control area
holds the simple check-in and escape, plus Stop sound while an alarm is playing or End focus
while earning a focus break. It consumes safe-drawing insets, including the keyboard. The alarm
and focus activities explicitly use `adjustResize`. Wide windows keep the task in a readable
640 dp column; long content scrolls instead of displacing escape controls.

Small UI values (step number, typed answer, selections, timer start) use saved instance state.
The alarm occurrence and accepted version identify a run; a follow-up for the same occurrence
keeps progress and a different occurrence starts again. A focus break saves its small generated
definition so recreation cannot change a sum while it is being answered. This is activity/saved
instance restoration, not a claim that progress survives force-stop, reboot or deletion of app
data. Alarm schedules continue to use their existing durable local store.

The run's accepted maximum duration is measured against `elapsedRealtime`, so changing wall
time does not skip or extend the attempt. Expiry switches to the low-demand check-in and keeps
escape available. It does not report a solved challenge. Timer and scanner callbacks from an
earlier step are ignored; completion is emitted once per run. These are usability and lifecycle
controls, not tamper-proof evidence of wakefulness.

Tests cover saved-state restoration, run identity, large text/short windows, expiry without
completion, and the existing creator journey. Previews cover alarm, quiz and chess on phone,
foldable, tablet and desktop, including large text and landscape. Emulator/layoutlib evidence
still does not replace the Stage 0 physical-device matrix.
