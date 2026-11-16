import { PointsBreakdown } from "../types";

export const BASE_SNIPE_POINTS = 100;
export const STREAK_BONUS_PER_LEVEL = 25;
export const CLOSE_QUARTERS_BONUS = 50;
export const CLOSE_QUARTERS_M = 5;
export const SPEED_BONUS = 75;
export const SPEED_WINDOW_MS = 2 * 60 * 60 * 1000;
/** Streak bonus stops growing here so one player can't run away with a group. */
export const MAX_STREAK_LEVELS = 20;

/**
 * Points for a successful snipe. `distanceM` is null when the distance could
 * not be measured (a snipe confirmed by the target instead of by GPS).
 */
export function calculatePoints(args: {
  distanceM: number | null;
  streak: number;
  assignedAt: number | null;
  capturedAt: number;
}): PointsBreakdown {
  const streak = Math.min(Math.max(args.streak, 0), MAX_STREAK_LEVELS) * STREAK_BONUS_PER_LEVEL;
  const closeQuarters =
    args.distanceM !== null && args.distanceM < CLOSE_QUARTERS_M ? CLOSE_QUARTERS_BONUS : 0;
  const elapsed = args.assignedAt === null ? null : args.capturedAt - args.assignedAt;
  const speed = elapsed !== null && elapsed >= 0 && elapsed < SPEED_WINDOW_MS ? SPEED_BONUS : 0;
  return {
    base: BASE_SNIPE_POINTS,
    streak,
    closeQuarters,
    speed,
    total: BASE_SNIPE_POINTS + streak + closeQuarters + speed,
  };
}
