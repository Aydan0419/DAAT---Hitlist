import { describe, expect, it } from "vitest";
import {
  checkEligibility,
  judgeLocation,
  planAward,
  planRemoval,
  SnipeAttempt,
} from "../../src/logic/engine";
import { AssignmentDoc, DEFAULT_SETTINGS, GroupDoc, LocationFix, MemberDoc } from "../../src/types";

const NOW = Date.parse("2026-11-16T18:00:00Z"); // noon in Chicago
const M = 1 / 111_195;
const here = { lat: 41.8781, lon: -87.6298 };

const group = (over: Partial<GroupDoc> = {}, settings: Partial<GroupDoc["settings"]> = {}): GroupDoc => ({
  id: "g1",
  name: "Dorm 4",
  adminId: "hunter",
  inviteCode: "ABCDEF",
  memberIds: ["hunter", "target", "other", "fourth"],
  settings: { ...DEFAULT_SETTINGS, ...settings },
  dayId: "2026-11-16",
  weekId: "2026-11-16",
  nextReshuffleAt: NOW + 3_600_000,
  createdAt: 0,
  ...over,
});

const member = (uid: string, over: Partial<MemberDoc> = {}): MemberDoc => ({
  uid,
  name: uid,
  username: uid,
  photoUrl: null,
  score: 0,
  weeklyScore: 0,
  weekId: "2026-11-16",
  streak: 0,
  bestStreak: 0,
  snipes: 0,
  timesSniped: 0,
  snipesToday: 0,
  snipeDayId: "2026-11-16",
  immuneUntil: 0,
  joinedAt: 0,
  ...over,
});

const assignment = (over: Partial<AssignmentDoc> = {}): AssignmentDoc => ({
  targetUid: "target",
  assignedAt: NOW - 3 * 3_600_000,
  pendingSnipeId: null,
  ...over,
});

const attempt = (over: Partial<SnipeAttempt> = {}): SnipeAttempt => ({
  hunterUid: "hunter",
  targetUid: "target",
  ...here,
  accuracyM: 5,
  headingDeg: 0,
  capturedAt: NOW - 10_000,
  ...over,
});

const eligible = (over: Partial<Parameters<typeof checkEligibility>[0]> = {}) =>
  checkEligibility({
    group: group(),
    hunter: member("hunter"),
    target: member("target"),
    assignment: assignment(),
    attempt: attempt(),
    snipeId: "s1",
    now: NOW,
    ...over,
  });

