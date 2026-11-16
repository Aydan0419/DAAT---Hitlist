import { DEFAULT_SETTINGS, GroupSettings, SafeZone } from "../types";
import { isValidTimezone } from "./time";

export class ValidationError extends Error {}

const fail = (msg: string): never => {
  throw new ValidationError(msg);
};

export function str(v: unknown, name: string, min = 1, max = 200): string {
  if (typeof v !== "string") return fail(`${name} must be text`);
  const t = v.trim();
  if (t.length < min || t.length > max) return fail(`${name} must be ${min}-${max} characters`);
  return t;
}

export function num(v: unknown, name: string, min: number, max: number): number {
  if (typeof v !== "number" || !Number.isFinite(v) || v < min || v > max) {
    return fail(`${name} must be a number between ${min} and ${max}`);
  }
  return v;
}

export function int(v: unknown, name: string, min: number, max: number): number {
  const n = num(v, name, min, max);
  return Number.isInteger(n) ? n : fail(`${name} must be a whole number`);
}

export function optNum(v: unknown, name: string, min: number, max: number): number | null {
  return v === null || v === undefined ? null : num(v, name, min, max);
}

/** Firestore auto-IDs and Firebase uids: safe to use in document paths. */
export function id(v: unknown, name: string): string {
  if (typeof v !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(v)) return fail(`${name} is invalid`);
  return v;
}

export const USERNAME_RE = /^[a-z0-9_]{3,20}$/;

export function username(v: unknown): string {
  const u = typeof v === "string" ? v.trim().replace(/^@/, "").toLowerCase() : "";
  return USERNAME_RE.test(u)
    ? u
    : fail("Username must be 3-20 characters: letters, numbers or underscores");
}

function safeZone(v: unknown, i: number): SafeZone {
  if (typeof v !== "object" || v === null) return fail(`Safe zone ${i + 1} is invalid`);
  const z = v as Record<string, unknown>;
  const start = optNum(z.startMinute, "Safe zone start", 0, 1439);
  const end = optNum(z.endMinute, "Safe zone end", 0, 1439);
  if ((start === null) !== (end === null)) fail("Safe zone needs both a start and an end time");
  return {
    id: id(z.id, "Safe zone id"),
    name: str(z.name, "Safe zone name", 1, 40),
    lat: num(z.lat, "Safe zone latitude", -90, 90),
    lon: num(z.lon, "Safe zone longitude", -180, 180),
    radiusM: num(z.radiusM, "Safe zone radius", 10, 5000),
    startMinute: start,
    endMinute: end,
  };
}

/** Merges a partial settings object from a client over the current settings. */
export function mergeSettings(current: GroupSettings, patch: unknown): GroupSettings {
  if (typeof patch !== "object" || patch === null) return fail("Settings are invalid");
  const p = patch as Record<string, unknown>;
  const next: GroupSettings = { ...DEFAULT_SETTINGS, ...current };
  if (p.reshuffleHour !== undefined) next.reshuffleHour = int(p.reshuffleHour, "Reshuffle hour", 0, 23);
  if (p.timezone !== undefined) {
    next.timezone = isValidTimezone(p.timezone) ? p.timezone : fail("Unknown timezone");
  }
  if (p.maxSnipesPerDay !== undefined) next.maxSnipesPerDay = int(p.maxSnipesPerDay, "Max snipes per day", 1, 20);
  if (p.immunityMinutes !== undefined) next.immunityMinutes = int(p.immunityMinutes, "Immunity", 0, 1440);
  if (p.snipedPenalty !== undefined) next.snipedPenalty = int(p.snipedPenalty, "Penalty", 0, 500);
  if (p.hintEnabled !== undefined) {
    next.hintEnabled = typeof p.hintEnabled === "boolean" ? p.hintEnabled : fail("Hint setting is invalid");
  }
  if (p.maxDistanceM !== undefined) next.maxDistanceM = num(p.maxDistanceM, "Max distance", 10, 100);
  if (p.safeZones !== undefined) {
    if (!Array.isArray(p.safeZones) || p.safeZones.length > 20) fail("Up to 20 safe zones are allowed");
    next.safeZones = (p.safeZones as unknown[]).map(safeZone);
  }
  if (p.quietHours !== undefined) {
    if (p.quietHours === null) next.quietHours = null;
    else {
      const q = p.quietHours as Record<string, unknown>;
      next.quietHours = {
        startMinute: int(q?.startMinute, "Quiet hours start", 0, 1439),
        endMinute: int(q?.endMinute, "Quiet hours end", 0, 1439),
      };
    }
  }
  return next;
}
