# Release Remind to friends

The Android app now uses Google sign-in without App Check. Friends do not need a Firebase account, a debug token, or their phone registered manually. They share your Firebase/OpenAI backend, so usage is billed to your projects.

Complete this checklist before distributing the release APK. The repository changes do not change your live Firebase console automatically.

## 1. Keep one permanent signing key

In Android Studio, open the `android` project and use **Build → Generate Signed App Bundle or APK → APK**. Create a release keystore if you do not already have one. Store it outside the repository, save its alias/passwords in your password manager, and back it up securely. Reuse this key for every update. Do not email it or commit it.

The Gradle release configuration accepts these environment variables:

- `REMIND_KEYSTORE`: absolute path to the keystore.
- `REMIND_STORE_PASSWORD`: keystore password.
- `REMIND_KEY_ALIAS`: key alias.
- `REMIND_KEY_PASSWORD`: key password.

Set them privately in your build environment; do not paste passwords into chat or a tracked script. Build with `./gradlew.bat assembleRelease` from `android`. The output is `android/app/build/outputs/apk/release/app-release.apk`. Increment `versionCode` before each later release.

Your existing debug installation uses a different certificate. Moving to a new release certificate normally requires uninstalling the debug app first. Sync pending actions first; server reminders return after signing in, but unsynced local data will be lost. Friends should start with the release APK. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## 2. Configure Firebase sign-in and remove App Check enforcement

Project: **reminder-app-a2150**. Android package: **com.qcbru.remind**.

1. Firebase Console → Project settings → Your apps → the Android app. Add the release certificate's **SHA-1 and SHA-256** fingerprints. Obtain them from Android Studio's signing report, or `keytool -list -v -keystore <your-keystore>`; let it prompt for the password.
2. Authentication → Sign-in method: ensure **Google** is enabled and has a support email.
3. Download the updated `google-services.json` into `android/app/google-services.json` and rebuild the APK. The Google web OAuth client remains necessary for Android sign-in even though the PWA is removed; do not delete it.
4. Security → App Check → APIs: if enforcement is enabled for **Cloud Firestore** or **Authentication**, turn it off. Also check other products you previously enforced. This release no longer supplies App Check tokens. Callable Functions enforcement is removed by deploying the code.
5. Once the new backend/client is deployed, old debug-token allowlist entries are unnecessary. No Play Integrity setup is needed for this version.
6. If a friend's sign-in is rejected due to your Google OAuth audience configuration, check Google Cloud's Google Auth Platform → Audience. Verify the app is available to external accounts and any testing-user restrictions are satisfied. Confirm with an account that has never used the app; do not assume your successful sign-in proves this.

Certificate fingerprints identify the APK signer, not individual phones. Register them once for everyone using that APK. [Firebase Google sign-in](https://firebase.google.com/docs/auth/android/google-signin), [App Check enforcement](https://firebase.google.com/docs/app-check/enable-enforcement).

## 3. Make invitation links open the Android app

Replace the empty array in `hosting/.well-known/assetlinks.json` with the following, substituting your **release SHA-256 certificate fingerprint**, including its colons:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.qcbru.remind",
      "sha256_cert_fingerprints": ["YOUR_RELEASE_SHA256_FINGERPRINT"]
    }
  }
]
```

The fingerprint is public information and should be committed. For testing debug links, a second fingerprint can be included in the array. After Hosting deployment, verify `https://reminder-app-a2150.web.app/.well-known/assetlinks.json` returns this JSON directly, without a redirect or the landing page.

