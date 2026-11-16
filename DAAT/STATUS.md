# Build status

Branch `hitlist-v1`, built against [`HITLIST_SPEC.md`](../HITLIST_SPEC.md).

## What has been checked, and how

| Check | Result |
|---|---|
| Backend unit tests (scoring, verification maths, target cycles, time zones) | 91 pass |
| Security rules + a full game played through the functions, on the Firebase emulators | Pass in GitHub Actions |
| Shared Kotlin code tests | Pass in GitHub Actions |
| Android debug and release (minified) APKs | Build in GitHub Actions |
| iPhone app, simulator build with Xcode 26 | Builds in GitHub Actions |

**Nothing has been run on a real phone, and the backend has not been deployed.** The code compiles and the server logic is tested, but nobody has yet signed in, taken a photo, or received a push with this build. Expect bugs in the first real session, most likely in camera, location permissions, sign-in and push delivery.

## Built

- Server-only scoring, target assignment (one cycle per group, daily reshuffle, repair on join/leave), snipe verification with accuracy-scaled tolerances, immunity, streak reset, daily snipe cap, safe zones, disputes, weekly board reset, account deletion, push notifications.
- Fresh-location flow: a silent push asks the target's phone for a new fix; if none arrives in 20 seconds the snipe goes to "pending" for the target to confirm or dispute.
- Write-only locations; hunters get a distance band and 8-point direction from the server.
- Shared UI: onboarding with 13+ confirmation, hunt, camera and result, per-group feed (like, report, block), leaderboards, profile, group admin with game settings.
- Android and iPhone shells: camera, location, compass, Google sign-in on both, Apple sign-in on iPhone.
- CI for backend and both apps.

## Needs you (cannot be done from code)

1. **Deploy the backend** (README step 1). The Firebase project must be on the Blaze plan.
2. **iPhone:** register an iOS app in Firebase, add `GoogleService-Info.plist`, set the reversed client id and Apple team id (README step 3). Upload an APNs key for push.
3. **App id:** Android still ships as `com.example.daat` because that is the id registered in Firebase. Google Play rejects `com.example.*`. Register `com.daat.hitlist` in Firebase, replace `androidApp/google-services.json`, then change `applicationId`.
4. **Store listing:** privacy policy URL, data-safety and privacy labels, background-location justification, release signing keys.
5. **Old data:** delete the old `users`, `snipes`, `groups`, `locations` collections before going live.

## Not done yet

- Android Baseline Profile (needs a device or emulator to record).
- Feed loads the latest 20 snipes; there is no "load more".
- Feed shows full-size compressed photos (about 1080 px); no separate thumbnails.
- App icon is the original placeholder.
- Cold-start time and download size have not been measured.
