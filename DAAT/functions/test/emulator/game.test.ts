/**
 * End-to-end game flow against the Firestore, Storage and Auth emulators.
 * Run with `npm run test:emulator`.
 */
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { beforeAll, describe, expect, it } from "vitest";
import { reshuffleGroup } from "../../src/assignments";
import { assignmentRef, assignmentsRef, db, groupRef, locationRef, memberRef, photoPath, snipeRef, userRef } from "../../src/db";
import * as fn from "../../src/index";
import { isSingleCycle } from "../../src/logic/cycle";
import { GroupDoc, MemberDoc, SnipeDoc } from "../../src/types";

const M = 1 / 111_195;
const here = { lat: 41.8781, lon: -87.6298 };
const players = ["u1", "u2", "u3", "u4"];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const run = (f: any, uid: string, data: Record<string, unknown> = {}) =>
  f.run({ auth: { uid, token: {} }, data, rawRequest: {}, acceptsStreaming: false });

const code = async (p: Promise<unknown>) => {
  try {
    await p;
    return "OK";
  } catch (e) {
    return (e as Error).message;
  }
};

const assignments = async (gid: string) => {
  const snap = await assignmentsRef(gid).get();
  return Object.fromEntries(snap.docs.map((d) => [d.id, d.get("targetUid") as string]));
};
const member = async (gid: string, uid: string) => (await memberRef(gid, uid).get()).data() as MemberDoc;

const setLocation = (uid: string, north: number, ageMs = 0) =>
  locationRef(uid).set({
    lat: here.lat + north * M,
    lon: here.lon,
    accuracy: 5,
    updatedAt: Timestamp.fromMillis(Date.now() - ageMs),
  });

const upload = (gid: string, uid: string, sid: string) =>
  getStorage().bucket().file(photoPath(gid, uid, sid)).save(Buffer.from("jpeg"), { contentType: "image/jpeg" });

const snipe = (gid: string, hunter: string, target: string, sid: string, over: Record<string, unknown> = {}) =>
  run(fn.submitSnipe, hunter, {
    groupId: gid, snipeId: sid, targetUid: target, ...here, accuracy: 5, heading: 0, capturedAt: Date.now(), ...over,
  });

let gid = "";

beforeAll(async () => {
  for (const uid of players) {
    await getAuth().createUser({ uid }).catch(() => undefined);
    await run(fn.completeRegistration, uid, { name: `Player ${uid}`, username: `player_${uid}`, ageConfirmed: true });
  }
});

describe("registration", () => {
  it("creates a profile and reserves the username", async () => {
    expect((await userRef("u1").get()).get("username")).toBe("player_u1");
    await getAuth().createUser({ uid: "dupe" }).catch(() => undefined);
    expect(await code(run(fn.completeRegistration, "dupe", { name: "x", username: "PLAYER_U1", ageConfirmed: true }))).toBe("USERNAME_TAKEN");
    expect(await code(run(fn.completeRegistration, "dupe", { name: "x", username: "fresh_name" }))).toBe("AGE_NOT_CONFIRMED");
  });
});