Invitations use `https://reminder-app-a2150.web.app/invite/<token>`. This domain is configured in the Android manifest and link handler. If you change Firebase projects/domains, update both. Android verifies links against the published fingerprint. See [website associations](https://developer.android.com/training/app-links/configure-assetlinks).

There is no deferred install tracking: a friend who installs after opening a link must return to the original message and tap it again. The landing page explains this. Links require confirmation, expire after seven days, and accept one friend. Creating a new link revokes the previous link; Friends also has a Revoke button.

## 4. Deploy the backend and landing pages

Use the existing Cloud Build `remind-deploy` trigger on the commit containing these changes, or your usual Firebase CLI deployment:

```powershell
pnpm install --frozen-lockfile
pnpm test
pnpm run build:functions
node scripts/prepare-deploy.mjs
firebase deploy --project reminder-app-a2150 --only functions,firestore,hosting
```

The runtime is Node 22; use that version locally/CI. The existing `OPENAI_API_KEY` Secret Manager secret must remain available to Functions. Region remains `us-south1`. The new Firestore-triggered `syncReminderDevices` function must deploy successfully; shared edits/deletions use it to wake background clients. If Cloud Build reports a missing Eventarc/API/IAM prerequisite, complete the specific console setup it reports and rerun. Do not distribute while this function has failed deployment.

No Firestore data migration is required. Private reminders keep their paths. New shared reminders have one authoritative `sharedReminders/{id}` document and server-maintained per-account delivery views under `users/{uid}/reminders/{id}`. Clients cannot write either directly. Existing owner-only rules remain unchanged; new top-level collections remain inaccessible to clients. Do not enable Firestore test mode or add public-read rules.

The web/PWA application is removed. Hosting now serves only the install/invitation page and retirement worker. Updated Android installs register `platform: android`; the dispatcher ignores old untagged/web tokens. Have existing Android users open this version once with notifications allowed so their registration is refreshed. Existing PWA users should visit the old site once and remove its home-screen shortcut.

## 5. Limits and monitoring

Limits are centralized in `functions/src/limits.ts`; change them there, commit, and redeploy:

| Limit | Default |
| --- | --- |
| Combined transcription + interpretation, entire app | 100/day |
| Combined AI, per account | 50/day, 5/minute |
| All callable requests, per account | 60/minute |
| Invitation create/preview/accept, per account | 10/hour |
| Test notification, per account | 5/hour |
| Registered devices, per account | 10 |
| Friends, per account | 100 |
| Participants in one shared reminder | 10 including creator |
| Active reminder creation/admission check | 200 per account |

Daily limits reset at **00:00 UTC**. Typed requests usually consume one AI operation; voice requests usually consume two. Failed provider attempts still count. Once AI allowance is exhausted, existing notifications, Done, snooze, and friendship operations still work within their own limits. The counters are server-owned and transactional.

In Firestore's TTL settings, enable an expiration policy for collection group **rateLimits**, field **expiresAt**, to clean up old rate counters. Expiration is housekeeping; enforcement uses time buckets and does not depend on prompt TTL deletion.

In Cloud Logging, monitor `quota.rejected`, `dispatchDue.queryFailed`, and `Reminder push not accepted`, plus errors for `syncReminderDevices`. Configure a Google Cloud budget alert and review OpenAI project usage. These quotas and instance bounds are **not a hard dollar spending cap**: authentication, rejected requests, database reads/writes, delivery, and multiple accounts can still cost money. Budget alerts notify; they do not automatically stop spending.

## 6. Acceptance checks before sending

- Install the **same signed release APK** on two phones with two different Google accounts, including one new to the project. Neither phone should need App Check tokens.
- Complete onboarding; try a duplicate username. Verify an invitation from a signed-out state survives sign-in and onboarding, then requires Add Friend.
- Create “Remind me and @friend to meet in five minutes.” Check the confirmed names and time. Verify both see the reminder and either can edit it.
- Verify Done and snooze affect only the acting person, concurrent edits are rejected safely, leaving stops your alerts, and Delete for everyone removes both views.
- Close the apps, change a shared reminder, and verify the other device synchronizes. Repeat after reconnecting from airplane mode. A disconnected device cannot learn about remote edits/deletions until it reconnects.
- Create a priority reminder. Enable notifications, exact alarms, and fullscreen access where prompted. Test locked and unlocked phones, permission denied, reboot, and each person's priority override. Fullscreen is subject to Android/device settings and does not automatically bypass DND.
- Test the updated APK installing over the prior **release-signed** APK without losing server data.

Local verification covers builds/unit tests, including a mocked Firestore transaction harness. It does not substitute for these live Firebase, cross-device, permission, and delivery checks. The release signing key, live Firebase console state, and real-device acceptance must be completed before calling this release ready.

Send the APK with the text in [FRIEND_INSTALL.md](FRIEND_INSTALL.md). If an email provider blocks APK attachments, send a private download link to that same signed APK instead; do not rename it to bypass security checks.
