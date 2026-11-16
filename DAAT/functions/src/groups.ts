import { Transaction } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions/v2";
import { readAssignments, toMap, writeAssignments } from "./assignments";
import { call, db, getDoc, groupRef, groups, inviteRef, memberRef, reject, userRef } from "./db";
import { insertMember, removeMember } from "./logic/cycle";
import { dayId, isValidTimezone, nextLocalHour, weekId } from "./logic/time";
import { id, mergeSettings, str } from "./logic/validate";
import { sendToUsers } from "./notify";
import { DEFAULT_SETTINGS, GroupDoc, MemberDoc, UserDoc } from "./types";

export const MAX_GROUP_MEMBERS = 50;
export const MAX_GROUPS_PER_USER = 10;
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O or 1/I

export function randomInviteCode(rng: () => number = Math.random): string {
  let code = "";
  for (let i = 0; i < 6; i++) code += CODE_ALPHABET[Math.floor(rng() * CODE_ALPHABET.length)];
  return code;
}

function newMember(user: UserDoc, group: Pick<GroupDoc, "weekId" | "dayId">, now: number): MemberDoc {
  return {
    uid: user.id,
    name: user.name,
    username: user.username,
    photoUrl: user.photoUrl ?? null,
    score: 0,
    weeklyScore: 0,
    weekId: group.weekId,
    streak: 0,
    bestStreak: 0,
    snipes: 0,
    timesSniped: 0,
    snipesToday: 0,
    snipeDayId: group.dayId,
    immuneUntil: 0,
    joinedAt: now,
  };
}

async function groupCount(uid: string): Promise<number> {
  const snap = await groups().where("memberIds", "array-contains", uid).count().get();
  return snap.data().count;
}

async function requireAdmin(tx: Transaction, gid: string, uid: string): Promise<GroupDoc> {
  const group = await getDoc<GroupDoc>(tx, groupRef(gid));
  if (!group) return reject("GROUP_NOT_FOUND");
  if (group.adminId !== uid) return reject("NOT_ADMIN");
  return group;
}

const notifyNewTarget = (uids: string[], gid: string) =>
  uids.length === 0
    ? Promise.resolve()
    : sendToUsers(uids, {
        type: "new_target",
        title: "New target",
        body: "Your target changed. Open Hitlist to see who it is.",
        groupId: gid,
      });

export const createGroup = call({}, async (uid, data) => {
  const name = str(data.name, "Group name", 2, 40);
  const timezone = isValidTimezone(data.timezone) ? data.timezone : DEFAULT_SETTINGS.timezone;
  if ((await groupCount(uid)) >= MAX_GROUPS_PER_USER) reject("TOO_MANY_GROUPS");
  const now = Date.now();
  const ref = groups().doc();

  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomInviteCode();
    const created = await db.runTransaction(async (tx) => {
      const user = await getDoc<UserDoc>(tx, userRef(uid));
      if (!user) return reject("NOT_REGISTERED");
      if ((await tx.get(inviteRef(code))).exists) return false;
      const settings = { ...DEFAULT_SETTINGS, timezone };
      const group: GroupDoc = {
        id: ref.id,
        name,
        adminId: uid,
        inviteCode: code,
        memberIds: [uid],
        settings,
        dayId: dayId(timezone, now),
        weekId: weekId(timezone, now),
        nextReshuffleAt: nextLocalHour(timezone, settings.reshuffleHour, now),
        createdAt: now,
      };
      tx.set(ref, group);
      tx.set(inviteRef(code), { groupId: ref.id });
      tx.set(memberRef(ref.id, uid), newMember(user, group, now));
      return true;
    });
    if (created) return { groupId: ref.id, inviteCode: code };
  }
  throw new Error("Could not allocate an invite code");
});

export const joinGroup = call({}, async (uid, data) => {
  const code = str(data.inviteCode, "Invite code", 6, 6).toUpperCase();
  if ((await groupCount(uid)) >= MAX_GROUPS_PER_USER) reject("TOO_MANY_GROUPS");
  const now = Date.now();

  const result = await db.runTransaction(async (tx) => {
    const invite = await tx.get(inviteRef(code));
    if (!invite.exists) return reject("INVALID_CODE");
    const gid = invite.data()!.groupId as string;
    const group = await getDoc<GroupDoc>(tx, groupRef(gid));
    if (!group) return reject("INVALID_CODE");
    if (group.memberIds.includes(uid)) return { groupId: gid, name: group.name, changed: [] as string[] };
    if (group.memberIds.length >= MAX_GROUP_MEMBERS) return reject("GROUP_FULL");
    const user = await getDoc<UserDoc>(tx, userRef(uid));
    if (!user) return reject("NOT_REGISTERED");
    const current = await readAssignments(tx, gid);

    const members = [...group.memberIds, uid];
    const next = insertMember(toMap(current), members, uid, Math.random);
    const changed = writeAssignments(tx, gid, current, next, now);
    tx.update(groupRef(gid), { memberIds: members });
    tx.set(memberRef(gid, uid), newMember(user, group, now));
    return { groupId: gid, name: group.name, changed };
  });
  await notifyNewTarget(result.changed.filter((h) => h !== uid), result.groupId);
  return { groupId: result.groupId, name: result.name };
});