describe("groups and targets", () => {
  it("hands out targets only once three players have joined", async () => {
    const created = await run(fn.createGroup, "u1", { name: "Test Group", timezone: "America/Chicago" });
    gid = created.groupId;
    await run(fn.joinGroup, "u2", { inviteCode: created.inviteCode });
    expect(await assignments(gid)).toEqual({});
    await run(fn.joinGroup, "u3", { inviteCode: created.inviteCode.toLowerCase() });
    expect(isSingleCycle(await assignments(gid), ["u1", "u2", "u3"])).toBe(true);
    await run(fn.joinGroup, "u4", { inviteCode: created.inviteCode });
    expect(isSingleCycle(await assignments(gid), players)).toBe(true);
  });

  it("rejects a bad invite code and ignores a repeat join", async () => {
    expect(await code(run(fn.joinGroup, "u1", { inviteCode: "ZZZZZZ" }))).toBe("INVALID_CODE");
    const group = (await groupRef(gid).get()).data() as GroupDoc;
    await run(fn.joinGroup, "u2", { inviteCode: group.inviteCode });
    expect(((await groupRef(gid).get()).data() as GroupDoc).memberIds).toHaveLength(4);
  });

  it("only the admin can change settings", async () => {
    expect(await code(run(fn.updateGroup, "u2", { groupId: gid, settings: { immunityMinutes: 5 } }))).toBe("NOT_ADMIN");
    await run(fn.updateGroup, "u1", { groupId: gid, settings: { immunityMinutes: 30, maxSnipesPerDay: 3 } });
    expect(((await groupRef(gid).get()).data() as GroupDoc).settings.immunityMinutes).toBe(30);
  });
});

describe("sniping", () => {
  it("rejects a snipe on someone who isn't your target", async () => {
    const target = (await assignments(gid)).u1;
    const wrong = players.find((p) => p !== "u1" && p !== target)!;
    await upload(gid, "u1", "wrong");
    expect(await code(snipe(gid, "u1", wrong, "wrong"))).toBe("NOT_YOUR_TARGET");
  });

  it("rejects a snipe with no photo", async () => {
    const target = (await assignments(gid)).u1;
    await setLocation(target, 20);
    expect(await code(snipe(gid, "u1", target, "nophoto"))).toBe("PHOTO_MISSING");
  });

  it("rejects a target who is too far away and deletes the photo", async () => {
    const target = (await assignments(gid)).u1;
    await setLocation(target, 2000);
    await upload(gid, "u1", "far");
    expect(await code(snipe(gid, "u1", target, "far"))).toBe("TOO_FAR");
    const [exists] = await getStorage().bucket().file(photoPath(gid, "u1", "far")).exists();
    expect(exists).toBe(false);
    expect((await member(gid, "u1")).score).toBe(0);
  });

  it("verifies a good snipe: points, streak, immunity and a new target", async () => {
    const target = (await assignments(gid)).u1;
    await setLocation(target, 20);
    await upload(gid, "u1", "good");
    const res = await snipe(gid, "u1", target, "good");
    expect(res.status).toBe("VERIFIED");
    expect(res.points).toBe(175); // base 100 + speed 75

    const hunter = await member(gid, "u1");
    expect(hunter).toMatchObject({ score: 175, weeklyScore: 175, streak: 1, snipes: 1, snipesToday: 1 });
    const hit = await member(gid, target);
    expect(hit.timesSniped).toBe(1);
    expect(hit.immuneUntil).toBeGreaterThan(Date.now());

    const next = (await assignments(gid)).u1;
    expect(next).toBeDefined();
    expect(next).not.toBe("u1");
    expect(next).not.toBe(target);
    const evidence = await snipeRef(gid, "good").collection("private").doc("evidence").get();
    expect(evidence.get("distanceM")).toBeCloseTo(20, 0);
  });

  it("returns the same result if the same snipe is submitted twice", async () => {
    const res = await snipe(gid, "u1", "anyone", "good");
    expect(res).toMatchObject({ status: "VERIFIED", points: 175 });
    expect((await member(gid, "u1")).score).toBe(175);
  });

  it("protects an immune player", async () => {
    const immune = (await Promise.all(players.map(async (p) => ({ p, m: await member(gid, p) }))))
      .find((x) => x.m.immuneUntil > Date.now())!.p;
    const hunter = players.find((p) => p !== immune && p !== "u1")!;
    await assignmentRef(gid, hunter).set({ targetUid: immune, assignedAt: Date.now(), pendingSnipeId: null });
    await setLocation(immune, 20);
    await upload(gid, hunter, "immune");
    expect(await code(snipe(gid, hunter, immune, "immune"))).toBe("TARGET_IMMUNE");
  });
});

