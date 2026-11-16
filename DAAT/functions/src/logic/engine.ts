/**
 * The game rules as pure functions. Handlers read documents, call these,
 * and write the results, so every rule here can be tested without Firebase.
 */
import {
  AssignmentDoc,
  GroupDoc,
  LocationFix,
  MemberDoc,
  PointsBreakdown,
  SnipeEvidence,
} from "../types";
import { calculatePoints } from "./scoring";
import { findSafeZone, inQuietHours, verifyGeometry } from "./geo";
import { pickNextTarget, Rng } from "./cycle";
import { localMinuteOfDay } from "./time";

export const MAX_PHOTO_AGE_MS = 5 * 60 * 1000;
export const MAX_CLOCK_SKEW_MS = 60 * 1000;
/** A target fix older than this can't be trusted for a distance check. */
export const FRESH_FIX_MS = 2 * 60 * 1000;

export type RejectCode =
  | "NOT_A_MEMBER"
  | "GROUP_TOO_SMALL"
  | "NO_TARGET"
  | "NOT_YOUR_TARGET"
  | "SNIPE_PENDING"
  | "DAILY_LIMIT"
  | "TARGET_IMMUNE"
  | "PHOTO_TOO_OLD"
  | "QUIET_HOURS"
  | "SAFE_ZONE"
  | "TOO_FAR"
  | "WRONG_ORIENTATION";

export interface SnipeAttempt {
  hunterUid: string;
  targetUid: string;
  lat: number;
  lon: number;
  accuracyM: number | null;
  headingDeg: number;
  capturedAt: number;
}

/** How many successful snipes the member has made in the group's current day. */
export function snipesToday(member: MemberDoc, group: GroupDoc): number {
  return member.snipeDayId === group.dayId ? member.snipesToday : 0;
}

/**
 * Checks that don't depend on where the target is. Returns a reject code, or
 * null when the attempt may go on to the location check.
 */
export function checkEligibility(args: {
  group: GroupDoc;
  hunter: MemberDoc | null;
  target: MemberDoc | null;
  assignment: AssignmentDoc | null;
  attempt: SnipeAttempt;
  snipeId: string;
  now: number;
}): RejectCode | null {
  const { group, hunter, target, assignment, attempt, now } = args;
  if (!hunter || !group.memberIds.includes(attempt.hunterUid)) return "NOT_A_MEMBER";
  if (group.memberIds.length < 3) return "GROUP_TOO_SMALL";
  if (!assignment) return snipesToday(hunter, group) >= group.settings.maxSnipesPerDay ? "DAILY_LIMIT" : "NO_TARGET";
  if (assignment.targetUid !== attempt.targetUid || !target) return "NOT_YOUR_TARGET";
  if (assignment.pendingSnipeId && assignment.pendingSnipeId !== args.snipeId) return "SNIPE_PENDING";
  if (snipesToday(hunter, group) >= group.settings.maxSnipesPerDay) return "DAILY_LIMIT";
  if (target.immuneUntil > now) return "TARGET_IMMUNE";
  if (attempt.capturedAt > now + MAX_CLOCK_SKEW_MS || now - attempt.capturedAt > MAX_PHOTO_AGE_MS) {
    return "PHOTO_TOO_OLD";
  }
  const minute = localMinuteOfDay(group.settings.timezone, now);
  if (inQuietHours(group.settings.quietHours, minute)) return "QUIET_HOURS";
  if (findSafeZone(group.settings.safeZones, attempt.lat, attempt.lon, minute)) return "SAFE_ZONE";
  return null;
}

export type LocationVerdict =
  | { outcome: "VERIFIED"; evidence: SnipeEvidence }
  | { outcome: "PENDING"; evidence: SnipeEvidence }
  | { outcome: "REJECT"; code: RejectCode; evidence: SnipeEvidence };

/**
 * The location check. With a fresh target fix the server decides outright;
 * without one the snipe is left PENDING for the target to confirm or dispute.
 */