/** Deletes everything a group owns. Safe to call more than once. */
export async function purgeGroup(gid: string): Promise<void> {
  await db.recursiveDelete(groupRef(gid));
  try {
    await getStorage().bucket().deleteFiles({ prefix: `snipes/${gid}/` });
  } catch (e) {
    logger.warn("photo cleanup failed", { gid, error: String(e) });
  }
}

/**
 * Takes a member out of a group inside a transaction: repairs the target
 * cycle, hands admin to the longest-standing member if the admin left, and
 * marks the group for deletion when it becomes empty.
 */
export async function removeMemberTx(
  tx: Transaction,
  gid: string,
  uid: string,
  now: number,
): Promise<{ emptied: boolean; changed: string[] }> {
  const group = await getDoc<GroupDoc>(tx, groupRef(gid));
  if (!group || !group.memberIds.includes(uid)) return { emptied: false, changed: [] };
  const current = await readAssignments(tx, gid);
  const remaining = group.memberIds.filter((m) => m !== uid);

  if (remaining.length === 0) {
    writeAssignments(tx, gid, current, {}, now);
    tx.delete(memberRef(gid, uid));
    tx.delete(inviteRef(group.inviteCode));
    tx.delete(groupRef(gid));
    return { emptied: true, changed: [] };
  }
  const next = removeMember(toMap(current), remaining, uid, Math.random);
  const changed = writeAssignments(tx, gid, current, next, now);
  tx.delete(memberRef(gid, uid));
  tx.update(groupRef(gid), {
    memberIds: remaining,
    adminId: group.adminId === uid ? remaining[0] : group.adminId,
  });
  return { emptied: false, changed };
}

export async function removeMemberFromGroup(gid: string, uid: string): Promise<void> {
  const res = await db.runTransaction((tx) => removeMemberTx(tx, gid, uid, Date.now()));
  if (res.emptied) await purgeGroup(gid);
  else await notifyNewTarget(res.changed, gid);
}

export const leaveGroup = call({}, async (uid, data) => {
  await removeMemberFromGroup(id(data.groupId, "Group"), uid);
  return { ok: true };
});

export const kickMember = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const target = id(data.memberUid, "Member");
  if (target === uid) reject("CANNOT_KICK_SELF");
  const res = await db.runTransaction(async (tx) => {
    await requireAdmin(tx, gid, uid);
    return removeMemberTx(tx, gid, target, Date.now());
  });
  await notifyNewTarget(res.changed, gid);
  return { ok: true };
});

export const updateGroup = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const group = await requireAdmin(tx, gid, uid);
    const update: Record<string, unknown> = {};
    if (data.name !== undefined) update.name = str(data.name, "Group name", 2, 40);
    if (data.settings !== undefined) {
      const settings = mergeSettings(group.settings, data.settings);
      update.settings = settings;
      const scheduleChanged =
        settings.timezone !== group.settings.timezone ||
        settings.reshuffleHour !== group.settings.reshuffleHour;
      if (scheduleChanged) {
        update.nextReshuffleAt = nextLocalHour(settings.timezone, settings.reshuffleHour, now);
      }
    }
    if (Object.keys(update).length > 0) tx.update(groupRef(gid), update);
  });
  return { ok: true };
});

export const transferAdmin = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const next = id(data.memberUid, "Member");
  await db.runTransaction(async (tx) => {
    const group = await requireAdmin(tx, gid, uid);
    if (!group.memberIds.includes(next)) reject("NOT_A_MEMBER");
    tx.update(groupRef(gid), { adminId: next });
  });
  return { ok: true };
});

export const regenerateInviteCode = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomInviteCode();
    const done = await db.runTransaction(async (tx) => {
      const group = await requireAdmin(tx, gid, uid);
      if ((await tx.get(inviteRef(code))).exists) return false;
      tx.delete(inviteRef(group.inviteCode));
      tx.set(inviteRef(code), { groupId: gid });
      tx.update(groupRef(gid), { inviteCode: code });
      return true;
    });
    if (done) return { inviteCode: code };
  }
  throw new Error("Could not allocate an invite code");
});

export const deleteGroup = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  await db.runTransaction(async (tx) => {
    const group = await requireAdmin(tx, gid, uid);
    tx.delete(inviteRef(group.inviteCode));
    // Emptying the member list first hides the group from every client at once.
    tx.update(groupRef(gid), { memberIds: [] });
  });
  await purgeGroup(gid);
  return { ok: true };
});
