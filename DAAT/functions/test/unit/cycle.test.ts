import { describe, expect, it } from "vitest";
import fc from "fast-check";
import {
  Assignments,
  buildCycle,
  insertMember,
  isSingleCycle,
  pickNextTarget,
  removeMember,
} from "../../src/logic/cycle";

/** A deterministic random source driven by fast-check. */
const rngFrom = (seed: number) => {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 2 ** 32;
  };
};

const members = (n: number) => Array.from({ length: n }, (_, i) => `p${i}`);
const huntedBy = (a: Assignments) => {
  const count: Record<string, number> = {};
  for (const t of Object.values(a)) count[t] = (count[t] ?? 0) + 1;
  return count;
};

describe("buildCycle", () => {
  it("returns nothing for fewer than 3 players", () => {
    expect(buildCycle(["a", "b"], {}, Math.random)).toEqual({});
    expect(buildCycle([], {}, Math.random)).toEqual({});
  });

  it("every member hunts exactly one and is hunted by exactly one", () => {
    fc.assert(
      fc.property(fc.integer({ min: 3, max: 50 }), fc.integer(), (n, seed) => {
        const m = members(n);
        const a = buildCycle(m, {}, rngFrom(seed));
        expect(Object.keys(a).sort()).toEqual([...m].sort());
        for (const p of m) {
          expect(a[p]).not.toBe(p);
          expect(huntedBy(a)[p]).toBe(1);
        }
        expect(isSingleCycle(a, m)).toBe(true);
      }),
    );
  });

  it("ignores duplicate member ids", () => {
    const a = buildCycle(["a", "b", "c", "a"], {}, rngFrom(1));
    expect(isSingleCycle(a, ["a", "b", "c"])).toBe(true);
  });

  it("avoids repeating yesterday's pairs when it can", () => {
    fc.assert(
      fc.property(fc.integer({ min: 6, max: 30 }), fc.integer(), (n, seed) => {
        const m = members(n);
        const rng = rngFrom(seed);
        const yesterday = buildCycle(m, {}, rng);
        const today = buildCycle(m, yesterday, rng);
        const repeats = m.filter((p) => today[p] === yesterday[p]).length;
        expect(repeats).toBeLessThanOrEqual(2);
      }),
    );
  });
});

describe("insertMember", () => {
  it("keeps a single cycle and changes exactly one existing target", () => {
    fc.assert(
      fc.property(fc.integer({ min: 3, max: 40 }), fc.integer(), (n, seed) => {
        const m = members(n);
        const rng = rngFrom(seed);
        const before = buildCycle(m, {}, rng);
        const after = insertMember(before, [...m, "new"], "new", rng);
        expect(isSingleCycle(after, [...m, "new"])).toBe(true);
        expect(m.filter((p) => after[p] !== before[p])).toHaveLength(1);
      }),
    );
  });

  it("starts the game when the third player joins", () => {
    const a = insertMember({}, ["a", "b", "c"], "c", rngFrom(7));
    expect(isSingleCycle(a, ["a", "b", "c"])).toBe(true);
  });

  it("gives nobody a target while the group is too small", () => {
    expect(insertMember({}, ["a", "b"], "b", rngFrom(7))).toEqual({});
  });

  it("still gives the newcomer a target when the cycle has been broken by snipes", () => {
    // p0 already sniped and was re-assigned, so two players hunt p2.
    const broken: Assignments = { p0: "p2", p1: "p2", p2: "p0" };
    const after = insertMember(broken, ["p0", "p1", "p2", "new"], "new", rngFrom(3));
    expect(after.new).toBeDefined();
    expect(after.new).not.toBe("new");
    expect(Object.values(after)).toContain("new");
  });
});

describe("removeMember", () => {
  it("stitches the cycle closed", () => {
    fc.assert(
      fc.property(fc.integer({ min: 4, max: 40 }), fc.integer(), (n, seed) => {
        const m = members(n);
        const rng = rngFrom(seed);
        const before = buildCycle(m, {}, rng);
        const gone = m[Math.floor(rng() * n)];
        const remaining = m.filter((p) => p !== gone);
        const after = removeMember(before, remaining, gone, rng);
        expect(isSingleCycle(after, remaining)).toBe(true);
        // Only the leaver's hunter got a new target.
        expect(remaining.filter((p) => after[p] !== before[p])).toHaveLength(1);
      }),
    );
  });

  it("clears every target when the group drops below 3", () => {
    const before = buildCycle(["a", "b", "c"], {}, rngFrom(1));
    expect(removeMember(before, ["a", "b"], "c", rngFrom(1))).toEqual({});
  });

  it("never leaves anyone hunting themselves or a non-member", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 4, max: 12 }),
        fc.integer(),
        fc.array(fc.integer({ min: 0, max: 11 }), { minLength: 4, maxLength: 12 }),
        (n, seed, targets) => {
          // Arbitrary (possibly broken) assignments, as happen mid-day.
          const m = members(n);
          const current: Assignments = {};
          m.forEach((p, i) => {
            const t = m[targets[i % targets.length] % n];
            if (t !== p) current[p] = t;
          });
          const gone = m[0];
          const remaining = m.slice(1);
          const after = removeMember(current, remaining, gone, rngFrom(seed));
          for (const [h, t] of Object.entries(after)) {
            expect(remaining).toContain(h);
            expect(remaining).toContain(t);
            expect(t).not.toBe(h);
          }
          // Nobody who had a target loses it.
          for (const p of remaining) if (current[p]) expect(after[p]).toBeDefined();
        },
      ),
    );
  });
});

describe("pickNextTarget", () => {
  it("never picks the hunter and avoids the player just sniped", () => {
    fc.assert(
      fc.property(fc.integer({ min: 3, max: 30 }), fc.integer(), (n, seed) => {
        const m = members(n);
        const t = pickNextTarget(m, "p0", ["p1"], rngFrom(seed));
        expect(t).not.toBe("p0");
        expect(t).not.toBe("p1");
        expect(m).toContain(t);
      }),
    );
  });

  it("falls back to the avoided player if nobody else is left", () => {
    expect(pickNextTarget(["a", "b"], "a", ["b"], rngFrom(1))).toBe("b");
    expect(pickNextTarget(["a"], "a", [], rngFrom(1))).toBeNull();
  });
});
