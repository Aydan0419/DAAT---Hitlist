import { describe, expect, it } from "vitest";
import fc from "fast-check";
import {
  angleDiffDeg,
  bearingDeg,
  compass8,
  distanceM,
  findSafeZone,
  inMinuteWindow,
  proximityHint,
  verifyGeometry,
} from "../../src/logic/geo";

// Roughly: 1 degree of latitude = 111.2 km.
const M = 1 / 111_195;
const base = { lat: 41.8781, lon: -87.6298 };
/** A point `north` metres north and `east` metres east of base. */
const offset = (north: number, east: number) => ({
  lat: base.lat + north * M,
  lon: base.lon + (east * M) / Math.cos((base.lat * Math.PI) / 180),
});

describe("distance and bearing", () => {
  it("measures known distances", () => {
    expect(distanceM(base.lat, base.lon, base.lat, base.lon)).toBe(0);
    const p = offset(100, 0);
    expect(distanceM(base.lat, base.lon, p.lat, p.lon)).toBeCloseTo(100, 0);
    // Chicago to New York is about 1,145 km.
    expect(distanceM(41.8781, -87.6298, 40.7128, -74.006) / 1000).toBeCloseTo(1145, -1);
  });

  it("gives compass bearings", () => {
    const n = offset(100, 0), e = offset(0, 100), s = offset(-100, 0), w = offset(0, -100);
    expect(bearingDeg(base.lat, base.lon, n.lat, n.lon)).toBeCloseTo(0, 0);
    expect(bearingDeg(base.lat, base.lon, e.lat, e.lon)).toBeCloseTo(90, 0);
    expect(bearingDeg(base.lat, base.lon, s.lat, s.lon)).toBeCloseTo(180, 0);
    expect(bearingDeg(base.lat, base.lon, w.lat, w.lon)).toBeCloseTo(270, 0);
  });

  it("wraps angle differences", () => {
    expect(angleDiffDeg(350, 10)).toBeCloseTo(20);
    expect(angleDiffDeg(10, 350)).toBeCloseTo(20);
    expect(angleDiffDeg(0, 180)).toBeCloseTo(180);
    expect(angleDiffDeg(90, 90)).toBe(0);
  });

  it("angle difference is symmetric and within 0-180", () => {
    fc.assert(
      fc.property(fc.double({ min: 0, max: 360, noNaN: true }), fc.double({ min: 0, max: 360, noNaN: true }), (a, b) => {
        const d = angleDiffDeg(a, b);
        expect(d).toBeGreaterThanOrEqual(0);
        expect(d).toBeLessThanOrEqual(180.000001);
        expect(d).toBeCloseTo(angleDiffDeg(b, a), 6);
      }),
    );
  });
});

