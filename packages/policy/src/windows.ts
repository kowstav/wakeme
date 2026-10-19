import { isHhMm, localParts, minuteOfDay } from './time.ts';

/**
 * A recurring window in the recipient's local time.
 * days: ISO weekdays (Mon=1..Sun=7) on which the window STARTS.
 * When end <= start the window is overnight and ends on the following day,
 * e.g. {days:[5], start:"22:00", end:"02:00"} covers Fri 22:00 - Sat 02:00.
 * start === end is rejected by validation (ambiguous between empty and 24h).
 */
export interface LocalWindow {
  days: number[];
  start: string;
  end: string;
}

export interface WindowIssue {
  path: string;
  message: string;
}

export function validateWindow(w: unknown, path = 'window'): WindowIssue[] {
  const issues: WindowIssue[] = [];
  if (typeof w !== 'object' || w === null) return [{ path, message: 'must be an object' }];
  const win = w as Partial<LocalWindow>;
  if (!Array.isArray(win.days) || win.days.length === 0 || win.days.length > 7) {
    issues.push({ path: `${path}.days`, message: 'must list 1-7 ISO weekdays' });
  } else {
    const seen = new Set<number>();
    for (const d of win.days) {
      if (!Number.isInteger(d) || d < 1 || d > 7) issues.push({ path: `${path}.days`, message: 'weekday must be 1-7' });
      if (seen.has(d)) issues.push({ path: `${path}.days`, message: 'weekdays must be unique' });
      seen.add(d);
    }
  }
  if (!isHhMm(win.start)) issues.push({ path: `${path}.start`, message: 'must be HH:MM' });
  if (!isHhMm(win.end)) issues.push({ path: `${path}.end`, message: 'must be HH:MM' });
  if (isHhMm(win.start) && isHhMm(win.end) && win.start === win.end) {
    issues.push({ path, message: 'start and end must differ' });
  }
  return issues;
}

export function isWithinWindow(instant: Date, timeZone: string, window: LocalWindow): boolean {
  const p = localParts(instant, timeZone);
  const now = p.hour * 60 + p.minute;
  const start = minuteOfDay(window.start);
  const end = minuteOfDay(window.end);
  if (start < end) {
    return window.days.includes(p.weekday) && now >= start && now < end;
  }
  // Overnight window: the part after midnight belongs to the previous day's window.
  const previousDay = p.weekday === 1 ? 7 : p.weekday - 1;
  return (window.days.includes(p.weekday) && now >= start) || (window.days.includes(previousDay) && now < end);
}

export function isWithinAnyWindow(instant: Date, timeZone: string, windows: readonly LocalWindow[]): boolean {
  return windows.some((w) => isWithinWindow(instant, timeZone, w));
}

export interface QuietHours {
  enabled: boolean;
  start: string;
  end: string;
}

export function isQuietTime(instant: Date, timeZone: string, quiet: QuietHours): boolean {
  if (!quiet.enabled) return false;
  return isWithinWindow(instant, timeZone, { days: [1, 2, 3, 4, 5, 6, 7], start: quiet.start, end: quiet.end });
}
