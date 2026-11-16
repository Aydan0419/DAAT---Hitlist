/**
 * Target assignment. An assignment map is hunter uid -> target uid.
 * After a reshuffle it is a single cycle through every member, so each
 * player hunts exactly one person and is hunted by exactly one.
 */

export type Assignments = Record<string, string>;
export type Rng = () => number;

export const MIN_PLAYERS = 3;

export function shuffle<T>(items: readonly T[], rng: Rng): T[] {
  const a = items.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function pick<T>(items: readonly T[], rng: Rng): T {
  return items[Math.floor(rng() * items.length)];
}

/**
 * Builds one random cycle through all members. Tries several shuffles and
 * keeps the one that repeats the fewest of the previous pairings.
 */
export function buildCycle(members: readonly string[], prev: Assignments, rng: Rng): Assignments {
  const unique = [...new Set(members)];
  if (unique.length < MIN_PLAYERS) return {};
  let best: Assignments = {};
  let bestRepeats = Infinity;
  for (let attempt = 0; attempt < 12 && bestRepeats > 0; attempt++) {
    const order = shuffle(unique, rng);
    const candidate: Assignments = {};
    let repeats = 0;
    for (let i = 0; i < order.length; i++) {
      const hunter = order[i];
      const target = order[(i + 1) % order.length];
      candidate[hunter] = target;
      if (prev[hunter] === target) repeats++;
    }
    if (repeats < bestRepeats) {
      best = candidate;
      bestRepeats = repeats;
    }
  }
  return best;
}

/** True when the map is exactly one cycle covering every member. */
export function isSingleCycle(a: Assignments, members: readonly string[]): boolean {
  const set = new Set(members);
  if (set.size === 0 || Object.keys(a).length !== set.size) return false;
  const start = members[0];
  let cur = start;
  const seen = new Set<string>();
  do {
    if (!set.has(cur) || seen.has(cur)) return false;
    seen.add(cur);
    cur = a[cur];
    if (cur === undefined) return false;
  } while (cur !== start);
  return seen.size === set.size;
}

/**
 * Adds a new member. `membersAfter` includes the newcomer.
 * The newcomer is spliced in between a random hunter and that hunter's
 * target, so only one existing player's target changes.
 */
export function insertMember(
  current: Assignments,
  membersAfter: readonly string[],
  newUid: string,
  rng: Rng,
): Assignments {
  if (membersAfter.length < MIN_PLAYERS) return {};
  const live = prune(current, membersAfter.filter((m) => m !== newUid));
  const hunters = Object.keys(live);
  if (hunters.length === 0) return buildCycle(membersAfter, {}, rng);
  const hunter = pick(hunters, rng);
  const next = { ...live };
  next[newUid] = live[hunter];
  next[hunter] = newUid;
  return next;
}

/**
 * Removes a member. `membersAfter` excludes them. Whoever was hunting the
 * leaver inherits the leaver's target, which stitches the cycle closed.
 */
export function removeMember(
  current: Assignments,
  membersAfter: readonly string[],
  leftUid: string,
  rng: Rng,
): Assignments {
  if (membersAfter.length < MIN_PLAYERS) return {};
  const inherited = current[leftUid];
  const next: Assignments = {};
  for (const [hunter, target] of Object.entries(prune(current, [...membersAfter, leftUid]))) {
    if (hunter === leftUid) continue;
    if (target !== leftUid) {
      next[hunter] = target;
      continue;
    }
    const usable = inherited !== undefined && inherited !== hunter && membersAfter.includes(inherited);
    const replacement = usable ? inherited : pickNextTarget(membersAfter, hunter, [], rng);
    if (replacement !== null) next[hunter] = replacement;
  }
  return next;
}

/** Drops entries whose hunter or target is no longer a member. */
function prune(a: Assignments, members: readonly string[]): Assignments {
  const set = new Set(members);
  const out: Assignments = {};
  for (const [h, t] of Object.entries(a)) if (set.has(h) && set.has(t) && h !== t) out[h] = t;
  return out;
}

/**
 * A fresh random target for a hunter who just completed a contract.
 * Avoids the `avoid` list (e.g. the player they just sniped) when possible.
 */
export function pickNextTarget(
  members: readonly string[],
  hunter: string,
  avoid: readonly string[],
  rng: Rng,
): string | null {
  const others = members.filter((m) => m !== hunter);
  if (others.length === 0) return null;
  const preferred = others.filter((m) => !avoid.includes(m));
  return pick(preferred.length > 0 ? preferred : others, rng);
}
