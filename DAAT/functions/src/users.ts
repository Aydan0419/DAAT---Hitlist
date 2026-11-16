import { getAuth } from "firebase-admin/auth";
import { getStorage } from "firebase-admin/storage";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { call, db, getDoc, groups, locationRef, memberRef, reject, snipesRef, tokensRef, userRef, usernameRef } from "./db";
import { removeMemberFromGroup } from "./groups";
import { str, username as parseUsername, ValidationError } from "./logic/validate";
import { UserDoc } from "./types";

/** Creates the public profile and reserves the username. */
export const completeRegistration = onCall(async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const uid = req.auth.uid;
  const data = (req.data ?? {}) as Record<string, unknown>;
  let name: string;
  let handle: string;
  try {
    name = str(data.name, "Name", 1, 40);
    handle = parseUsername(data.username);
  } catch (e) {
    if (e instanceof ValidationError) throw new HttpsError("invalid-argument", e.message);
    throw e;
  }
  if (data.ageConfirmed !== true) reject("AGE_NOT_CONFIRMED");
  const picture = typeof req.auth.token.picture === "string" ? req.auth.token.picture : null;

  await db.runTransaction(async (tx) => {
    if (await getDoc<UserDoc>(tx, userRef(uid))) return reject("ALREADY_REGISTERED");
    if ((await tx.get(usernameRef(handle))).exists) return reject("USERNAME_TAKEN");
    const user: UserDoc = { id: uid, name, username: handle, photoUrl: picture, createdAt: Date.now() };
    tx.set(userRef(uid), user);
    tx.set(usernameRef(handle), { uid });
  });
  return { ok: true };
});

/** Keeps the name and photo shown on leaderboards in step with the profile. */
export const onUserUpdated = onDocumentUpdated("users/{uid}", async (event) => {
  const before = event.data?.before.data() as UserDoc | undefined;
  const after = event.data?.after.data() as UserDoc | undefined;
  if (!before || !after) return;
  if (before.name === after.name && before.photoUrl === after.photoUrl) return;
  const uid = event.params.uid;
  const mine = await groups().where("memberIds", "array-contains", uid).get();
  const batch = db.batch();
  for (const g of mine.docs) {
    batch.update(memberRef(g.id, uid), { name: after.name, photoUrl: after.photoUrl ?? null });
  }
  await batch.commit();
});

async function deleteSnipesInvolving(gid: string, uid: string): Promise<void> {
  const [asHunter, asTarget] = await Promise.all([
    snipesRef(gid).where("hunterUid", "==", uid).get(),
    snipesRef(gid).where("targetUid", "==", uid).get(),
  ]);
  const bucket = getStorage().bucket();
  for (const doc of [...asHunter.docs, ...asTarget.docs]) {
    const path = doc.get("photoPath") as string;
    if (path) await bucket.file(path).delete({ ignoreNotFound: true }).catch(() => undefined);
    const sound = doc.get("soundPath") as string | undefined;
    if (sound) await bucket.file(sound).delete({ ignoreNotFound: true }).catch(() => undefined);
    await db.recursiveDelete(doc.ref);
  }
}

/** Deletes the account and everything tied to it. Required by both app stores. */
export const deleteAccount = call({ timeoutSeconds: 120 }, async (uid) => {
  const mine = await groups().where("memberIds", "array-contains", uid).get();
  for (const g of mine.docs) {
    await deleteSnipesInvolving(g.id, uid);
    await removeMemberFromGroup(g.id, uid);
  }
  const user = (await userRef(uid).get()).data() as UserDoc | undefined;
  if (user) await usernameRef(user.username).delete();
  await db.recursiveDelete(db.collection("devices").doc(uid));
  await db.recursiveDelete(userRef(uid));
  await locationRef(uid).delete();
  try {
    await getStorage().bucket().deleteFiles({ prefix: `avatars/${uid}/` });
  } catch (e) {
    logger.warn("avatar cleanup failed", { uid, error: String(e) });
  }
  await getAuth().deleteUser(uid);
  return { ok: true };
});

/** Drops this device's push token and stored location when signing out. */
export const signOutDevice = call({}, async (uid, data) => {
  if (typeof data.token === "string" && data.token.length > 0 && !data.token.includes("/")) {
    await tokensRef(uid).doc(data.token).delete();
  }
  await locationRef(uid).delete();
  return { ok: true };
});
