// Time-zone arithmetic built only on Intl so the server needs no tz library.
// The resolution rules match java.time and Foundation's Calendar so that the
// Android and iOS apps compute identical instants (see policy-fixtures):
//   * gap  (wall time skipped by a DST jump): shift later by the gap length
//   * fold (wall time repeated): use the earlier instant

export interface LocalDate {
  year: number;
  month: number; // 1-12
  day: number; // 1-31
}

export interface LocalParts extends LocalDate {
  hour: number;
  minute: number;
  second: number;
  /** ISO weekday: Monday = 1 ... Sunday = 7 */
  weekday: number;
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = formatterCache.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
    });
    formatterCache.set(timeZone, f);
  }
  return f;
}

const WEEKDAYS: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

export function isValidTimeZone(timeZone: string): boolean {
  if (typeof timeZone !== 'string' || timeZone.length === 0 || timeZone.length > 64) return false;
  try {
    formatter(timeZone);
    return true;
  } catch {
    return false;
  }
}

export function localParts(instant: Date, timeZone: string): LocalParts {
  const parts = formatter(timeZone).formatToParts(instant);
  const get = (type: Intl.DateTimeFormatPartTypes): string => {
    const p = parts.find((x) => x.type === type);
    if (!p) throw new Error(`missing ${type} for ${timeZone}`);
    return p.value;
  };
  const weekday = WEEKDAYS[get('weekday')];
  if (!weekday) throw new Error(`unexpected weekday for ${timeZone}`);
  return {
    year: Number(get('year')),
    month: Number(get('month')),
    day: Number(get('day')),
    hour: Number(get('hour')),
    minute: Number(get('minute')),
    second: Number(get('second')),
    weekday,
  };
}

/** Offset of the zone from UTC at the given instant, in milliseconds. */
export function offsetMs(instantMs: number, timeZone: string): number {
  const p = localParts(new Date(instantMs), timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  const truncated = instantMs - (((instantMs % 1000) + 1000) % 1000);
  return asUtc - truncated;
}

/** Converts a wall-clock time in a zone to an instant using the documented gap/fold rules. */
export function zonedToInstant(date: LocalDate, hour: number, minute: number, timeZone: string): Date {
  const wallMs = Date.UTC(date.year, date.month - 1, date.day, hour, minute, 0);
  const day = 86_400_000;
  const before = offsetMs(wallMs - day, timeZone);
  const after = offsetMs(wallMs + day, timeZone);
  const candidates = new Set<number>([before, after, offsetMs(wallMs, timeZone)]);
  const valid: number[] = [];
  for (const offset of candidates) {
    const instant = wallMs - offset;
    if (offsetMs(instant, timeZone) === offset) valid.push(instant);
  }
  if (valid.length > 0) {
    return new Date(Math.min(...valid));
  }
  // Gap: interpret with the pre-transition offset, which lands after the jump
  // at wall time + gap length (02:30 -> 03:30 for a one-hour spring-forward).
  return new Date(wallMs - before);
}

export function parseHhMm(value: string): { hour: number; minute: number } {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(value);
  if (!m) throw new Error(`invalid HH:MM value: ${value}`);
  return { hour: Number(m[1]), minute: Number(m[2]) };
}

export function isHhMm(value: unknown): value is string {
  return typeof value === 'string' && /^([01]\d|2[0-3]):([0-5]\d)$/.test(value);
}

export function minuteOfDay(value: string): number {
  const { hour, minute } = parseHhMm(value);
  return hour * 60 + minute;
}

export function addDays(date: LocalDate, days: number): LocalDate {
  const ms = Date.UTC(date.year, date.month - 1, date.day) + days * 86_400_000;
  const d = new Date(ms);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

export function isoWeekday(date: LocalDate): number {
  const js = new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay(); // 0 = Sunday
  return js === 0 ? 7 : js;
}

export function formatLocalDate(date: LocalDate): string {
  const mm = String(date.month).padStart(2, '0');
  const dd = String(date.day).padStart(2, '0');
  return `${String(date.year).padStart(4, '0')}-${mm}-${dd}`;
}

export function parseLocalDate(value: string): LocalDate {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) throw new Error(`invalid local date: ${value}`);
  const date = { year: Number(m[1]), month: Number(m[2]), day: Number(m[3]) };
  const check = new Date(Date.UTC(date.year, date.month - 1, date.day));
  if (check.getUTCMonth() + 1 !== date.month || check.getUTCDate() !== date.day) {
    throw new Error(`invalid local date: ${value}`);
  }
  return date;
}

/** ISO-8601 UTC instant with second precision, e.g. 2026-10-19T14:00:00Z (same as Swift/Kotlin defaults). */
export function toIsoSeconds(instant: Date): string {
  return new Date(Math.floor(instant.getTime() / 1000) * 1000).toISOString().replace('.000Z', 'Z');
}
