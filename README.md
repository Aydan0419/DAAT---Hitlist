# Hitlist

A real-world "snipe" game for friend groups. Everyone in a group gets one other player as their **target** and is **hunted** by someone else. Snipe your target by photographing them in person; the server checks you were really there, and you score points.

Android and iPhone, from one shared Kotlin codebase. Team DAAT.

- **Rules and design:** [`HITLIST_SPEC.md`](HITLIST_SPEC.md)
- **Status of this build:** [`DAAT/STATUS.md`](DAAT/STATUS.md)

## What's in here

```
DAAT/
  shared/        All game UI and logic (Kotlin Multiplatform + Compose Multiplatform)
    commonMain/    screens, view model, Firebase data layer: runs on both phones
    androidMain/   camera (CameraX), location, compass, Google sign-in
    iosMain/       camera, location, compass, bridge to Swift
  androidApp/    Android shell: activity, push service, icons
  iosApp/        iPhone shell: Swift app delegate, Google/Apple sign-in
  functions/     Cloud Functions (TypeScript): the only code that can change
                 scores, targets, snipes or group membership
  firestore.rules, storage.rules, firestore.indexes.json
```

## How cheating and privacy are handled

- Phones never decide whether a snipe counts. They upload a photo and their GPS/compass reading; `submitSnipe` on the server does the check and awards points.
- A player's coordinates are **write-only**. Security rules let nobody read them, including the player. Hunters see only a rough distance band ("Near", "Close") worked out by the server.
- Players can read only their own target.
- Both guarantees are covered by automated tests that run against the Firebase emulators on every push.

## First-time setup

You need the [Firebase CLI](https://firebase.google.com/docs/cli) (`npm install -g firebase-tools`) and Node 22.

### 1. Deploy the backend (required before the app works)

```bash
cd DAAT
firebase login
firebase use daat-799f7          # the existing Firebase project
cd functions && npm ci && cd ..
firebase deploy --only functions,firestore,storage
```

Cloud Functions need the Firebase project to be on the **Blaze (pay-as-you-go)** plan. A friend group stays well inside the free allowance.

In the Firebase console, also check that these are turned on: **Authentication** (Google, Anonymous, and Apple for iPhone), **Firestore**, **Storage**, **Cloud Messaging**.

> Deploying the new rules locks down the old data layout. Old `users` documents with scores and coordinates are no longer used; delete the old `users`, `snipes`, `groups` and `locations` collections in the console before going live.

### 2. Android

Open `DAAT/` in Android Studio (Otter 3 or newer) and run the `androidApp` configuration. Or from a terminal:

```bash
cd DAAT
./gradlew :androidApp:assembleDebug     # APK in androidApp/build/outputs/apk/debug/
```

For Google sign-in on a new machine, add that machine's debug SHA-1 fingerprint to the Android app in the Firebase console (`./gradlew :androidApp:signingReport`).

### 3. iPhone (needs a Mac with Xcode 16+)

1. In the Firebase console, add an **iOS app** with bundle id `com.daat.hitlist`. Download `GoogleService-Info.plist` into `DAAT/iosApp/iosApp/`.
2. In `DAAT/iosApp/project.yml`, replace `com.googleusercontent.apps.REPLACE_WITH_REVERSED_CLIENT_ID` with the `REVERSED_CLIENT_ID` value from that plist, and set `DEVELOPMENT_TEAM` to your Apple team id.
3. Generate and open the project:
   ```bash
   brew install xcodegen
   cd DAAT/iosApp && xcodegen generate && open Hitlist.xcodeproj
   ```
4. For push notifications, upload an APNs key in Firebase console → Project settings → Cloud Messaging.

Running on a real iPhone needs an Apple Developer account ($99/year). The simulator works without one, but has no camera.

## Tests

```bash
cd DAAT/functions
npm test                 # 91 unit tests: scoring, verification maths, target cycles, timezones
npm run test:emulator    # 34 tests: security rules + a full game played through the functions

cd DAAT
./gradlew :shared:testAndroidHostTest
```

GitHub Actions runs all of these, builds the Android APK, and builds the iPhone app for the simulator on every push.

## Working on the UI without a backend

`FakeGameRepository` is an in-memory game with sample players. Pass it to the app root to run every screen with no Firebase project:

```kotlin
setContent { App(repository = FakeGameRepository()) }
```
