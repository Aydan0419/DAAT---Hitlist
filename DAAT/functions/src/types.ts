/** Shared document shapes. All times are epoch milliseconds unless noted. */

export interface SafeZone {
  id: string;
  name: string;
  lat: number;
  lon: number;
  radiusM: number;
  /** Optional daily window in group-local minutes since midnight. Omitted = always active. */
  startMinute?: number | null;
  endMinute?: number | null;
}

export interface QuietHours {
  startMinute: number;
  endMinute: number;
}

export interface GroupSettings {
  /** Hour of day (0-23, group-local) when targets are reshuffled. */
  reshuffleHour: number;
  /** IANA timezone, e.g. "America/Chicago". */
  timezone: string;
  maxSnipesPerDay: number;
  immunityMinutes: number;
  /** Points taken from a player when they get sniped. */
  snipedPenalty: number;
  hintEnabled: boolean;
  maxDistanceM: number;
  safeZones: SafeZone[];
  /** Sniping is off everywhere during this window. */
  quietHours: QuietHours | null;
}

export const DEFAULT_SETTINGS: GroupSettings = {
  reshuffleHour: 8,
  timezone: "America/Chicago",
  // 1 keeps the game strictly one hunter per target: after a snipe the hunter waits for the next reshuffle.
  maxSnipesPerDay: 1,
  immunityMinutes: 60,
  snipedPenalty: 0,
  hintEnabled: true,
  maxDistanceM: 50,
  safeZones: [],
  quietHours: null,
};

export interface GroupDoc {
  id: string;
  name: string;
  adminId: string;
  inviteCode: string;
  memberIds: string[];
  settings: GroupSettings;
  /** Local date (YYYY-MM-DD) of the most recent reshuffle. */
  dayId: string;
  /** Local date of the Monday of the current week; rolls over at Monday's reshuffle. */
  weekId: string;
  nextReshuffleAt: number;
  createdAt: number;
}

export interface MemberDoc {
  uid: string;
  name: string;
  username: string;
  photoUrl: string | null;
  score: number;
  weeklyScore: number;
  weekId: string;
  streak: number;
  bestStreak: number;
  snipes: number;
  timesSniped: number;
  snipesToday: number;
  snipeDayId: string;
  immuneUntil: number;
  joinedAt: number;
  /** Set by the group admin: this player's shot sounds are not attached to their snipes. */
  soundMuted?: boolean;
}

export interface AssignmentDoc {
  targetUid: string;
  assignedAt: number;
  /** Set while a snipe on this target is waiting for confirmation. */
  pendingSnipeId: string | null;
}

export type SnipeStatus = "PENDING" | "VERIFIED" | "DISPUTED" | "REJECTED" | "REMOVED";

/**
 * Raw verification data. Stored at snipes/{id}/private/evidence, which only
 * Cloud Functions can read, because it contains the hunter's coordinates.
 */
export interface SnipeEvidence {
  hunterLat: number;
  hunterLon: number;
  hunterAccuracyM: number;
  headingDeg: number;
  distanceM: number | null;
  bearingDeg: number | null;
  aimErrorDeg: number | null;
  targetAccuracyM: number | null;
  targetFixAgeSec: number | null;
}

export interface PointsBreakdown {
  base: number;
  streak: number;
  closeQuarters: number;
  speed: number;
  total: number;
}

export interface SnipeDoc {
  id: string;
  groupId: string;
  hunterUid: string;
  targetUid: string;
  hunterName: string;
  targetName: string;
  photoPath: string;
  /** The hunter's recorded shot sound, if they attached one and are not muted in this group. */
  soundPath?: string | null;
  status: SnipeStatus;
  points: number;
  breakdown: PointsBreakdown | null;
  /** Measured hunter-to-target distance; null when the target's location wasn't fresh. */
  distanceM: number | null;
  assignedAt: number;
  capturedAt: number;
  createdAt: number;
  resolvedAt: number | null;
  likedBy: string[];
  /** Week the points were credited to, so removal can undo them. */
  weekId: string | null;
}

export interface UserDoc {
  id: string;
  name: string;
  username: string;
  photoUrl: string | null;
  createdAt: number;
}

export interface LocationFix {
  lat: number;
  lon: number;
  accuracyM: number;
  updatedAt: number;
}
