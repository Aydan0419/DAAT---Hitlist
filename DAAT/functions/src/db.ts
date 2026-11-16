import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { CallableOptions, HttpsError, onCall } from "firebase-functions/v2/https";
import { ValidationError } from "./logic/validate";
import { LocationFix } from "./types";

initializeApp();
export const db = getFirestore();
db.settings({ ignoreUndefinedProperties: true });

export const users = () => db.collection("users");
export const userRef = (uid: string) => users().doc(uid);
export const usernameRef = (name: string) => db.collection("usernames").doc(name);
export const locationRef = (uid: string) => db.collection("locations").doc(uid);
export const tokensRef = (uid: string) => db.collection("devices").doc(uid).collection("tokens");
export const inviteRef = (code: string) => db.collection("inviteCodes").doc(code);
export const groups = () => db.collection("groups");
export const groupRef = (gid: string) => groups().doc(gid);
export const memberRef = (gid: string, uid: string) => groupRef(gid).collection("members").doc(uid);
export const assignmentsRef = (gid: string) => groupRef(gid).collection("assignments");
export const assignmentRef = (gid: string, uid: string) => assignmentsRef(gid).doc(uid);
export const snipesRef = (gid: string) => groupRef(gid).collection("snipes");
export const snipeRef = (gid: string, sid: string) => snipesRef(gid).doc(sid);
export const evidenceRef = (gid: string, sid: string) =>
  snipeRef(gid, sid).collection("private").doc("evidence");

export const photoPath = (gid: string, hunterUid: string, sid: string) =>
  `snipes/${gid}/${hunterUid}/${sid}.jpg`;

/** Throws a game-rule rejection. The client switches on `message`, which is a stable code. */
export function reject(code: string, details: Record<string, unknown> = {}): never {
  throw new HttpsError("failed-precondition", code, { code, ...details });
}

export type Data = Record<string, unknown>;

/** An authenticated callable with input-validation errors mapped to `invalid-argument`. */
export function call<T>(opts: CallableOptions, handler: (uid: string, data: Data) => Promise<T>) {
  return onCall(opts, async (req) => {
    if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
    try {
      return await handler(req.auth.uid, (req.data ?? {}) as Data);
    } catch (e) {
      if (e instanceof ValidationError) throw new HttpsError("invalid-argument", e.message);
      throw e;
    }
  });
}

export function toFix(data: FirebaseFirestore.DocumentData | undefined): LocationFix | null {
  if (!data || typeof data.lat !== "number" || typeof data.lon !== "number") return null;
  const at = data.updatedAt;
  const updatedAt = at instanceof Timestamp ? at.toMillis() : typeof at === "number" ? at : 0;
  return {
    lat: data.lat,
    lon: data.lon,
    accuracyM: typeof data.accuracy === "number" ? data.accuracy : -1,
    updatedAt,
  };
}

export async function getDoc<T>(tx: Transaction, ref: FirebaseFirestore.DocumentReference): Promise<T | null> {
  const snap = await tx.get(ref);
  return snap.exists ? (snap.data() as T) : null;
}
