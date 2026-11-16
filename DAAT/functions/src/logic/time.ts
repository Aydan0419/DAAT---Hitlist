/** Timezone helpers built on Intl, so no date library is needed. */

export interface LocalParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  /** 0 = Sunday … 6 = Saturday */
  weekday: number;
}

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(tz: string): Intl.DateTimeFormat {
  let f = formatters.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      weekday: "short",
    });
    formatters.set(tz, f);
  }
  return f;
}

export function isValidTimezone(tz: unknown): tz is string {
  if (typeof tz !== "string" || tz.length === 0 || tz.length > 64) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

export function localParts(tz: string, at: number): LocalParts {
  const out: Record<string, string> = {};
  for (const p of formatter(tz).formatToParts(new Date(at))) out[p.type] = p.value;
  return {
    year: Number(out.year),
    month: Number(out.month),
    day: Number(out.day),
    hour: Number(out.hour) % 24,
    minute: Number(out.minute),
    weekday: WEEKDAYS.indexOf(out.weekday),
  };
}

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`;

/** Local calendar date, YYYY-MM-DD. */
export function dayId(tz: string, at: number): string {
  const p = localParts(tz, at);
  return ymd(p.year, p.month, p.day);
}

/** Local date of the Monday that starts the week containing `at`. */
export function weekId(tz: string, at: number): string {
  const p = localParts(tz, at);
  const back = (p.weekday + 6) % 7;
  const monday = new Date(Date.UTC(p.year, p.month - 1, p.day - back));
  return ymd(monday.getUTCFullYear(), monday.getUTCMonth() + 1, monday.getUTCDate());
}

export function localMinuteOfDay(tz: string, at: number): number {
  const p = localParts(tz, at);
  return p.hour * 60 + p.minute;
}

const QUARTER_HOUR = 15 * 60 * 1000;

/**
 * The first instant strictly after `after` at which the local clock in `tz`
 * reads `hour`:00. Walks in 15-minute steps so half- and quarter-hour offsets
 * and daylight-saving changes are handled. If DST skips that hour on a given
 * day, the first quarter-hour at or past it that day is used instead.
 */
export function nextLocalHour(tz: string, hour: number, after: number): number {
  let prev = localParts(tz, after);
  let t = Math.floor(after / QUARTER_HOUR) * QUARTER_HOUR + QUARTER_HOUR;
  for (let i = 0; i < 4 * 50; i++, t += QUARTER_HOUR) {
    const p = localParts(tz, t);
    if (p.hour === hour && p.minute === 0) return t;
    const sameDay = p.day === prev.day && p.month === prev.month;
    if (sameDay && prev.hour < hour && p.hour > hour) return t;
    prev = p;
  }
  return after + 24 * 60 * 60 * 1000;
}
