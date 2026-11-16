import { assignmentRef, call, db, groupRef, locationRef, memberRef, reject, toFix } from "./db";
import { FRESH_FIX_MS } from "./logic/engine";
import { proximityHint } from "./logic/geo";
import { id, num } from "./logic/validate";
import { sendToUsers } from "./notify";
import { AssignmentDoc, GroupDoc, MemberDoc } from "./types";

/** Beyond this the target's last fix says nothing useful about where they are. */
const HINT_MAX_AGE_MS = 15 * 60 * 1000;
const LOCATE_THROTTLE_MS = 5 * 60 * 1000;

/**
 * A coarse "how close is my target" hint. The server does the maths so the
 * hunter's phone never receives the target's coordinates.
 */
export const getProximityHint = call({}, async (uid, data) => {
  const gid = id(data.groupId, "Group");
  const lat = num(data.lat, "Latitude", -90, 90);
  const lon = num(data.lon, "Longitude", -180, 180);

  const [groupSnap, assignSnap] = await db.getAll(groupRef(gid), assignmentRef(gid, uid));
  const group = groupSnap.data() as GroupDoc | undefined;
  if (!group || !group.memberIds.includes(uid)) reject("NOT_A_MEMBER");
  const assignment = assignSnap.data() as AssignmentDoc | undefined;
  if (!assignment) return { band: "UNKNOWN", direction: null, targetImmuneUntil: 0 };

  const [locSnap, targetSnap] = await db.getAll(
    locationRef(assignment.targetUid),
    memberRef(gid, assignment.targetUid),
  );
  const targetImmuneUntil = (targetSnap.data() as MemberDoc | undefined)?.immuneUntil ?? 0;
  if (!group!.settings.hintEnabled) return { band: "UNKNOWN", direction: null, targetImmuneUntil };

  const now = Date.now();
  const fix = toFix(locSnap.data());
  const age = fix ? now - fix.updatedAt : Infinity;

  // Nudge a quiet phone for a new fix, but not more than once every few minutes.
  const lastAsked = (locSnap.data()?.locateRequestedAt as number | undefined) ?? 0;
  if (age > FRESH_FIX_MS && now - lastAsked > LOCATE_THROTTLE_MS) {
    await locationRef(assignment.targetUid).set({ locateRequestedAt: now }, { merge: true });
    await sendToUsers([assignment.targetUid], { type: "locate", groupId: gid });
  }
  if (!fix || age > HINT_MAX_AGE_MS) return { band: "UNKNOWN", direction: null, targetImmuneUntil };
  return { ...proximityHint(lat, lon, fix.lat, fix.lon), targetImmuneUntil };
});
