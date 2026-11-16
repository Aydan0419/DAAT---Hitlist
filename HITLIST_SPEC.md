# Hitlist: Build Spec (v1.0)

> Hand this to the model building the app. It describes what Hitlist is, what's already in this repo, what's broken, and what "done" looks like.

## 1. What Hitlist is

Hitlist is a real-world "snipe" game for friend groups. Every player in a group is assigned one other player as their **target** and is themselves **hunted** by someone else. You "snipe" your target by taking a photo of them in person with the app. The server confirms the snipe using GPS distance and compass direction, and the hunter earns points.

**Locked-in decisions (from the owner):**

| Decision | Choice |
|---|---|
| Game mode | **Points, no elimination.** Nobody is knocked out. Everyone keeps playing and the leaderboard decides who's on top. |
| Format | **Always-on.** No start or end; targets keep rotating. |
| Platforms | **Android + iPhone** from one shared Kotlin codebase. |
| Snipe verification | **Server-side GPS + compass check.** The client never decides whether a snipe counts. |
| Priority | The **most optimized** app possible: fast startup, smooth UI, low battery use, small download. |

## 2. What's in the repo today (starting point)

An Android app at `DAAT/` (Kotlin, Jetpack Compose, Material 3, Firebase Auth/Firestore/Storage/Functions, CameraX, play-services-location). Package `com.example.daat`. About 3,400 lines of Kotlin.

