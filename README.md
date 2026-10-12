# Remind

Firefox-first reminder PWA. The code follows [PLAN.md](PLAN.md) and [TECHNICAL_IMPLEMENTATION_PLAN.md](TECHNICAL_IMPLEMENTATION_PLAN.md). For Firebase, Google Cloud, and GitHub setup, use [the deployment guide](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md).

## Implemented source

- Material 3 inspired React/TypeScript PWA with system light/dark mode, a prominent push-to-talk control, spoken readbacks, text input, confirmation cards, upcoming reminders, history, and settings.
- Firebase Google sign-in for any verified Google account; each account has private Firestore data and server-only writes.
- Callable functions for recorded-audio transcription, structured conversational proposals, explicit confirmation, direct Done/Dismiss/Snooze, device registration and removal, and conversation deletion. AI usage has per-account and app-wide daily limits.
- Shared recurrence rules for one-time, elapsed intervals, and daily/weekly/monthly/yearly wall-clock schedules, including DST resolution and next-five previews.
- One scheduled dispatcher per minute and a data-only FCM push worker with Done and Snooze notification actions where Firefox supports them.

## Verification and deployment

The user authorized a build for this project. The committed pnpm lockfile supports `pnpm install --frozen-lockfile`; `functions/package-lock.json` pins the dependencies installed by the Cloud Functions buildpack. Run `pnpm test`, `pnpm run typecheck:web`, `pnpm run build:functions`, and `pnpm run build:web` to verify it. The local checks and production dependency audit passed on 2026-10-11. The production web build has a bundle-size warning, but completes successfully.

[`cloudbuild.yaml`](cloudbuild.yaml) runs those checks and deploys on a GitHub push after the Cloud Build trigger is configured. The supplied public Firebase Web config is recorded there. [`scripts/prepare-deploy.mjs`](scripts/prepare-deploy.mjs) validates settings and writes the ignored Functions environment file. See [the setup guide](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md) for configuration. Verify each deployment and device behavior separately.

## Device acceptance

Use the actual Samsung phone with current Firefox for Android. Record the exact model and version numbers during testing. Install the PWA from Firefox and grant microphone access. Notification access is requested when confirming the first reminder; **Enable & test** in Settings sends a real push for manual diagnosis. With permission granted, the app refreshes registration when opened, when brought back into view, after network recovery, and when its server device record disappears. Failed refreshes retry with backoff. Then check a five-minute reminder with Firefox foregrounded, backgrounded, closed, and after a phone restart. Check Done, Dismiss alert, Snooze, recurrence, timezone travel, and offline recovery. If background delivery remains unreliable on that device, a native Android notification component is the next stronger option.

## Delivery diagnostics

Each send attempt is recorded in Firestore at `users/{uid}/occurrences/{reminderId}_{scheduledAt}`. The `status` is `sent`, `retry`, or `missed`; `reason` distinguishes `accepted`, `no_devices`, `all_rejected`, and `send_error`. The record also includes device, accepted, and rejected counts plus Firebase error codes. `sent` means FCM accepted the push, not that the phone displayed it. Older occurrence records lack these diagnostic fields until retried.

Backend Cloud Logs use structured `operation.started`, `operation.stage`, `operation.succeeded`, and `operation.failed` entries for callable functions. Each has an `operationId`, `operation`, `uid`, and elapsed time; stages identify validation, quota, AI, persistence, and device registration progress. `dispatchDue` logs a claim and delivery result per occurrence, then a run summary when it found due reminders. Empty minute scans produce no app log. Logs omit reminder text, transcripts, audio, push tokens, API keys, exception messages, and stacks. See [the log queries](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md#6-view-backend-logs).

Delivery is best effort. Chrome is not required. A notification action opens the authenticated app and applies the requested action; the service worker does not hold credentials. Text conversations remain until deleted. Raw audio is sent for transcription and is not stored in Firestore.

**Dismiss alert** closes a currently displayed notification and leaves future nudges scheduled. **Done** finishes the current occurrence and stops its repeat-until-Done nudges; recurring reminders keep their next regular occurrence. Upcoming only offers Dismiss alert for a recently sent or active alert.