describe("checkEligibility", () => {
  it("passes a normal attempt", () => {
    expect(eligible()).toBeNull();
  });

  it("rejects non-members", () => {
    expect(eligible({ hunter: null })).toBe("NOT_A_MEMBER");
    expect(eligible({ group: group({ memberIds: ["target", "other", "fourth"] }) })).toBe("NOT_A_MEMBER");
  });

  it("rejects when the group is too small", () => {
    expect(eligible({ group: group({ memberIds: ["hunter", "target"] }) })).toBe("GROUP_TOO_SMALL");
  });

  it("rejects sniping someone who isn't your target", () => {
    expect(eligible({ attempt: attempt({ targetUid: "other" }), target: member("other") })).toBe("NOT_YOUR_TARGET");
    expect(eligible({ target: null })).toBe("NOT_YOUR_TARGET");
  });

  it("rejects when there is no assignment", () => {
    expect(eligible({ assignment: null })).toBe("NO_TARGET");
  });

  it("explains a missing assignment as the daily limit when that's why", () => {
    expect(eligible({ assignment: null, hunter: member("hunter", { snipesToday: 3 }) })).toBe("DAILY_LIMIT");
  });

  it("enforces the daily limit, but only for the current day", () => {
    expect(eligible({ hunter: member("hunter", { snipesToday: 3 }) })).toBe("DAILY_LIMIT");
    expect(eligible({ hunter: member("hunter", { snipesToday: 3, snipeDayId: "2026-11-15" }) })).toBeNull();
  });

  it("blocks a second attempt while one is pending, but lets the same one retry", () => {
    expect(eligible({ assignment: assignment({ pendingSnipeId: "s0" }) })).toBe("SNIPE_PENDING");
    expect(eligible({ assignment: assignment({ pendingSnipeId: "s1" }) })).toBeNull();
  });

  it("respects immunity", () => {
    expect(eligible({ target: member("target", { immuneUntil: NOW + 1 }) })).toBe("TARGET_IMMUNE");
    expect(eligible({ target: member("target", { immuneUntil: NOW - 1 }) })).toBeNull();
  });

  it("rejects old photos and photos from the future", () => {
    expect(eligible({ attempt: attempt({ capturedAt: NOW - 6 * 60_000 }) })).toBe("PHOTO_TOO_OLD");
    expect(eligible({ attempt: attempt({ capturedAt: NOW + 5 * 60_000 }) })).toBe("PHOTO_TOO_OLD");
    expect(eligible({ attempt: attempt({ capturedAt: NOW + 30_000 }) })).toBeNull();
  });

  it("rejects during quiet hours", () => {
    const quiet = group({}, { quietHours: { startMinute: 11 * 60, endMinute: 13 * 60 } });
    expect(eligible({ group: quiet })).toBe("QUIET_HOURS");
  });

  it("rejects a hunter standing in a safe zone", () => {
    const zoned = group({}, { safeZones: [{ id: "z", name: "Class", ...here, radiusM: 80 }] });
    expect(eligible({ group: zoned })).toBe("SAFE_ZONE");
  });
});

describe("judgeLocation", () => {
  const fix = (north: number, ageMs = 5_000): LocationFix => ({
    lat: here.lat + north * M,
    lon: here.lon,
    accuracyM: 5,
    updatedAt: NOW - ageMs,
  });
  const judge = (targetFix: LocationFix | null, over: Partial<SnipeAttempt> = {}, g = group()) =>
    judgeLocation({ group: g, attempt: attempt(over), targetFix, now: NOW });

  it("verifies with a fresh fix in range and on aim", () => {
    const v = judge(fix(30));
    expect(v.outcome).toBe("VERIFIED");
    expect(v.evidence.distanceM).toBeCloseTo(30, 0);
    expect(v.evidence.targetFixAgeSec).toBe(5);
  });

  it("rejects out of range", () => {
    const v = judge(fix(400));
    expect(v).toMatchObject({ outcome: "REJECT", code: "TOO_FAR" });
  });

  it("rejects pointing away", () => {
    expect(judge(fix(40), { headingDeg: 180 })).toMatchObject({ outcome: "REJECT", code: "WRONG_ORIENTATION" });
  });

  it("leaves it pending when the target has no location", () => {
    const v = judge(null);
    expect(v.outcome).toBe("PENDING");
    expect(v.evidence.distanceM).toBeNull();
  });

  it("leaves it pending when the target's fix is stale, even if it looks far away", () => {
    expect(judge(fix(5000, 10 * 60_000)).outcome).toBe("PENDING");
  });

  it("rejects when the target is inside a safe zone", () => {
    const t = fix(40);
    const zoned = group({}, { safeZones: [{ id: "z", name: "Home", lat: t.lat, lon: t.lon, radiusM: 15 }] });
    expect(judge(t, {}, zoned)).toMatchObject({ outcome: "REJECT", code: "SAFE_ZONE" });
  });

  it("honours the group's max distance setting", () => {
    expect(judge(fix(80)).outcome).toBe("REJECT");
    expect(judge(fix(80), {}, group({}, { maxDistanceM: 100 })).outcome).toBe("VERIFIED");
  });
});

