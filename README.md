# Remind

Android reminders with Google sign-in, voice/text creation, shared reminders, and optional fullscreen priority alarms. Firebase stores account data and runs the reminder/AI backend. The web/PWA app has been retired; Hosting serves invitation and installation pages only.

- [Release setup checklist](docs/FRIENDS_RELEASE_SETUP.md): Firebase, signing, invitation links, deployment, limits, and acceptance checks.
- [Instructions to send friends](docs/FRIEND_INSTALL.md).
- [Android development](android/README.md).

## Build and test

Use Node 22 and the pinned pnpm version. Run `pnpm install --frozen-lockfile`, `pnpm test`, and `pnpm run build:functions`. From `android`, run `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` with JDK 21 and Android SDK 37 installed.

`cloudbuild.yaml` builds/tests the backend and deploys Functions, Firestore configuration, and static Hosting through the existing trigger. Release APKs require a private signing key; see the release checklist. No App Check debug tokens are required.

## Sharing

One canonical shared reminder controls content, schedule, membership, and default priority. Server-maintained account views hold individual delivery/completion/snooze state. Edits are transactional and version checked. Friends can create reminders together; Done and snooze are personal. Everyone can leave or explicitly delete for everyone. Removing a friend preserves existing shared reminders.

## Limits and delivery

The combined app-wide transcription/interpretation quota is 100 operations per UTC day. Other backend limits are centralized in `functions/src/limits.ts`. Google authentication and server-side authorization remain mandatory; Firestore clients cannot write data directly.

FCM acceptance is not proof that a phone displayed an alert. Synced reminders have local alarm fallback; offline devices cannot learn about remote changes until they reconnect. Fullscreen alarms depend on Android permissions and device settings. Validate both accounts and phones before distributing a release.

The older PLAN.md and TECHNICAL_IMPLEMENTATION_PLAN.md describe the original PWA architecture and are historical.
