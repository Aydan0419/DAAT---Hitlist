import { describe, expect, it } from "vitest";
import { calculatePoints, MAX_STREAK_LEVELS } from "../../src/logic/scoring";

const HOUR = 3_600_000;

describe("calculatePoints", () => {
  it("gives the base 100 with no bonuses", () => {
    const p = calculatePoints({ distanceM: 30, streak: 0, assignedAt: 0, capturedAt: 3 * HOUR });
    expect(p).toEqual({ base: 100, streak: 0, closeQuarters: 0, speed: 0, total: 100 });
  });

  it("adds 25 per streak level", () => {
    expect(calculatePoints({ distanceM: 30, streak: 3, assignedAt: 0, capturedAt: 3 * HOUR }).total).toBe(175);
  });

  it("caps the streak bonus", () => {
    const p = calculatePoints({ distanceM: 30, streak: 999, assignedAt: 0, capturedAt: 3 * HOUR });
    expect(p.streak).toBe(MAX_STREAK_LEVELS * 25);
  });

  it("adds the close-quarters bonus under 5 m only", () => {
    expect(calculatePoints({ distanceM: 4.9, streak: 0, assignedAt: 0, capturedAt: 3 * HOUR }).closeQuarters).toBe(50);
    expect(calculatePoints({ distanceM: 5, streak: 0, assignedAt: 0, capturedAt: 3 * HOUR }).closeQuarters).toBe(0);
  });

  it("gives no close-quarters bonus when distance is unknown", () => {
    expect(calculatePoints({ distanceM: null, streak: 0, assignedAt: 0, capturedAt: 3 * HOUR }).closeQuarters).toBe(0);
  });

  it("adds the speed bonus within 2 hours of assignment", () => {
    expect(calculatePoints({ distanceM: 30, streak: 0, assignedAt: 0, capturedAt: 2 * HOUR - 1 }).speed).toBe(75);
    expect(calculatePoints({ distanceM: 30, streak: 0, assignedAt: 0, capturedAt: 2 * HOUR }).speed).toBe(0);
    expect(calculatePoints({ distanceM: 30, streak: 0, assignedAt: null, capturedAt: 1 }).speed).toBe(0);
  });

  it("stacks every bonus", () => {
    expect(calculatePoints({ distanceM: 2, streak: 2, assignedAt: 0, capturedAt: HOUR }).total).toBe(100 + 50 + 50 + 75);
  });
});