describe("planAward", () => {
  const award = (over: Partial<Parameters<typeof planAward>[0]> = {}) =>
    planAward({
      group: group(),
      hunter: member("hunter"),
      target: member("target"),
      distanceM: 30,
      assignedAt: NOW - 3 * 3_600_000,
      capturedAt: NOW,
      now: NOW,
      rng: () => 0,
      ...over,
    });

  it("credits the hunter", () => {
    const p = award({ hunter: member("hunter", { score: 500, weeklyScore: 200, streak: 2, bestStreak: 2, snipes: 4 }) });
    expect(p.breakdown.total).toBe(150);
    expect(p.hunter).toMatchObject({ score: 650, weeklyScore: 350, streak: 3, bestStreak: 3, snipes: 5, snipesToday: 1 });
  });

  it("starts the weekly score again in a new week", () => {
    const p = award({ hunter: member("hunter", { weeklyScore: 900, weekId: "2026-11-09" }) });
    expect(p.hunter.weeklyScore).toBe(100);
    expect(p.hunter.weekId).toBe("2026-11-16");
  });

  it("counts today's snipes from zero on a new day", () => {
    const p = award({ hunter: member("hunter", { snipesToday: 3, snipeDayId: "2026-11-15" }) });
    expect(p.hunter.snipesToday).toBe(1);
    expect(p.hunter.snipeDayId).toBe("2026-11-16");
  });

  it("keeps the best streak", () => {
    expect(award({ hunter: member("hunter", { streak: 1, bestStreak: 9 }) }).hunter.bestStreak).toBe(9);
  });

  it("resets the target's streak and makes them immune", () => {
    const p = award({ target: member("target", { streak: 7, score: 300, timesSniped: 1 }) });
    expect(p.target).toEqual({ score: 300, streak: 0, timesSniped: 2, immuneUntil: NOW + 60 * 60_000 });
  });

  it("applies the optional penalty without going below zero", () => {
    const g = group({}, { snipedPenalty: 50 });
    expect(award({ group: g, target: member("target", { score: 300 }) }).target!.score).toBe(250);
    expect(award({ group: g, target: member("target", { score: 20 }) }).target!.score).toBe(0);
  });

  it("gives the hunter a new target who isn't themselves or the one just sniped", () => {
    for (const r of [0, 0.3, 0.6, 0.999]) {
      const next = award({ rng: () => r }).nextTargetUid;
      expect(["other", "fourth"]).toContain(next);
    }
  });

  it("gives no new target once the daily limit is reached", () => {
    expect(award({ hunter: member("hunter", { snipesToday: 2 }) }).nextTargetUid).toBeNull();
    expect(award({ hunter: member("hunter", { snipesToday: 1 }) }).nextTargetUid).not.toBeNull();
  });

  it("still credits the hunter if the target has left the group", () => {
    const p = award({ target: null });
    expect(p.target).toBeNull();
    expect(p.hunter.score).toBe(100);
  });

  it("awards the speed bonus and no distance bonus for a target-confirmed snipe", () => {
    const p = award({ distanceM: null, assignedAt: NOW - 60_000 });
    expect(p.breakdown).toMatchObject({ closeQuarters: 0, speed: 75, total: 175 });
  });
});

describe("planRemoval", () => {
  it("returns the points, including this week's", () => {
    const h = member("hunter", { score: 400, weeklyScore: 250, snipes: 3 });
    expect(planRemoval(h, 150, "2026-11-16")).toEqual({ score: 250, weeklyScore: 100, snipes: 2 });
  });

  it("leaves the weekly score alone for a snipe from an earlier week", () => {
    const h = member("hunter", { score: 400, weeklyScore: 250, snipes: 3 });
    expect(planRemoval(h, 150, "2026-11-09")).toEqual({ score: 250, weeklyScore: 250, snipes: 2 });
  });

  it("never goes negative", () => {
    expect(planRemoval(member("hunter", { score: 50 }), 150, null)).toEqual({ score: 0, weeklyScore: 0, snipes: 0 });
  });
});