**Exists and is worth keeping (port it, don't throw it away):**
- Models: `User`, `Group`, `Snipe` (`data/model/`).
- `GameRepository` interface, with a `FirebaseGameRepository` and a `FakeGameRepository` for UI work.
- Google sign-in (Credential Manager), anonymous sign-in, a username registration step.
- Groups: create, join by a 6-letter invite code, leave, and admin tools (kick, rename, transfer admin, delete).
- Screens: Sign-in, Home (camera + "tactical compass" pointing at the target), Feed, Leaderboard, Profile/groups.
- `ScoringManager`: base 100, plus 25 per streak level, plus 50 for close range (<5 m), plus 75 for speed (<2 h after assignment).
- `VerificationUtils`: haversine distance, bearing, ±20° aim tolerance, 50 m max distance, 5-minute photo freshness.

**Broken or missing. The current build does not work as a game:**
1. **No targets are ever assigned on Firebase.** `assignDailyTargets` returns "Not implemented. Use Cloud Functions," and no such function exists.
2. **All verification and scoring runs on the phone.** The client computes distance and points, then writes `totalScore` straight into Firestore. Anyone can cheat.
3. **There are no Firestore or Storage security rules** in the repo. Every user's raw latitude/longitude sits on their `users` doc, readable by anyone. `toPublicProfile()` only strips it on the client, which protects nothing.
4. **Target location goes stale.** A player's location only updates when they open the app, so the 50 m check compares against wherever the target was hours ago. If the target has never reported a location, it falls back to `0.0, 0.0`.
5. **One `currentTargetId` per user**, even though users can be in many groups. Targets must be tracked per group.
6. **The feed is global**, so it shows snipes from groups you're not in. `toggleLike` only increments (no unlike, no per-user tracking, `isLikedByMe` is never stored).
7. **Getting sniped has no consequence.** Streaks never reset.
8. Everyone is auto-joined to a `"global"` group, and the leaderboard and target assignment are hardcoded to it.
9. `LocationManager` writes anonymous docs to a `locations` collection with no user ID, duplicating `updateLocation`. `functions/index.js` (`saveLocation`) is unused, and its v1-style `onCall((data, context))` signature probably doesn't match firebase-functions v7.
10. Build hygiene: `isMinifyEnabled = false`, `applicationId = com.example.daat` (must change before store release), and `local.properties`, `.idea/`, `.gradle/`, `.kotlin/` are committed despite `.gitignore`. Duplicate permission lines in `AndroidManifest.xml`.

## 3. Platform & architecture

- **Kotlin Multiplatform + Compose Multiplatform** (iOS has been stable since CMP 1.8). Restructure into:
  - `shared/` holds models, repository interfaces, scoring/verification logic, ViewModels, and **all Compose UI** (aim for ≥90% shared code).
  - `androidApp/` and `iosApp/` are thin platform shells.
  - `expect/actual` (or small interfaces) wrap the platform pieces: camera, location, compass, push notifications, Google/Apple sign-in.
- Firebase on both platforms through a KMP Firebase wrapper (e.g. GitLive `firebase-kotlin-sdk`), or platform SDKs behind the shared repository interface. Pick one and justify it briefly.
- **Backend:** Firebase Cloud Functions (TypeScript, v2 API). The functions are the **only** writers of scores, targets, snipe status and streaks.
- **Sign-in:** Google on both platforms, plus **Sign in with Apple on iOS** (App Store requirement when offering third-party login). Keep anonymous/guest only if it can be upgraded to a real account later.
- Keep `FakeGameRepository` so the UI can run with no backend.
- **iOS shipping requires** a Mac with Xcode and an Apple Developer account ($99/yr). Android Play Store requires a one-time $25 developer account. Set up CI (GitHub Actions; macOS runner for iOS) so builds don't depend on one laptop.

## 4. Game rules (points mode, always-on)

Values marked *(config)* are per-group settings the admin can change, with the default shown.

**Target assignment**
- Each group gets a fresh assignment every day at a set time *(config, default 08:00 group-local)*.
- Assignment is a **single random cycle** (A→B→C→…→A), so every player hunts exactly one person and is hunted by exactly one. Avoid repeating yesterday's pairs where possible.
- Players who join mid-day are slotted into the cycle immediately. Players who leave are removed and the cycle is stitched closed.
- Minimum 3 players for a group to be active.
- Targets are **per group**: a player in 3 groups has 3 targets.

**Sniping**
- The hunter opens the camera, aims at the target, and takes a photo. The app captures GPS (with accuracy radius), compass heading, and the time at the shutter press.
- **The server verifies** (see §5). If valid, the hunter gets points, the snipe is posted to the group feed, and the target gets a push notification ("You got sniped by @x").
- **After a successful snipe:** the hunter's contract is complete. They get a new random target from the group straight away *(config: max successful snipes per day, default 3)*. The cycle is restored at the next daily reshuffle.
- **Being sniped:** the target's streak resets to 0, and they get **immunity** for a period *(config, default 60 min)*, during which they can't be sniped. Optional point penalty *(config, default 0)*.
- Streak = consecutive successful snipes without being sniped. It feeds the existing streak bonus.

**Scoring:** keep the existing `ScoringManager` formula, moved to the server.

**Leaderboards:** per group, all-time plus a weekly board that resets Monday.

**Safe zones** *(strongly recommended)*: the admin can define places/times where sniping doesn't count (classrooms, workplaces, home after 10 pm, etc.). The server rejects snipes inside them.

## 5. Verification (server-side) & location

1. The client uploads the photo to Storage under `snipes/{groupId}/{snipeId}.jpg`. Storage rules only allow the authenticated hunter to write there, ≤2 MB, image only.
2. The client calls the `submitSnipe` callable Function with: groupId, targetId, photo path, hunter lat/lon/accuracy, heading, capturedAt.
3. The Function checks, in order: hunter is in the group, the target really is their current target, the target isn't immune, the photo is fresh (≤5 min), not in a safe zone, the **target's location is fresh**, distance is within range, and aim is within tolerance.
4. **Location freshness is the hard part, so design it carefully:**
   - While the app is open, report location every ~15–30 s when you have an active target.
   - In the background, use low-power updates (Android: balanced-power fused location / geofences; iOS: significant-change plus region monitoring). Request background location only with a clear explanation screen, and handle "denied" gracefully.
   - If the target's last fix is older than ~2 min at verification time, the Function sends the target's device a high-priority/silent push asking for a fresh fix, waits up to ~20 s, then decides. If no fresh fix arrives, mark the snipe **PENDING** and let the target confirm or dispute it. Admin settles disputes.
5. **Scale the tolerances to real GPS accuracy.** Phone GPS is often off by 5–20 m, which makes a fixed ±20° aim check meaningless at close range. Allowed aim error should widen as distance shrinks and as the two accuracy radii grow. Max distance = 50 m + combined accuracy, capped.
6. Store the raw evidence (distance, bearing, heading, accuracy, fix ages) on the snipe doc for disputes.

**Privacy rule:** raw locations live in a separate `locations/{uid}` collection that **only Cloud Functions can read**. No client ever receives another player's coordinates.

## 6. Hunting UI ("tactical compass")

The current compass points straight at the target, which gives away their exact location. Replace it with a **coarse proximity hint** computed by the server and refreshed every ~30 s: a distance band (Far, 1 km+ / Near, 100 m–1 km / Close, <100 m / Very close, <30 m) plus a direction rounded to 8 compass points, shown only when "Near" or closer. *(config: hint on/off)*

## 7. Screens

1. **Onboarding:** sign in (Google / Apple / guest), pick a username, explain permissions (camera, location, notifications).
2. **Home / Hunt:** group switcher, current target card (photo, name, username), proximity hint, immunity timer if you were just sniped, big camera button.
3. **Camera:** live preview, aim reticle, shutter, then a result screen (success with points breakdown / pending / failed with the reason).
4. **Feed (per group):** snipe photos, hunter → target, points, like/unlike, report.
5. **Leaderboard (per group):** all-time / weekly tabs, your rank pinned.
6. **Profile:** stats (score, streak, best streak, snipes, times sniped), groups, settings, sign out, **delete account** (required by both stores).
7. **Group admin:** existing tools, plus game settings from §4, safe zones, dispute queue, regenerate invite code.
8. **Notifications:** new target, you got sniped, your snipe was disputed/resolved, daily reshuffle.

## 8. Data model (Firestore)

- `users/{uid}`: public profile only (name, username, photoUrl, createdAt).
- `locations/{uid}`: lat, lon, accuracy, updatedAt. **Functions-only.**
- `groups/{gid}`: name, adminId, inviteCode, settings, memberIds.
- `groups/{gid}/members/{uid}`: score, weeklyScore, streak, bestStreak, immuneUntil, joinedAt.
- `groups/{gid}/assignments/{hunterUid}`: targetUid, assignedAt. Readable only by that hunter.
- `groups/{gid}/snipes/{sid}`: hunterUid, targetUid, photoPath, status, points, evidence, createdAt, likeCount.
- `groups/{gid}/snipes/{sid}/likes/{uid}`: one doc per like.
- `devices/{uid}/tokens/{token}`: push tokens.

Write `firestore.rules` and `storage.rules`, plus **rules unit tests** with the Firebase emulator. Clients may only write their own profile, likes, and Storage uploads. Everything else goes through Functions.

**Cloud Functions:** `submitSnipe`, `reportLocation`, `dailyReshuffle` (scheduled), `onMemberJoin`/`onMemberLeave` (cycle repair), `resolveDispute`, `weeklyReset` (scheduled), `deleteAccount`, notification fan-out.

## 9. Performance & "optimized" checklist

- Release builds: R8 minify + resource shrinking on Android. Release config with `-Os`-style optimization for Kotlin/Native on iOS.
- Baseline Profiles (Android) for startup and scrolling. Target cold start <1.5 s on a mid-range phone.
- Compose: stable/immutable UI state, `key`s in lazy lists, no work in composition, `collectAsStateWithLifecycle`.
- Photos: compress to ~1080 px JPEG (≤500 KB) before upload. Thumbnails in the feed (Coil 3, multiplatform).
- Firestore: listeners scoped to the current group only, paginated feed (20 at a time), offline persistence on, no N+1 reads (the current `getCurrentTarget` and `getGroupMembers` do nested fetches).
- Location: no high-accuracy polling in the background. Stop updates when you have no active target or are in a safe zone.
- App size target: <25 MB Android download.

## 10. Store readiness & safety

- Real app ID (e.g. `com.daat.hitlist`), app icon, name, privacy policy URL, data-safety (Play) and privacy nutrition labels (Apple).
- In-app account deletion, report/block user, report photo. Admin can remove snipes.
- Background location justification text for both stores (Apple and Google review this closely).
- Age gate (13+) and invite-only groups. **Drop the auto-joined "global" group**: hunting strangers by location is a safety problem and will likely get the app rejected.

## 11. Testing & definition of done

- Unit tests: scoring, verification math (including tolerance scaling), cycle assignment/repair (property-based: every member hunts exactly one and is hunted by exactly one).
- Functions tests against the emulator. Security rules tests (a client cannot read `locations`, cannot write scores, cannot read others' assignments).
- UI runs fully against `FakeGameRepository`.
- **Done =** a group of 3+ people on a mix of Android and iPhone can sign up, join a group by code, get targets, snipe each other in person, see verified results, feed and leaderboard update live, get notifications, and nobody can read anyone's coordinates or edit their own score.

## 12. Suggested build order

1. Repo cleanup (untrack local files, fix manifest, real app ID), then the KMP restructure with Android still working.
2. New data model + security rules + rules tests.
3. Cloud Functions: assignment cycle, `submitSnipe`, location reporting.
4. Rewire the app to the new backend; group-scoped everything.
5. Location freshness + push notifications.
6. iOS shell: camera, location, compass, Apple sign-in.
7. Proximity hint, safe zones, disputes, weekly board.
8. Performance pass, store assets, CI, release builds.

Work in small pull requests per step; keep `main` building.
