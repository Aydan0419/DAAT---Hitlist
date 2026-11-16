import { Transaction } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions/v2";
import { HttpsError } from "firebase-functions/v2/https";
import {
  assignmentRef,
  call,
  db,
  evidenceRef,
  getDoc,
  groupRef,
  locationRef,
  memberRef,
  photoPath,
  reject,
  snipeRef,
  toFix,
} from "./db";
import {
  checkEligibility,
  FRESH_FIX_MS,
  judgeLocation,
  planAward,
  planRemoval,
  SnipeAttempt,
} from "./logic/engine";
import { id, num, optNum } from "./logic/validate";
import { sendToUsers } from "./notify";
import { AssignmentDoc, GroupDoc, LocationFix, MemberDoc, SnipeDoc } from "./types";

const LOCATE_WAIT_MS = 20_000;

async function deletePhoto(path: string): Promise<void> {
  try {
    await getStorage().bucket().file(path).delete({ ignoreNotFound: true });
  } catch (e) {
    logger.warn("photo delete failed", { path, error: String(e) });
  }
}

/**
 * The target's current location. If the stored fix is stale, their phone is
 * asked for a new one with a silent push and we wait briefly for it to land.
 */
async function freshTargetFix(targetUid: string, groupId: string): Promise<LocationFix | null> {
  const ref = locationRef(targetUid);
  const stored = toFix((await ref.get()).data());
  const isFresh = (f: LocationFix | null) => f !== null && Date.now() - f.updatedAt <= FRESH_FIX_MS;
  if (isFresh(stored)) return stored;

  await sendToUsers([targetUid], { type: "locate", groupId });
  return new Promise((resolve) => {
    let latest = stored;
    const finish = () => {
      clearTimeout(timer);
      unsubscribe();
      resolve(latest);
    };
    const timer = setTimeout(finish, LOCATE_WAIT_MS);
    const unsubscribe = ref.onSnapshot(
      (snap) => {
        latest = toFix(snap.data()) ?? latest;
        if (isFresh(latest)) finish();
      },
      () => finish(),
    );
  });
}

/**
 * Applies a confirmed snipe inside a transaction: points and streak for the
 * hunter, streak reset and immunity for the target, and the hunter's next target.
 * All reads must already be done; this only writes.
 */
function applyAward(
  tx: Transaction,
  args: {
    group: GroupDoc;
    hunter: MemberDoc;
    target: MemberDoc | null;
    assignment: AssignmentDoc | null;
    snipe: Pick<SnipeDoc, "id" | "targetUid" | "assignedAt" | "capturedAt" | "distanceM">;
    /** Rotate the hunter's target only if their assignment still points at this snipe's target. */
    rotate: boolean;
    now: number;
  },
) {
  const { group, hunter, target, snipe, now } = args;
  const gid = group.id;
  const plan = planAward({
    group,
    hunter,
    target,
    distanceM: snipe.distanceM,
    assignedAt: snipe.assignedAt,
    capturedAt: snipe.capturedAt,
    now,
    rng: Math.random,
  });
  tx.update(memberRef(gid, hunter.uid), plan.hunter);
  if (target && plan.target) tx.update(memberRef(gid, target.uid), plan.target);
  if (args.rotate) {
    if (plan.nextTargetUid) {
      const next: AssignmentDoc = { targetUid: plan.nextTargetUid, assignedAt: now, pendingSnipeId: null };
      tx.set(assignmentRef(gid, hunter.uid), next);
    } else {
      tx.delete(assignmentRef(gid, hunter.uid));
    }
  }
  return plan;
}

