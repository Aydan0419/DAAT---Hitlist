/**
 * Security rules tests. Run with `npm run test:emulator` (needs the Firebase emulators).
 * These prove the spec's privacy and anti-cheat guarantees at the database level.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  arrayRemove,
  arrayUnion,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from "firebase/firestore";

let env: RulesTestEnvironment;
const db = (uid: string) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-hitlist",
    firestore: { rules: readFileSync(resolve(__dirname, "../../../firestore.rules"), "utf8") },
  });
});
afterAll(() => env.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const a = ctx.firestore();
    for (const uid of ["alice", "bob", "carol", "mallory"]) {
      await setDoc(doc(a, "users", uid), { id: uid, name: uid, username: uid, photoUrl: null, createdAt: 1 });
    }
    await setDoc(doc(a, "locations/bob"), { lat: 1, lon: 2, accuracy: 5, updatedAt: new Date() });
    await setDoc(doc(a, "usernames/alice"), { uid: "alice" });
    await setDoc(doc(a, "inviteCodes/ABCDEF"), { groupId: "g1" });
    await setDoc(doc(a, "groups/g1"), {
      id: "g1", name: "Dorm", adminId: "alice", inviteCode: "ABCDEF", memberIds: ["alice", "bob", "carol"],
    });
    for (const uid of ["alice", "bob", "carol"]) {
      await setDoc(doc(a, `groups/g1/members/${uid}`), { uid, score: 100, streak: 0 });
    }
    await setDoc(doc(a, "groups/g1/assignments/alice"), { targetUid: "bob", assignedAt: 1, pendingSnipeId: null });
    await setDoc(doc(a, "groups/g1/assignments/bob"), { targetUid: "carol", assignedAt: 1, pendingSnipeId: null });
    await setDoc(doc(a, "groups/g1/snipes/s1"), {
      id: "s1", hunterUid: "alice", targetUid: "bob", status: "VERIFIED", points: 100, likedBy: [],
    });
    await setDoc(doc(a, "groups/g1/snipes/s1/private/evidence"), { hunterLat: 1, hunterLon: 2 });
  });
});

describe("locations", () => {
  it("nobody can read a location, not even the owner", async () => {
    await assertFails(getDoc(doc(db("alice"), "locations/bob")));
    await assertFails(getDoc(doc(db("bob"), "locations/bob")));
    await assertFails(getDocs(collection(db("alice"), "locations")));
  });

  it("a player can write only their own location, with a server timestamp", async () => {
    const fix = { lat: 41.8, lon: -87.6, accuracy: 8, updatedAt: serverTimestamp() };
    await assertSucceeds(setDoc(doc(db("alice"), "locations/alice"), fix));
    await assertFails(setDoc(doc(db("alice"), "locations/bob"), fix));
    await assertFails(setDoc(doc(db("alice"), "locations/alice"), { ...fix, updatedAt: new Date(0) }));
    await assertFails(setDoc(doc(db("alice"), "locations/alice"), { ...fix, lat: 123 }));
    await assertFails(setDoc(doc(db("alice"), "locations/alice"), { ...fix, extra: true }));
    await assertFails(setDoc(doc(anon(), "locations/alice"), fix));
  });
});

describe("scores and membership", () => {
  it("a player cannot change any score, including their own", async () => {
    await assertFails(updateDoc(doc(db("alice"), "groups/g1/members/alice"), { score: 99999 }));
    await assertFails(updateDoc(doc(db("alice"), "groups/g1/members/bob"), { score: 0 }));
    await assertFails(setDoc(doc(db("mallory"), "groups/g1/members/mallory"), { uid: "mallory", score: 1 }));
  });

  it("members can read the leaderboard; outsiders cannot", async () => {
    await assertSucceeds(getDocs(collection(db("alice"), "groups/g1/members")));
    await assertFails(getDocs(collection(db("mallory"), "groups/g1/members")));
  });

  it("a player cannot edit a group or add themselves to it", async () => {
    await assertFails(updateDoc(doc(db("mallory"), "groups/g1"), { memberIds: arrayUnion("mallory") }));
    await assertFails(updateDoc(doc(db("bob"), "groups/g1"), { adminId: "bob" }));
  });

  it("a player can list only the groups they belong to", async () => {
    const mine = (uid: string) =>
      getDocs(query(collection(db(uid), "groups"), where("memberIds", "array-contains", uid)));
    await assertSucceeds(mine("alice"));
    await assertSucceeds(mine("mallory")); // allowed, simply returns nothing
    await assertFails(getDoc(doc(db("mallory"), "groups/g1")));
    await assertFails(getDocs(collection(db("alice"), "groups")));
  });

  it("invite codes and usernames can't be read or enumerated", async () => {
    await assertFails(getDoc(doc(db("mallory"), "inviteCodes/ABCDEF")));
    await assertFails(getDocs(collection(db("mallory"), "inviteCodes")));
    await assertFails(getDoc(doc(db("alice"), "usernames/alice")));
  });
});

describe("assignments", () => {
  it("a player can read their own target but nobody else's", async () => {
    await assertSucceeds(getDoc(doc(db("alice"), "groups/g1/assignments/alice")));
    await assertFails(getDoc(doc(db("alice"), "groups/g1/assignments/bob")));
    await assertFails(getDocs(collection(db("alice"), "groups/g1/assignments")));
  });

  it("a player cannot pick their own target", async () => {
    await assertFails(updateDoc(doc(db("alice"), "groups/g1/assignments/alice"), { targetUid: "carol" }));
  });
});

describe("snipes", () => {
  it("members can read the feed; outsiders cannot", async () => {
    await assertSucceeds(getDoc(doc(db("carol"), "groups/g1/snipes/s1")));
    await assertFails(getDoc(doc(db("mallory"), "groups/g1/snipes/s1")));
  });

  it("nobody can read verification evidence", async () => {
    await assertFails(getDoc(doc(db("alice"), "groups/g1/snipes/s1/private/evidence")));
    await assertFails(getDoc(doc(db("bob"), "groups/g1/snipes/s1/private/evidence")));
  });

  it("a player cannot create a snipe or award themselves points", async () => {
    await assertFails(
      setDoc(doc(db("alice"), "groups/g1/snipes/fake"), { hunterUid: "alice", status: "VERIFIED", points: 500, likedBy: [] }),
    );
    await assertFails(updateDoc(doc(db("alice"), "groups/g1/snipes/s1"), { points: 5000 }));
    await assertFails(updateDoc(doc(db("bob"), "groups/g1/snipes/s1"), { status: "REJECTED" }));
    await assertFails(deleteDoc(doc(db("bob"), "groups/g1/snipes/s1")));
  });

  it("a member can like and unlike as themselves only", async () => {
    const s = (uid: string) => doc(db(uid), "groups/g1/snipes/s1");
    await assertSucceeds(updateDoc(s("carol"), { likedBy: arrayUnion("carol") }));
    await assertFails(updateDoc(s("carol"), { likedBy: arrayUnion("bob") }));
    await assertFails(updateDoc(s("alice"), { likedBy: arrayRemove("carol") }));
    await assertFails(updateDoc(s("alice"), { likedBy: [] }));
    await assertSucceeds(updateDoc(s("carol"), { likedBy: arrayRemove("carol") }));
    await assertFails(updateDoc(s("mallory"), { likedBy: arrayUnion("mallory") }));
  });
});

describe("profiles", () => {
  it("signed-in players can read profiles; signed-out visitors cannot", async () => {
    await assertSucceeds(getDoc(doc(db("alice"), "users/bob")));
    await assertFails(getDoc(doc(anon(), "users/bob")));
  });

  it("a player can change their own name and photo, nothing else", async () => {
    await assertSucceeds(updateDoc(doc(db("alice"), "users/alice"), { name: "Alice A." }));
    await assertFails(updateDoc(doc(db("alice"), "users/alice"), { username: "admin" }));
    await assertFails(updateDoc(doc(db("alice"), "users/bob"), { name: "hacked" }));
    await assertFails(updateDoc(doc(db("alice"), "users/alice"), { name: "" }));
    await assertFails(setDoc(doc(db("mallory"), "users/someone"), { id: "someone", name: "x", username: "x" }));
  });

  it("push tokens and block lists are private to their owner", async () => {
    await assertSucceeds(setDoc(doc(db("alice"), "devices/alice/tokens/t1"), { platform: "android" }));
    await assertFails(getDocs(collection(db("bob"), "devices/alice/tokens")));
    await assertSucceeds(setDoc(doc(db("alice"), "users/alice/blocked/bob"), { at: 1 }));
    await assertFails(getDocs(collection(db("bob"), "users/alice/blocked")));
  });
});

describe("reports", () => {
  const report = (uid: string) => ({
    reporterUid: uid, snipeId: "s1", reportedUid: "alice", reason: "not me", createdAt: serverTimestamp(),
  });

  it("members can file a report as themselves; only the admin can read them", async () => {
    await assertSucceeds(setDoc(doc(db("bob"), "groups/g1/reports/r1"), report("bob")));
    await assertFails(setDoc(doc(db("bob"), "groups/g1/reports/r2"), report("carol")));
    await assertFails(setDoc(doc(db("mallory"), "groups/g1/reports/r3"), report("mallory")));
    await assertSucceeds(getDocs(collection(db("alice"), "groups/g1/reports")));
    await assertFails(getDocs(collection(db("bob"), "groups/g1/reports")));
  });
});
