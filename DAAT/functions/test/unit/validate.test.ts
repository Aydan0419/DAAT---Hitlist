import { describe, expect, it } from "vitest";
import { id, mergeSettings, num, str, username, ValidationError } from "../../src/logic/validate";
import { DEFAULT_SETTINGS } from "../../src/types";

describe("input validation", () => {
  it("trims and bounds text", () => {
    expect(str("  hi  ", "x")).toBe("hi");
    expect(() => str("", "x")).toThrow(ValidationError);
    expect(() => str(5, "x")).toThrow(ValidationError);
    expect(() => str("a".repeat(41), "x", 1, 40)).toThrow(ValidationError);
  });

  it("rejects bad numbers", () => {
    expect(num(5, "x", 0, 10)).toBe(5);
    for (const bad of [NaN, Infinity, "5", null, 11, -1]) expect(() => num(bad, "x", 0, 10)).toThrow(ValidationError);
  });

  it("only accepts ids that are safe in a document path", () => {
    expect(id("abc_DEF-123", "x")).toBe("abc_DEF-123");
    for (const bad of ["", "a/b", "../x", "a b", 5, "a".repeat(129)]) expect(() => id(bad, "x")).toThrow(ValidationError);
  });

  it("normalises usernames", () => {
    expect(username("@Aydan_04")).toBe("aydan_04");
    for (const bad of ["ab", "has space", "emoji😀", "a".repeat(21), 7]) expect(() => username(bad)).toThrow(ValidationError);
  });
});

describe("mergeSettings", () => {
  it("applies a partial patch over the current settings", () => {
    const s = mergeSettings(DEFAULT_SETTINGS, { immunityMinutes: 30, hintEnabled: false });
    expect(s).toEqual({ ...DEFAULT_SETTINGS, immunityMinutes: 30, hintEnabled: false });
  });

  it("rejects out-of-range values", () => {
    for (const bad of [
      { reshuffleHour: 24 },
      { reshuffleHour: 7.5 },
      { maxSnipesPerDay: 0 },
      { immunityMinutes: -1 },
      { timezone: "Nowhere/Land" },
      { hintEnabled: "yes" },
      { maxDistanceM: 5000 },
      { quietHours: { startMinute: 0 } },
      { safeZones: [{ id: "z", name: "x", lat: 95, lon: 0, radiusM: 50 }] },
      { safeZones: [{ id: "z", name: "x", lat: 1, lon: 1, radiusM: 50, startMinute: 60 }] },
    ]) {
      expect(() => mergeSettings(DEFAULT_SETTINGS, bad)).toThrow(ValidationError);
    }
    expect(() => mergeSettings(DEFAULT_SETTINGS, "nope")).toThrow(ValidationError);
  });

  it("ignores unknown keys so clients can't smuggle fields in", () => {
    const s = mergeSettings(DEFAULT_SETTINGS, { adminId: "evil", score: 9999 });
    expect(s).toEqual(DEFAULT_SETTINGS);
  });

  it("accepts safe zones and quiet hours, and can clear quiet hours", () => {
    const s = mergeSettings(DEFAULT_SETTINGS, {
      safeZones: [{ id: "z1", name: "Library", lat: 41.8, lon: -87.6, radiusM: 120, startMinute: 480, endMinute: 900 }],
      quietHours: { startMinute: 1320, endMinute: 420 },
    });
    expect(s.safeZones).toHaveLength(1);
    expect(s.quietHours).toEqual({ startMinute: 1320, endMinute: 420 });
    expect(mergeSettings(s, { quietHours: null }).quietHours).toBeNull();
  });
});