describe("pending snipes and disputes", () => {
  it("goes pending when the target's location is stale, then the admin settles a dispute", async () => {
    // Clear immunity so the flow isn't blocked by the earlier snipe.
    for (const p of players) await memberRef(gid, p).update({ immuneUntil: 0 });
    const target = (await assignments(gid)).u2;
    await setLocation(target, 20, 30 * 60_000);
    await upload(gid, "u2", "pend");
    const res = await snipe(gid, "u2", target, "pend");
    expect(res.status).toBe("PENDING");
    expect((await member(gid, "u2")).score).toBe(0);

    await upload(gid, "u2", "second");
    expect(await code(snipe(gid, "u2", target, "second"))).toBe("SNIPE_PENDING");

    expect(await code(run(fn.respondToSnipe, "u2", { groupId: gid, snipeId: "pend", accept: true }))).toBe("NOT_YOUR_SNIPE");
    await run(fn.respondToSnipe, target, { groupId: gid, snipeId: "pend", accept: false });
    expect(((await snipeRef(gid, "pend").get()).data() as SnipeDoc).status).toBe("DISPUTED");

    expect(await code(run(fn.resolveDispute, "u3", { groupId: gid, snipeId: "pend", approve: true }))).toBe("NOT_ADMIN");
    await run(fn.resolveDispute, "u1", { groupId: gid, snipeId: "pend", approve: true });
    const final = (await snipeRef(gid, "pend").get()).data() as SnipeDoc;
    expect(final.status).toBe("VERIFIED");
    expect(final.points).toBeGreaterThanOrEqual(100);
    expect((await member(gid, "u2")).score).toBe(final.points);
  });

  it("lets the admin remove a snipe and takes the points back", async () => {
    const before = (await member(gid, "u1")).score;
    await run(fn.removeSnipe, "u1", { groupId: gid, snipeId: "good" });
    expect((await member(gid, "u1")).score).toBe(before - 175);
    expect(((await snipeRef(gid, "good").get()).data() as SnipeDoc).status).toBe("REMOVED");
  });
});

describe("daily reshuffle and leaving", () => {
  it("restores a full cycle at the reshuffle", async () => {
    await reshuffleGroup(gid, Date.now(), true);
    expect(isSingleCycle(await assignments(gid), players)).toBe(true);
    const group = (await groupRef(gid).get()).data() as GroupDoc;
    expect(group.nextReshuffleAt).toBeGreaterThan(Date.now());
  });

  it("repairs the cycle when a player leaves, and passes on admin", async () => {
    await run(fn.leaveGroup, "u1", { groupId: gid });
    const rest = ["u2", "u3", "u4"];
    expect(isSingleCycle(await assignments(gid), rest)).toBe(true);
    const group = (await groupRef(gid).get()).data() as GroupDoc;
    expect(group.memberIds).toEqual(rest);
    expect(group.adminId).toBe("u2");
    expect((await memberRef(gid, "u1").get()).exists).toBe(false);
  });

  it("stops the game below three players", async () => {
    await run(fn.kickMember, "u2", { groupId: gid, memberUid: "u4" });
    expect(await assignments(gid)).toEqual({});
  });

  it("deleting an account removes the player everywhere", async () => {
    await setLocation("u3", 0);
    await run(fn.deleteAccount, "u3");
    expect((await userRef("u3").get()).exists).toBe(false);
    expect((await locationRef("u3").get()).exists).toBe(false);
    expect(((await groupRef(gid).get()).data() as GroupDoc).memberIds).toEqual(["u2"]);
    await expect(getAuth().getUser("u3")).rejects.toBeTruthy();
  });

  it("deletes the group when the last player leaves", async () => {
    await run(fn.leaveGroup, "u2", { groupId: gid });
    expect((await groupRef(gid).get()).exists).toBe(false);
    expect((await db.collection("inviteCodes").get()).size).toBe(0);
  });
});
