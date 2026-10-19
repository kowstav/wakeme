import type { PushAlert } from '../push/gateway.ts';
import { soundFileName } from '../push/gateway.ts';

// Literal, minimal notification copy. Keys are localized by the apps; the
// English strings are the fallback APNs shows if a key is missing. No message
// bodies, quiz content, goals or coordinates ever appear in a notification.

export type Template =
  | 'nudge'
  | 'wake'
  | 'focus'
  | 'checkin_request'
  | 'location_request'
  | 'outcome_acknowledged'
  | 'outcome_challenge_completed'
  | 'outcome_bypassed'
  | 'plan_proposed'
  | 'plan_accepted'
  | 'plan_challenge_completed'
  | 'plan_bypassed'
  | 'message_direct'
  | 'message_circle'
  | 'sound_offered'
  | 'sos'
  | 'checkin_started'
  | 'share_started'
  | 'checked_in'
  | 'checkin_missed'
  | 'safety_acknowledged';

interface Copy {
  title: string;
  body: string;
  category?: string;
  interruption: PushAlert['interruption'];
}

function copy(t: Template, name: string, extra: { minutes?: number; circle?: string | null }): Copy {
  switch (t) {
    case 'nudge':
      return { title: name, body: 'Quack! 🦆', category: 'WM_NUDGE', interruption: 'time-sensitive' };
    case 'wake':
      return { title: `${name} is waking you up`, body: 'Open Wake Me to start your wake-up.', category: 'WM_WAKE', interruption: 'time-sensitive' };
    case 'focus':
      return { title: `${name} asks you to focus`, body: `A ${extra.minutes ?? 25}-minute focus session. You choose whether to start it.`, category: 'WM_FOCUS', interruption: 'active' };
    case 'checkin_request':
      return { title: `${name} is checking on you`, body: "Let them know you're OK.", category: 'WM_CHECKIN', interruption: 'active' };
    case 'location_request':
      return { title: `${name} asked where you are`, body: 'Nothing is shared unless you choose to share.', category: 'WM_LOCATION_REQUEST', interruption: 'active' };
    case 'outcome_acknowledged':
      return { title: name, body: 'Saw your request 👍', interruption: 'passive' };
    case 'outcome_challenge_completed':
      return { title: name, body: 'Finished the wake-up challenge ☀️', interruption: 'active' };
    case 'outcome_bypassed':
      return { title: name, body: 'Ended the wake-up without the challenge.', interruption: 'passive' };
    case 'plan_proposed':
      return { title: `${name} suggested a wake-up routine`, body: 'Review it. Nothing changes until you accept.', category: 'WM_PLAN', interruption: 'active' };
    case 'plan_accepted':
      return { title: name, body: 'Accepted your wake-up routine.', interruption: 'passive' };
    case 'plan_challenge_completed':
      return { title: name, body: 'Finished their wake-up challenge ☀️', interruption: 'passive' };
    case 'plan_bypassed':
      return { title: name, body: 'Ended their wake-up without the challenge.', interruption: 'passive' };
    case 'message_direct':
      return { title: name, body: 'New message', category: 'WM_MESSAGE', interruption: 'active' };
    case 'message_circle':
      return { title: extra.circle ?? 'Circle', body: `New message from ${name}`, category: 'WM_MESSAGE', interruption: 'active' };
    case 'sound_offered':
      return { title: name, body: 'Shared a sound with you. Approve it to let it play.', interruption: 'passive' };
    case 'sos':
      return { title: `SOS from ${name}`, body: 'Open Wake Me to see what they shared. Call emergency services if needed.', category: 'WM_SOS', interruption: 'time-sensitive' };
    case 'checkin_started':
      return { title: name, body: 'Started a check-in with you.', category: 'WM_SAFETY', interruption: 'active' };
    case 'share_started':
      return { title: name, body: 'Is sharing their location with you for a limited time.', category: 'WM_SAFETY', interruption: 'active' };
    case 'checked_in':
      return { title: name, body: "Checked in: they're OK.", interruption: 'active' };
    case 'checkin_missed':
      return { title: name, body: "Hasn't checked in yet. Try reaching them.", category: 'WM_SAFETY', interruption: 'time-sensitive' };
    case 'safety_acknowledged':
      return { title: name, body: 'Saw your alert.', interruption: 'active' };
  }
}

export function renderAlert(t: Template, actorName: string, opts: { soundId?: string | null; silent?: boolean; minutes?: number; circle?: string | null; threadId?: string } = {}): PushAlert {
  const c = copy(t, actorName, opts);
  const silent = opts.silent === true;
  const alert: PushAlert = {
    title: c.title,
    body: c.body,
    titleLocKey: `push.${t}.title`,
    locKey: `push.${t}.body`,
    locArgs: [actorName, String(opts.minutes ?? ''), opts.circle ?? ''],
    sound: silent ? null : opts.soundId ? soundFileName(opts.soundId) : 'default',
    interruption: silent ? 'passive' : c.interruption,
  };
  if (c.category) alert.category = c.category;
  if (opts.threadId) alert.threadId = opts.threadId;
  return alert;
}