describe("verifyGeometry", () => {
  const check = (north: number, east: number, heading: number, acc = 5, extra = {}) => {
    const t = offset(north, east);
    return verifyGeometry({
      hunterLat: base.lat,
      hunterLon: base.lon,
      hunterAccuracyM: acc,
      headingDeg: heading,
      targetLat: t.lat,
      targetLon: t.lon,
      targetAccuracyM: acc,
      targetFixAgeSec: 0,
      maxDistanceM: 50,
      ...extra,
    });
  };

  it("accepts a well-aimed snipe in range", () => {
    const r = check(40, 0, 0);
    expect(r.ok).toBe(true);
    expect(r.distanceM).toBeCloseTo(40, 0);
  });

  it("rejects a target that is too far", () => {
    expect(check(200, 0, 0).reason).toBe("TOO_FAR");
  });

  it("stretches the distance limit by the GPS uncertainty", () => {
    expect(check(58, 0, 0, 5).ok).toBe(true); // 50 + 5 + 5
    expect(check(62, 0, 0, 5).reason).toBe("TOO_FAR");
    expect(check(62, 0, 0, 10).ok).toBe(true); // 50 + 10 + 10
  });

  it("never accepts beyond the hard limit, however bad the GPS", () => {
    expect(check(160, 0, 0, 100).reason).toBe("TOO_FAR");
  });

  it("rejects when the phone points the wrong way at range", () => {
    expect(check(40, 0, 180).reason).toBe("WRONG_ORIENTATION");
    expect(check(40, 0, 90).reason).toBe("WRONG_ORIENTATION");
  });

  it("allows a wider aim error as the target gets closer", () => {
    // Same 45-degree aim error, same GPS accuracy.
    expect(check(45, 0, 45, 5).ok).toBe(false);
    expect(check(14, 0, 45, 5).ok).toBe(true);
    const far = check(45, 0, 0, 5).allowedAimErrorDeg;
    const near = check(14, 0, 0, 5).allowedAimErrorDeg;
    expect(near).toBeGreaterThan(far);
  });

  it("skips the aim check when the two fixes overlap", () => {
    const r = check(6, 0, 180, 5);
    expect(r.allowedAimErrorDeg).toBe(180);
    expect(r.ok).toBe(true);
  });

  it("allows for a target who may have moved since their last fix", () => {
    const stale = check(40, 0, 0, 5, { targetFixAgeSec: 60 });
    const fresh = check(40, 0, 0, 5, { targetFixAgeSec: 0 });
    expect(stale.allowedDistanceM).toBeGreaterThan(fresh.allowedDistanceM);
  });

  it("treats missing accuracy as a typical 20 m", () => {
    const t = offset(40, 0);
    const r = verifyGeometry({
      hunterLat: base.lat, hunterLon: base.lon, hunterAccuracyM: null, headingDeg: 0,
      targetLat: t.lat, targetLon: t.lon, targetAccuracyM: null, targetFixAgeSec: 0, maxDistanceM: 50,
    });
    expect(r.allowedDistanceM).toBe(90);
  });

  it("the aim tolerance never drops below 20 degrees or exceeds 180", () => {
    fc.assert(
      fc.property(
        fc.double({ min: -140, max: 140, noNaN: true }),
        fc.double({ min: -140, max: 140, noNaN: true }),
        fc.double({ min: 0, max: 360, noNaN: true }),
        fc.double({ min: 1, max: 100, noNaN: true }),
        (n, e, h, acc) => {
          const r = check(n, e, h, acc);
          expect(r.allowedAimErrorDeg).toBeGreaterThanOrEqual(20);
          expect(r.allowedAimErrorDeg).toBeLessThanOrEqual(180);
          expect(r.allowedDistanceM).toBeLessThanOrEqual(150);
          if (r.ok) expect(r.distanceM).toBeLessThanOrEqual(150);
        },
      ),
    );
  });
});

describe("proximityHint", () => {
  const hint = (north: number, east: number) => {
    const t = offset(north, east);
    return proximityHint(base.lat, base.lon, t.lat, t.lon);
  };

  it("uses distance bands", () => {
    expect(hint(10, 0).band).toBe("VERY_CLOSE");
    expect(hint(60, 0).band).toBe("CLOSE");
    expect(hint(500, 0).band).toBe("NEAR");
    expect(hint(5000, 0).band).toBe("FAR");
  });

  it("hides the direction when far", () => {
    expect(hint(5000, 0).direction).toBeNull();
  });

  it("rounds direction to 8 points", () => {
    expect(hint(500, 0).direction).toBe("N");
    expect(hint(500, 500).direction).toBe("NE");
    expect(hint(0, -500).direction).toBe("W");
    expect(hint(-500, 60).direction).toBe("S");
    expect(compass8(359)).toBe("N");
    expect(compass8(22.4)).toBe("N");
    expect(compass8(22.6)).toBe("NE");
  });
});

describe("safe zones", () => {
  const school = { id: "z1", name: "School", ...base, radiusM: 100 };

  it("matches points inside the radius", () => {
    const inside = offset(50, 0), outside = offset(150, 0);
    expect(findSafeZone([school], inside.lat, inside.lon, 600)?.name).toBe("School");
    expect(findSafeZone([school], outside.lat, outside.lon, 600)).toBeNull();
  });

  it("respects a time window", () => {
    const timed = { ...school, startMinute: 8 * 60, endMinute: 15 * 60 };
    expect(findSafeZone([timed], base.lat, base.lon, 9 * 60)).not.toBeNull();
    expect(findSafeZone([timed], base.lat, base.lon, 16 * 60)).toBeNull();
  });

  it("handles windows that wrap past midnight", () => {
    expect(inMinuteWindow(23 * 60, 22 * 60, 6 * 60)).toBe(true);
    expect(inMinuteWindow(3 * 60, 22 * 60, 6 * 60)).toBe(true);
    expect(inMinuteWindow(12 * 60, 22 * 60, 6 * 60)).toBe(false);
    expect(inMinuteWindow(6 * 60, 22 * 60, 6 * 60)).toBe(false);
  });
});