export const submitSnipe = call({ timeoutSeconds: 60, memory: "512MiB" }, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const sid = id(data.snipeId, "Snipe");
  const attempt: SnipeAttempt = {
    hunterUid: uid,
    targetUid: id(data.targetUid, "Target"),
    lat: num(data.lat, "Latitude", -90, 90),
    lon: num(data.lon, "Longitude", -180, 180),
    accuracyM: optNum(data.accuracy, "Accuracy", 0, 100_000),
    headingDeg: num(data.heading, "Heading", 0, 360),
    capturedAt: num(data.capturedAt, "Capture time", 0, Number.MAX_SAFE_INTEGER),
  };
  const path = photoPath(gid, uid, sid);

  try {
    // Cheap checks first, so an attempt that can't count never wakes the target's phone.
    const [groupSnap, hunterSnap, targetSnap, assignSnap, existing] = await db.getAll(
      groupRef(gid),
      memberRef(gid, uid),
      memberRef(gid, attempt.targetUid),
      assignmentRef(gid, uid),
      snipeRef(gid, sid),
    );
    if (existing.exists) {
      const s = existing.data() as SnipeDoc;
      if (s.hunterUid !== uid) reject("NOT_A_MEMBER");
      return { status: s.status, points: s.points, breakdown: s.breakdown };
    }
    if (!groupSnap.exists) reject("GROUP_NOT_FOUND");
    const pre = checkEligibility({
      group: groupSnap.data() as GroupDoc,
      hunter: (hunterSnap.data() as MemberDoc) ?? null,
      target: (targetSnap.data() as MemberDoc) ?? null,
      assignment: (assignSnap.data() as AssignmentDoc) ?? null,
      attempt,
      snipeId: sid,
      now: Date.now(),
    });
    if (pre) reject(pre);
    const [photoExists] = await getStorage().bucket().file(path).exists();
    if (!photoExists) reject("PHOTO_MISSING");

    const targetFix = await freshTargetFix(attempt.targetUid, gid);
    const now = Date.now();
    const verdict = judgeLocation({ group: groupSnap.data() as GroupDoc, attempt, targetFix, now });
    if (verdict.outcome === "REJECT") {
      reject(verdict.code, {
        distanceM: verdict.evidence.distanceM,
        aimErrorDeg: verdict.evidence.aimErrorDeg,
      });
    }

    const result = await db.runTransaction(async (tx) => {
      const group = await getDoc<GroupDoc>(tx, groupRef(gid));
      const hunter = await getDoc<MemberDoc>(tx, memberRef(gid, uid));
      const target = await getDoc<MemberDoc>(tx, memberRef(gid, attempt.targetUid));
      const assignment = await getDoc<AssignmentDoc>(tx, assignmentRef(gid, uid));
      if ((await tx.get(snipeRef(gid, sid))).exists) return reject("DUPLICATE");
      if (!group) return reject("GROUP_NOT_FOUND");
      // Re-check on fresh data: another snipe may have landed while we waited.
      const again = checkEligibility({ group, hunter, target, assignment, attempt, snipeId: sid, now });
      if (again) return reject(again);

      const snipe: SnipeDoc = {
        id: sid,
        groupId: gid,
        hunterUid: uid,
        targetUid: attempt.targetUid,
        hunterName: hunter!.name,
        targetName: target!.name,
        photoPath: path,
        status: verdict.outcome,
        points: 0,
        breakdown: null,
        distanceM: verdict.evidence.distanceM,
        assignedAt: assignment!.assignedAt,
        capturedAt: attempt.capturedAt,
        createdAt: now,
        resolvedAt: null,
        likedBy: [],
        weekId: null,
      };
      if (verdict.outcome === "VERIFIED") {
        const plan = applyAward(tx, { group, hunter: hunter!, target, assignment, snipe, rotate: true, now });
        snipe.points = plan.breakdown.total;
        snipe.breakdown = plan.breakdown;
        snipe.resolvedAt = now;
        snipe.weekId = group.weekId;
      } else {
        tx.update(assignmentRef(gid, uid), { pendingSnipeId: sid });
      }
      tx.set(snipeRef(gid, sid), snipe);
      tx.set(evidenceRef(gid, sid), verdict.evidence);
      return snipe;
    });

    if (result.status === "VERIFIED") {
      await sendToUsers([result.targetUid], {
        type: "sniped",
        title: "You got sniped!",
        body: `${result.hunterName} caught you. Your streak is reset.`,
        groupId: gid,
        snipeId: sid,
      });
    } else {
      await sendToUsers([result.targetUid], {
        type: "snipe_pending",
        title: "Did you get sniped?",
        body: `${result.hunterName} says they caught you. Confirm or dispute it.`,
        groupId: gid,
        snipeId: sid,
      });
    }
    return { status: result.status, points: result.points, breakdown: result.breakdown };
  } catch (e) {
    // A rejected attempt leaves no snipe behind, so its photo goes too.
    const rejected = e instanceof HttpsError && e.code === "failed-precondition";
    if (rejected && e.message !== "DUPLICATE") await deletePhoto(path);
    throw e;
  }
});

type Decision = "VERIFY" | "REJECT" | "DISPUTE";

/**
 * Moves a PENDING or DISPUTED snipe to its next state. `authorize` runs inside
 * the transaction and decides whether the caller may make this decision.
 */
