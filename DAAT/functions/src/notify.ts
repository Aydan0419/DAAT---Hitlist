import { getMessaging, MulticastMessage } from "firebase-admin/messaging";
import { logger } from "firebase-functions/v2";
import { tokensRef } from "./db";

export type PushType =
  | "new_target"
  | "sniped"
  | "snipe_pending"
  | "snipe_verified"
  | "snipe_rejected"
  | "dispute"
  | "locate";

export interface Push {
  type: PushType;
  title?: string;
  body?: string;
  groupId?: string;
  snipeId?: string;
}

const DEAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

/**
 * Sends a push to every device of each user. A push without a title is
 * delivered silently as data only (used to ask a phone for a fresh location).
 * Never throws: a failed notification must not fail the game action.
 */
export async function sendToUsers(uids: string[], push: Push): Promise<void> {
  try {
    const unique = [...new Set(uids)];
    const snaps = await Promise.all(unique.map((uid) => tokensRef(uid).get()));
    const refs = snaps.flatMap((s) => s.docs.map((d) => d.ref));
    if (refs.length === 0) return;

    const data: Record<string, string> = { type: push.type };
    if (push.groupId) data.groupId = push.groupId;
    if (push.snipeId) data.snipeId = push.snipeId;
    const silent = !push.title;

    const message: MulticastMessage = {
      tokens: refs.map((r) => r.id),
      data,
      android: { priority: "high" },
      apns: silent
        ? {
            headers: { "apns-priority": "5", "apns-push-type": "background" },
            payload: { aps: { contentAvailable: true } },
          }
        : { payload: { aps: { sound: "default" } } },
    };
    if (!silent) message.notification = { title: push.title, body: push.body };

    const res = await getMessaging().sendEachForMulticast(message);
    const dead = res.responses
      .map((r, i) => (!r.success && r.error && DEAD_TOKEN.has(r.error.code) ? refs[i] : null))
      .filter((r): r is FirebaseFirestore.DocumentReference => r !== null);
    await Promise.all(dead.map((r) => r.delete()));
  } catch (e) {
    logger.warn("push failed", { type: push.type, error: String(e) });
  }
}
