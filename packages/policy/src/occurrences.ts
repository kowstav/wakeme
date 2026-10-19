import {
  addDays,
  formatLocalDate,
  isoWeekday,
  localParts,
  parseHhMm,
  parseLocalDate,
  zonedToInstant,
  type LocalDate,
} from './time.ts';

export type Recurrence = { kind: 'weekly'; days: number[] } | { kind: 'once'; date: string };

export interface OccurrenceRule {
  /** Wall-clock time, HH:MM */
  localTime: string;
  /** IANA zone used for the calculation (plan zone, or the device zone for device_local plans) */
  timeZone: string;
  recurrence: Recurrence;
}

export interface Occurrence {
  /** Local calendar date of the occurrence in the rule's zone; also the occurrence key. */
  localDate: string;
  instant: Date;
}

/**
 * Next `count` occurrences strictly after `after`.
 * DST gaps shift later by the gap length; folds use the earlier instant.
 */
export function nextOccurrences(rule: OccurrenceRule, after: Date, count: number): Occurrence[] {
  if (!Number.isInteger(count) || count < 1 || count > 64) throw new Error('count must be 1-64');
  const { hour, minute } = parseHhMm(rule.localTime);
  const result: Occurrence[] = [];

  if (rule.recurrence.kind === 'once') {
    const date = parseLocalDate(rule.recurrence.date);
    const instant = zonedToInstant(date, hour, minute, rule.timeZone);
    if (instant.getTime() > after.getTime()) result.push({ localDate: formatLocalDate(date), instant });
    return result;
  }

  const days = rule.recurrence.days;
  if (days.length === 0) return result;
  const start = localParts(after, rule.timeZone);
  // Start one day early so an occurrence just after `after` on the previous
  // local date (only possible across odd offsets) is never skipped.
  let date: LocalDate = addDays({ year: start.year, month: start.month, day: start.day }, -1);
  const maxDays = 8 * count + 2;
  for (let i = 0; i < maxDays && result.length < count; i++, date = addDays(date, 1)) {
    if (!days.includes(isoWeekday(date))) continue;
    const instant = zonedToInstant(date, hour, minute, rule.timeZone);
    if (instant.getTime() > after.getTime()) result.push({ localDate: formatLocalDate(date), instant });
  }
  return result;
}

export function nextOccurrence(rule: OccurrenceRule, after: Date): Occurrence | undefined {
  return nextOccurrences(rule, after, 1)[0];
}