async function decideSnipe(
  gid: string,
  sid: string,
  decision: Decision,
  authorize: (snipe: SnipeDoc, group: GroupDoc) => void,
): Promise<{ snipe: SnipeDoc; group: GroupDoc; status: SnipeDoc["status"] } | null> {
  const now = Date.now();
  return db.runTransaction(async (tx) => {
    const snipe = await getDoc<SnipeDoc>(tx, snipeRef(gid, sid));
    const group = await getDoc<GroupDoc>(tx, groupRef(gid));
    if (!snipe || !group) return reject("SNIPE_NOT_FOUND");
    authorize(snipe, group);
    const hunter = await getDoc<MemberDoc>(tx, memberRef(gid, snipe.hunterUid));
    const target = await getDoc<MemberDoc>(tx, memberRef(gid, snipe.targetUid));
    const assignment = await getDoc<AssignmentDoc>(tx, assignmentRef(gid, snipe.hunterUid));
    const holdsContract = assignment?.pendingSnipeId === sid;

    if (decision === "DISPUTE") {
      tx.update(snipeRef(gid, sid), { status: "DISPUTED" });
      return { snipe, group, status: "DISPUTED" as const };
    }
    // A hunter who has left the group can't be credited.
    if (decision === "REJECT" || !hunter) {
      tx.update(snipeRef(gid, sid), { status: "REJECTED", resolvedAt: now });
      if (holdsContract) tx.update(assignmentRef(gid, snipe.hunterUid), { pendingSnipeId: null });
      return { snipe, group, status: "REJECTED" as const };
    }
    const plan = applyAward(tx, { group, hunter, target, assignment, snipe, rotate: holdsContract, now });
    tx.update(snipeRef(gid, sid), {
      status: "VERIFIED",
      points: plan.breakdown.total,
      breakdown: plan.breakdown,
      resolvedAt: now,
      weekId: group.weekId,
    });
    return { snipe, group, status: "VERIFIED" as const };
  });
}

async function announce(res: { snipe: SnipeDoc; group: GroupDoc; status: SnipeDoc["status"] }) {
  const { snipe, group, status } = res;
  const ids = { groupId: snipe.groupId, snipeId: snipe.id };
  if (status === "VERIFIED") {
    await sendToUsers([snipe.hunterUid], {
      type: "snipe_verified",
      title: "Snipe confirmed",
      body: `Your snipe on ${snipe.targetName} counts.`,
      ...ids,
    });
  } else if (status === "REJECTED") {
    await sendToUsers([snipe.hunterUid], {
      type: "snipe_rejected",
      title: "Snipe rejected",
      body: `Your snipe on ${snipe.targetName} didn't count.`,
      ...ids,
    });
  } else if (status === "DISPUTED") {
    await sendToUsers([group.adminId, snipe.hunterUid], {
      type: "dispute",
      title: "Snipe disputed",
      body: `${snipe.targetName} disputed a snipe by ${snipe.hunterName}.`,
      ...ids,
    });
  }
}

/** The target confirms or disputes a snipe the server couldn't verify by location. */
export const respondToSnipe = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const sid = id(data.snipeId, "Snipe");
  const res = await decideSnipe(gid, sid, data.accept === true ? "VERIFY" : "DISPUTE", (snipe) => {
    if (snipe.targetUid !== uid) reject("NOT_YOUR_SNIPE");
    if (snipe.status !== "PENDING") reject("ALREADY_RESOLVED");
  });
  if (res) await announce(res);
  return { status: res?.status };
});

/** The group admin settles a disputed snipe. */
export const resolveDispute = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const sid = id(data.snipeId, "Snipe");
  const res = await decideSnipe(gid, sid, data.approve === true ? "VERIFY" : "REJECT", (snipe, group) => {
    if (group.adminId !== uid) reject("NOT_ADMIN");
    if (snipe.status !== "DISPUTED") reject("ALREADY_RESOLVED");
  });
  if (res) {
    await announce(res);
    if (res.status === "REJECTED") await deletePhoto(res.snipe.photoPath);
  }
  return { status: res?.status };
});

/** A pending snipe the target ignored for a day is counted. */
export async function autoConfirm(gid: string, sid: string): Promise<void> {
  const res = await decideSnipe(gid, sid, "VERIFY", (snipe) => {
    if (snipe.status !== "PENDING") reject("ALREADY_RESOLVED");
  });
  if (res) await announce(res);
}

/** The admin takes a snipe down (bad photo, cheating). Points for it are returned. */
export const removeSnipe = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const sid = id(data.snipeId, "Snipe");
  const path = await db.runTransaction(async (tx) => {
    const snipe = await getDoc<SnipeDoc>(tx, snipeRef(gid, sid));
    const group = await getDoc<GroupDoc>(tx, groupRef(gid));
    if (!snipe || !group) return reject("SNIPE_NOT_FOUND");
    if (group.adminId !== uid) return reject("NOT_ADMIN");
    if (snipe.status === "REMOVED") return snipe.photoPath;
    const hunter = await getDoc<MemberDoc>(tx, memberRef(gid, snipe.hunterUid));
    const assignment = await getDoc<AssignmentDoc>(tx, assignmentRef(gid, snipe.hunterUid));
    if (snipe.status === "VERIFIED" && hunter) {
      tx.update(memberRef(gid, hunter.uid), planRemoval(hunter, snipe.points, snipe.weekId));
    }
    if (assignment?.pendingSnipeId === sid) {
      tx.update(assignmentRef(gid, snipe.hunterUid), { pendingSnipeId: null });
    }
    tx.update(snipeRef(gid, sid), { status: "REMOVED", resolvedAt: Date.now(), photoPath: "" });
    return snipe.photoPath;
  });
  if (path) await deletePhoto(path);
  return { ok: true };
});
