# Remind

Personal, Firefox-first reminder PWA. The code follows [PLAN.md](PLAN.md) and [TECHNICAL_IMPLEMENTATION_PLAN.md](TECHNICAL_IMPLEMENTATION_PLAN.md).

## Implemented source

- Material 3 inspired React/TypeScript PWA with system light/dark mode, a prominent push-to-talk control, spoken readbacks, text input, confirmation cards, upcoming reminders, history, and settings.
- Firebase Google sign-in with a single UID allowlist; Firestore client reads and server-only writes.
- Callable functions for recorded-audio transcription, structured conversational proposals, explicit confirmation, direct Done/Dismiss/Snooze, device registration, and conversation deletion.
- Shared recurrence rules for one-time, elapsed intervals, and daily/weekly/monthly/yearly wall-clock schedules, including DST resolution and next-five previews.
- One scheduled dispatcher per minute and a data-only FCM push worker with Done and Snooze notification actions where Firefox supports them.

## Setup before deployment

1. Create a **new** Firebase project on Blaze. Enable Google Authentication, Firestore, Hosting, App Check for the web app, Cloud Messaging, and 2nd generation Cloud Functions. Choose the Firestore region before creating the database.
2. Copy `.firebaserc.example` to `.firebaserc` and set the dedicated project ID. Copy `web/.env.example` to `web/.env` and fill the public Firebase web config, VAPID key, reCAPTCHA v3 site key, and owner UID. Do not put secrets in the web config.
3. Sign in once in Firebase Authentication to obtain the exact Firebase UID for your Google account. Put it in `functions/.env` as `OWNER_UID` and replace `REPLACE_WITH_OWNER_UID` in `firestore.rules` with the same UID. The placeholder rule deliberately denies all reads.
4. Set the Cloud Functions Secret Manager secret `OPENAI_API_KEY`. Keep the key out of Git and the browser. Add budget alerts for OpenAI and Google Cloud.
5. Install dependencies to generate and commit a lockfile. Build `functions` and `web` before deploying. `firebase.json` does not run a build automatically. Deploy Firestore rules, functions, and Hosting together only after the build succeeds.

The repository instruction forbids running applications and build tools here. Dependencies were not installed, no lockfile was generated, and no build or device test has been run. The setup and deployment steps above are for a later authorized environment.

## Device acceptance

Use the actual Samsung phone with current Firefox for Android. Record the exact model and version numbers during testing. Install the PWA from Firefox, grant microphone and notification access, use **Enable & test** in Settings, then check a five-minute reminder with Firefox foregrounded, backgrounded, closed, and after a phone restart. Check Done, Dismiss, Snooze, recurrence, timezone travel, and offline recovery. If FCM registration or background delivery fails on that device, add a standards-based Web Push transport while keeping the PWA interface.

Delivery is best effort. Chrome is not required. A notification action opens the authenticated app and applies the requested action; the service worker does not hold credentials. Text conversations remain until deleted. Raw audio is sent for transcription and is not stored in Firestore.
