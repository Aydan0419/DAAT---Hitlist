import { SafeZone, QuietHours } from "../types";

const EARTH_RADIUS_M = 6_371_000;
const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;

/** Haversine distance in metres. */
export function distanceM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** Bearing from point 1 to point 2: degrees, 0 = north, clockwise. */
export function bearingDeg(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLon = rad(lon2 - lon1);
  const y = Math.sin(dLon) * Math.cos(rad(lat2));
  const x =
    Math.cos(rad(lat1)) * Math.sin(rad(lat2)) -
    Math.sin(rad(lat1)) * Math.cos(rad(lat2)) * Math.cos(dLon);
  return (deg(Math.atan2(y, x)) + 360) % 360;
}

/** Smallest angle between two headings, 0-180. */
export function angleDiffDeg(a: number, b: number): number {
  return Math.abs(((((a - b + 540) % 360) + 360) % 360) - 180);
}

export const BASE_AIM_TOLERANCE_DEG = 20;
export const DEFAULT_ACCURACY_M = 20;
export const MAX_ACCURACY_M = 100;
/** However bad the GPS, a snipe from further than this never counts. */
export const HARD_MAX_DISTANCE_M = 150;
/** Extra uncertainty for how far the target may have walked since their last fix. */
export const DRIFT_M_PER_SEC = 0.5;
export const MAX_DRIFT_M = 30;

export function clampAccuracy(a: number | null | undefined): number {
  if (a === null || a === undefined || !Number.isFinite(a) || a <= 0) return DEFAULT_ACCURACY_M;
  return Math.min(a, MAX_ACCURACY_M);
}

export interface GeometryResult {
  ok: boolean;
  reason: "OK" | "TOO_FAR" | "WRONG_ORIENTATION";
  distanceM: number;
  bearingDeg: number;
  aimErrorDeg: number;
  allowedDistanceM: number;
  allowedAimErrorDeg: number;
}

/**
 * Distance + aim check with tolerances scaled to real GPS accuracy.
 *
 * Phone GPS is routinely 5-20 m off. At close range that makes the true
 * bearing to the target unknowable, so the allowed aim error widens by the
 * angle the combined position uncertainty subtends at the measured distance,
 * and the aim check is skipped entirely once the two fixes overlap.
 */
export function verifyGeometry(args: {
  hunterLat: number;
  hunterLon: number;
  hunterAccuracyM: number | null;
  headingDeg: number;
  targetLat: number;
  targetLon: number;
  targetAccuracyM: number | null;
  targetFixAgeSec: number;
  maxDistanceM: number;
}): GeometryResult {
  const drift = Math.min(Math.max(args.targetFixAgeSec, 0) * DRIFT_M_PER_SEC, MAX_DRIFT_M);
  const uncertainty =
    clampAccuracy(args.hunterAccuracyM) + clampAccuracy(args.targetAccuracyM) + drift;
  const dist = distanceM(args.hunterLat, args.hunterLon, args.targetLat, args.targetLon);
  const bearing = bearingDeg(args.hunterLat, args.hunterLon, args.targetLat, args.targetLon);
  const aimError = angleDiffDeg(args.headingDeg, bearing);

  const allowedDistance = Math.min(args.maxDistanceM + uncertainty, HARD_MAX_DISTANCE_M);
  const allowedAim =
    dist <= uncertainty
      ? 180
      : Math.min(180, BASE_AIM_TOLERANCE_DEG + deg(Math.atan2(uncertainty, dist)));

  const base = {
    distanceM: dist,
    bearingDeg: bearing,
    aimErrorDeg: aimError,
    allowedDistanceM: allowedDistance,
    allowedAimErrorDeg: allowedAim,
  };
  if (dist > allowedDistance) return { ok: false, reason: "TOO_FAR", ...base };
  if (aimError > allowedAim) return { ok: false, reason: "WRONG_ORIENTATION", ...base };
  return { ok: true, reason: "OK", ...base };
}

// ── Proximity hint ──────────────────────────────────────────────

export type ProximityBand = "UNKNOWN" | "FAR" | "NEAR" | "CLOSE" | "VERY_CLOSE";
export type Compass8 = "N" | "NE" | "E" | "SE" | "S" | "SW" | "W" | "NW";
const COMPASS: Compass8[] = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];

export function compass8(bearing: number): Compass8 {
  return COMPASS[Math.round((((bearing % 360) + 360) % 360) / 45) % 8];
}

/**
 * A deliberately coarse hint: a distance band, plus a direction rounded to
 * 8 points only once the hunter is within 1 km. Never exposes coordinates.
 */
export function proximityHint(
  hunterLat: number,
  hunterLon: number,
  targetLat: number,
  targetLon: number,
): { band: ProximityBand; direction: Compass8 | null } {
  const d = distanceM(hunterLat, hunterLon, targetLat, targetLon);
  const band: ProximityBand = d < 30 ? "VERY_CLOSE" : d < 100 ? "CLOSE" : d < 1000 ? "NEAR" : "FAR";
  const direction =
    band === "FAR" ? null : compass8(bearingDeg(hunterLat, hunterLon, targetLat, targetLon));
  return { band, direction };
}

// ── Safe zones ──────────────────────────────────────────────────

/** True when `minute` falls in [start, end), handling windows that wrap past midnight. */
export function inMinuteWindow(minute: number, start: number, end: number): boolean {
  if (start === end) return true;
  return start < end ? minute >= start && minute < end : minute >= start || minute < end;
}

export function inQuietHours(quiet: QuietHours | null, localMinute: number): boolean {
  return quiet !== null && inMinuteWindow(localMinute, quiet.startMinute, quiet.endMinute);
}

/** Returns the first active safe zone containing the point, if any. */
export function findSafeZone(
  zones: SafeZone[],
  lat: number,
  lon: number,
  localMinute: number,
): SafeZone | null {
  for (const z of zones) {
    const timed = z.startMinute != null && z.endMinute != null;
    if (timed && !inMinuteWindow(localMinute, z.startMinute!, z.endMinute!)) continue;
    if (distanceM(lat, lon, z.lat, z.lon) <= z.radiusM) return z;
  }
  return null;
}
