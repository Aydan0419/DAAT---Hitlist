import { describe, expect, it } from "vitest";
import { dayId, isValidTimezone, localMinuteOfDay, localParts, nextLocalHour, weekId } from "../../src/logic/time";

const CHI = "America/Chicago";
const at = (iso: string) => Date.parse(iso);

describe("local time", () => {
  it("converts to a timezone", () => {
    // 2026-11-16 03:00 UTC is 21:00 on Sunday the 15th in Chicago (CST, UTC-6).
    const p = localParts(CHI, at("2026-11-16T03:00:00Z"));
    expect(p).toMatchObject({ year: 2026, month: 11, day: 15, hour: 21, minute: 0, weekday: 0 });
    expect(dayId(CHI, at("2026-11-16T03:00:00Z"))).toBe("2026-11-15");
    expect(localMinuteOfDay(CHI, at("2026-11-16T03:30:00Z"))).toBe(21 * 60 + 30);
  });

  it("reports midnight as hour 0", () => {
    expect(localParts(CHI, at("2026-11-16T06:00:00Z")).hour).toBe(0);
  });

  it("validates timezones", () => {
    expect(isValidTimezone("America/Chicago")).toBe(true);
    expect(isValidTimezone("Mars/Olympus")).toBe(false);
    expect(isValidTimezone(42)).toBe(false);
  });
});

describe("weekId", () => {
  it("is the Monday of the local week", () => {
    expect(weekId(CHI, at("2026-11-16T03:00:00Z"))).toBe("2026-11-09"); // Sunday -> previous Monday
    expect(weekId(CHI, at("2026-11-16T12:00:00Z"))).toBe("2026-11-16"); // Monday
    expect(weekId(CHI, at("2026-11-20T12:00:00Z"))).toBe("2026-11-16"); // Friday
  });

  it("crosses month and year boundaries", () => {
    expect(weekId("UTC", at("2026-01-01T12:00:00Z"))).toBe("2025-12-29"); // Thursday
    expect(weekId("UTC", at("2026-03-01T12:00:00Z"))).toBe("2026-02-23"); // Sunday
  });
});

describe("nextLocalHour", () => {
  it("finds 8am later the same day", () => {
    const next = nextLocalHour(CHI, 8, at("2026-11-16T09:00:00Z")); // 3am local
    expect(new Date(next).toISOString()).toBe("2026-11-16T14:00:00.000Z");
  });

  it("rolls to tomorrow once the hour has passed", () => {
    const next = nextLocalHour(CHI, 8, at("2026-11-16T14:00:00Z")); // exactly 8am
    expect(new Date(next).toISOString()).toBe("2026-11-17T14:00:00.000Z");
  });

  it("is always in the future and at most about a day away", () => {
    for (const tz of [CHI, "Asia/Kolkata", "Asia/Kathmandu", "Australia/Lord_Howe", "UTC", "Pacific/Auckland"]) {
      for (let hour = 0; hour < 24; hour++) {
        for (const start of ["2026-03-07T00:07:00Z", "2026-07-01T13:59:59Z", "2026-11-01T05:30:00Z"]) {
          const after = at(start);
          const next = nextLocalHour(tz, hour, after);
          expect(next).toBeGreaterThan(after);
          expect(next - after).toBeLessThanOrEqual(25.5 * 3_600_000);
          const p = localParts(tz, next);
          // Lands on the hour, except when daylight saving skips it.
          if (p.hour === hour) expect(p.minute).toBe(0);
        }
      }
    }
  });

  it("handles half-hour offset zones", () => {
    const next = nextLocalHour("Asia/Kolkata", 8, at("2026-11-16T00:00:00Z")); // 05:30 local
    expect(new Date(next).toISOString()).toBe("2026-11-16T02:30:00.000Z");
  });

  it("copes with the hour daylight saving skips", () => {
    // US clocks jump from 2:00 to 3:00 on 2026-03-08.
    const next = nextLocalHour(CHI, 2, at("2026-03-08T07:00:00Z")); // 01:00 local
    expect(new Date(next).toISOString()).toBe("2026-03-08T08:00:00.000Z"); // 03:00 local
  });

  it("steps a daily schedule forward one day at a time across a DST change", () => {
    let t = at("2026-03-06T15:00:00Z");
    const days: string[] = [];
    for (let i = 0; i < 5; i++) {
      t = nextLocalHour(CHI, 8, t);
      days.push(dayId(CHI, t));
      expect(localParts(CHI, t).hour).toBe(8);
    }
    expect(days).toEqual(["2026-03-07", "2026-03-08", "2026-03-09", "2026-03-10", "2026-03-11"]);
  });
});
