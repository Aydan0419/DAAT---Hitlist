import { Transaction } from "firebase-admin/firestore";
import { assignmentRef, assignmentsRef, db, getDoc, groupRef } from "./db";
import { Assignments, buildCycle } from "./logic/cycle";
import { dayId, nextLocalHour, weekId } from "./logic/time";
import { sendToUsers } from "./notify";
import { AssignmentDoc, GroupDoc } from "./types";

export async function readAssignments(tx: Transaction, gid: string): Promise<Map<string, AssignmentDoc>> {
  const snap = await tx.get(assignmentsRef(gid));
  return new Map(snap.docs.map((d) => [d.id, d.data() as AssignmentDoc]));
}

export function toMap(current: Map<string, AssignmentDoc>): Assignments {
  const out: Assignments = {};
  for (const [hunter, a] of current) out[hunter] = a.targetUid;
  return out;
}

/**
 * Writes the difference between the stored assignments and `next`.
 * Returns the hunters whose target changed, so they can be notified.
 * With `resetAll`, every assignment is rewritten (a new day starts the clock again).
 */
export function writeAssignments(
  tx: Transaction,
  gid: string,
  current: Map<string, AssignmentDoc>,
  next: Assignments,
  now: number,
  resetAll = false,
): string[] {
  const changed: string[] = [];
  for (const hunter of current.keys()) {
    if (!(hunter in next)) tx.delete(assignmentRef(gid, hunter));
  }
  for (const [hunter, targetUid] of Object.entries(next)) {
    if (!resetAll && current.get(hunter)?.targetUid === targetUid) continue;
    const doc: AssignmentDoc = { targetUid, assignedAt: now, pendingSnipeId: null };
    tx.set(assignmentRef(gid, hunter), doc);
    changed.push(hunter);
  }
  return changed;
}

/** Starts a new day for a group: a fresh cycle and new day/week ids. */
export async function reshuffleGroup(gid: string, now: number, force = false): Promise<void> {
  const notify = await db.runTransaction(async (tx) => {
    const group = await getDoc<GroupDoc>(tx, groupRef(gid));
    if (!group || (!force && group.nextReshuffleAt > now)) return [];
    const current = await readAssignments(tx, gid);
    const next = buildCycle(group.memberIds, toMap(current), Math.random);
    const changed = writeAssignments(tx, gid, current, next, now, true);
    const tz = group.settings.timezone;
    tx.update(groupRef(gid), {
      dayId: dayId(tz, now),
      weekId: weekId(tz, now),
      nextReshuffleAt: nextLocalHour(tz, group.settings.reshuffleHour, now),
    });
    return changed;
  });
  if (notify.length > 0) {
    await sendToUsers(notify, {
      type: "new_target",
      title: "New target",
      body: "Targets have been reshuffled. See who you're hunting.",
      groupId: gid,
    });
  }
}
