import { setGlobalOptions } from "firebase-functions/v2";

setGlobalOptions({ region: "us-central1", maxInstances: 10 });

export { completeRegistration, deleteAccount, onUserUpdated, signOutDevice } from "./users";
export {
  createGroup,
  deleteGroup,
  joinGroup,
  kickMember,
  leaveGroup,
  regenerateInviteCode,
  setSoundMuted,
  transferAdmin,
  updateGroup,
} from "./groups";
export { removeSnipe, resolveDispute, respondToSnipe, submitSnipe } from "./snipes";
export { getProximityHint } from "./location";
export { tick } from "./schedule";
