import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions/v2";
import { reshuffleGroup } from "./assignments";
import { db, groups } from "./db";
import { autoConfirm } from "./snipes";

const PENDING_TIMEOUT_MS = 24 * 60 * 60 * 1000;

/**
 * Runs every 15 minutes. Reshuffles any group whose daily reshuffle time has
 * passed (each group has its own timezone and hour), and counts pending
 * snipes that the target has ignored for a day.
 */
export const tick = onSchedule({ schedule: "every 15 minutes", timeoutSeconds: 300 }, async () => {
  const now = Date.now();

  const due = await groups().where("nextReshuffleAt", "<=", now).get();
  for (const g of due.docs) {
    try {
      await reshuffleGroup(g.id, now);
    } catch (e) {
      logger.error("reshuffle failed", { groupId: g.id, error: String(e) });
    }
  }

  const stale = await db
    .collectionGroup("snipes")
    .where("status", "==", "PENDING")
    .where("createdAt", "<", now - PENDING_TIMEOUT_MS)
    .limit(200)
    .get();
  for (const s of stale.docs) {
    try {
      await autoConfirm(s.get("groupId") as string, s.id);
    } catch (e) {
      logger.error("auto-confirm failed", { snipeId: s.id, error: String(e) });
    }
  }
});
