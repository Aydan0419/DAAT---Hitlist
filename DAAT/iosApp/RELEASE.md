# Getting Hitlist onto iPhones (TestFlight and the App Store)

You do not need a Mac. GitHub builds, signs and uploads the app.
**This release workflow has not been run yet**, because it needs an Apple Developer account. Expect to fix a thing or two on the first run.

## One-time setup

1. **Apple Developer Program.** Enroll at <https://developer.apple.com/programs/enroll/> ($99/year).
2. **Create the app record.** In [App Store Connect](https://appstoreconnect.apple.com) choose Apps, then +, then New App. Platform iOS, name "Hitlist" (or whatever is free), bundle ID `com.daat.hitlist`. If the bundle ID is not in the list, register it first under Certificates, Identifiers & Profiles, with **Sign in with Apple** and **Push Notifications** ticked.
3. **API key.** App Store Connect, Users and Access, Integrations, App Store Connect API. Create a key with the **Admin** role. Note the **Key ID** and **Issuer ID** and download the `.p8` file (it can only be downloaded once).
4. **Firebase.** In the Firebase console add an iOS app with bundle ID `com.daat.hitlist` and download `GoogleService-Info.plist`. Turn on the Apple sign-in provider under Authentication. Upload an APNs key under Project settings, Cloud Messaging, so push works.
5. **GitHub secrets.** In the repo: Settings, Secrets and variables, Actions, New repository secret.

   | Secret | Value |
   |---|---|
   | `APPLE_TEAM_ID` | Team ID from developer.apple.com, Membership details |
   | `APP_STORE_CONNECT_KEY_ID` | Key ID from step 3 |
   | `APP_STORE_CONNECT_ISSUER_ID` | Issuer ID from step 3 |
   | `APP_STORE_CONNECT_KEY_BASE64` | the `.p8` file, base64-encoded |
   | `GOOGLE_SERVICE_INFO_BASE64` | `GoogleService-Info.plist`, base64-encoded |
   | `REVERSED_CLIENT_ID` | the `REVERSED_CLIENT_ID` value inside that plist |

   To base64-encode a file on Windows PowerShell:
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\file")) | Set-Clipboard`

## Each release

1. GitHub, Actions, **iPhone release (TestFlight)**, Run workflow.
2. After 15 to 30 minutes the build shows up in App Store Connect under TestFlight.
3. Add testers by email (internal, instant) or with a public link (external, needs a short Apple review the first time). They install the TestFlight app and then Hitlist.

## Submitting to the App Store

In App Store Connect, on the app's page:

- Screenshots (6.9-inch iPhone size), description, keywords, support URL.
- **Privacy policy URL** (required).
- **App Privacy** answers: precise location, photos, name, user ID. All used for app functionality, linked to the user, not used for tracking. `iosApp/PrivacyInfo.xcprivacy` says the same thing and the two must agree.
- **Age rating** questionnaire.
- **Review notes:** a test login, and an explanation that players are in private invite-only groups, nobody can see another player's coordinates, and background location is only used to confirm a snipe. Reviewers look closely at background location and at apps involving photos of other people.
- Pick the TestFlight build and submit.

## Already in the app for App Store review

Sign in with Apple, in-app account deletion, report and block, 13+ confirmation, permission explanations, privacy manifest, export-compliance flag, app icon.