export function judgeLocation(args: {
  group: GroupDoc;
  attempt: SnipeAttempt;
  targetFix: LocationFix | null;
  now: number;
}): LocationVerdict {
  const { group, attempt, targetFix, now } = args;
  const base: SnipeEvidence = {
    hunterLat: attempt.lat,
    hunterLon: attempt.lon,
    hunterAccuracyM: attempt.accuracyM ?? -1,
    headingDeg: attempt.headingDeg,
    distanceM: null,
    bearingDeg: null,
    aimErrorDeg: null,
    targetAccuracyM: targetFix?.accuracyM ?? null,
    targetFixAgeSec: targetFix ? Math.round((now - targetFix.updatedAt) / 1000) : null,
  };
  if (!targetFix || now - targetFix.updatedAt > FRESH_FIX_MS) {
    return { outcome: "PENDING", evidence: base };
  }
  const ageSec = Math.max(0, (now - targetFix.updatedAt) / 1000);
  const geo = verifyGeometry({
    hunterLat: attempt.lat,
    hunterLon: attempt.lon,
    hunterAccuracyM: attempt.accuracyM,
    headingDeg: attempt.headingDeg,
    targetLat: targetFix.lat,
    targetLon: targetFix.lon,
    targetAccuracyM: targetFix.accuracyM,
    targetFixAgeSec: ageSec,
    maxDistanceM: group.settings.maxDistanceM,
  });
  const evidence: SnipeEvidence = {
    ...base,
    distanceM: Math.round(geo.distanceM * 10) / 10,
    bearingDeg: Math.round(geo.bearingDeg),
    aimErrorDeg: Math.round(geo.aimErrorDeg),
  };
  if (!geo.ok) return { outcome: "REJECT", code: geo.reason as RejectCode, evidence };
  const minute = localMinuteOfDay(group.settings.timezone, now);
  if (findSafeZone(group.settings.safeZones, targetFix.lat, targetFix.lon, minute)) {
    return { outcome: "REJECT", code: "SAFE_ZONE", evidence };
  }
  return { outcome: "VERIFIED", evidence };
}

export interface AwardPlan {
  breakdown: PointsBreakdown;
  hunter: Pick<
    MemberDoc,
    "score" | "weeklyScore" | "weekId" | "streak" | "bestStreak" | "snipes" | "snipesToday" | "snipeDayId"
  >;
  /** Null when the target has left the group. */
  target: Pick<MemberDoc, "score" | "streak" | "timesSniped" | "immuneUntil"> | null;
  /** The hunter's next target, or null when they've hit the daily limit. */
  nextTargetUid: string | null;
}

/** Everything that changes when a snipe is confirmed. */
export function planAward(args: {
  group: GroupDoc;
  hunter: MemberDoc;
  target: MemberDoc | null;
  distanceM: number | null;
  assignedAt: number | null;
  capturedAt: number;
  now: number;
  rng: Rng;
}): AwardPlan {
  const { group, hunter, target, now } = args;
  const breakdown = calculatePoints({
    distanceM: args.distanceM,
    streak: hunter.streak,
    assignedAt: args.assignedAt,
    capturedAt: args.capturedAt,
  });
  const today = snipesToday(hunter, group) + 1;
  const streak = hunter.streak + 1;
  const sameWeek = hunter.weekId === group.weekId;
  const nextTargetUid =
    today >= group.settings.maxSnipesPerDay || group.memberIds.length < 3
      ? null
      : pickNextTarget(group.memberIds, hunter.uid, target ? [target.uid] : [], args.rng);
  return {
    breakdown,
    hunter: {
      score: hunter.score + breakdown.total,
      weeklyScore: (sameWeek ? hunter.weeklyScore : 0) + breakdown.total,
      weekId: group.weekId,
      streak,
      bestStreak: Math.max(hunter.bestStreak, streak),
      snipes: hunter.snipes + 1,
      snipesToday: today,
      snipeDayId: group.dayId,
    },
    target: target
      ? {
          score: Math.max(0, target.score - group.settings.snipedPenalty),
          streak: 0,
          timesSniped: target.timesSniped + 1,
          immuneUntil: now + group.settings.immunityMinutes * 60 * 1000,
        }
      : null,
    nextTargetUid,
  };
}

/** Undoes the hunter's points when an admin removes a verified snipe. */
export function planRemoval(hunter: MemberDoc, points: number, snipeWeekId: string | null) {
  return {
    score: Math.max(0, hunter.score - points),
    weeklyScore:
      snipeWeekId !== null && hunter.weekId === snipeWeekId
        ? Math.max(0, hunter.weeklyScore - points)
        : hunter.weeklyScore,
    snipes: Math.max(0, hunter.snipes - 1),
  };
}
