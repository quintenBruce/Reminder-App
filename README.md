# Remind

Firefox-first reminder PWA. The code follows [PLAN.md](PLAN.md) and [TECHNICAL_IMPLEMENTATION_PLAN.md](TECHNICAL_IMPLEMENTATION_PLAN.md). For Firebase, Google Cloud, and GitHub setup, use [the deployment guide](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md).

## Implemented source

- Material 3 inspired React/TypeScript PWA with system light/dark mode, a sticky header, a prominent push-to-talk control, spoken readbacks for voice requests, text input, confirmation cards, upcoming reminders, history, and settings.
- Firebase Google sign-in for any verified Google account; each account has private Firestore data and server-only writes.
- Callable functions for recorded-audio transcription, structured conversational proposals, explicit confirmation, Done/Snooze, permanent reminder deletion, device registration and removal, and conversation deletion. Legacy Dismiss calls remain supported for older clients. AI usage has per-account and app-wide daily limits.
- Shared recurrence rules for one-time, elapsed intervals, and daily/weekly/monthly/yearly wall-clock schedules, including numbered or last weekdays of a month and weekly days after a monthly cutoff, DST resolution, and next-five previews.
- One scheduled dispatcher per minute and a data-only FCM push worker with Done and Snooze notification actions where Firefox supports them. Duplicate reminder descriptions are omitted from push notifications.
- Voice capture requests the microphone with `getUserMedia`, records PCM through an AudioWorklet, and sends a mono 16 kHz WAV to the transcription function. The worklet selects the active input channel; the UI shows input level and rejects very short or silent WAVs locally. If Web Audio cannot start, capture falls back to the browser's `MediaRecorder` format.

For example, “remind me every 3rd Sunday of the month at noon” fires on the third Sunday in each month. “Starting the 10th of every month, remind me every Friday at noon to do QuickBooks” fires on Fridays dated the 10th or later, resetting that cutoff each month. The app asks for a time when one is missing and reads the interpreted schedule back for confirmation.

## Verification and deployment

The user authorized a build for this project. The committed pnpm lockfile supports `pnpm install --frozen-lockfile`; `functions/package-lock.json` pins the dependencies installed by the Cloud Functions buildpack. Run `pnpm test`, `pnpm run typecheck:web`, `pnpm run build:functions`, and `pnpm run build:web` to verify it. On 2026-10-12, 52 tests, both typechecks, and the production web build passed. The web build has a bundle-size warning, but completes successfully.

[`cloudbuild.yaml`](cloudbuild.yaml) runs those checks and deploys on a GitHub push after the Cloud Build trigger is configured. The supplied public Firebase Web config is recorded there. [`scripts/prepare-deploy.mjs`](scripts/prepare-deploy.mjs) validates settings and writes the ignored Functions environment file. See [the setup guide](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md) for configuration. Verify each deployment and device behavior separately.

## Device acceptance

Use the actual Samsung phone with current Firefox for Android. Record the exact model and version numbers during testing. Install the PWA from Firefox and grant microphone access. Notification access is requested when confirming the first reminder; **Enable & test** in Settings sends a real push for manual diagnosis. With permission granted, the app refreshes registration when opened, when brought back into view, after network recovery, and when its server device record disappears. Failed refreshes retry with backoff. Then check a five-minute reminder with Firefox foregrounded, backgrounded, closed, and after a phone restart. Check Done, Snooze, grouped previews, permanent Delete, recurrence, timezone travel, and offline recovery. If background delivery remains unreliable on that device, a native Android notification component is the next stronger option.

## Delivery diagnostics

Each send attempt is recorded in Firestore at `users/{uid}/occurrences/{reminderId}_{scheduledAt}`. The `status` is `sent`, `retry`, or `missed`; `reason` distinguishes `accepted`, `no_devices`, `all_rejected`, and `send_error`. The record also includes device, accepted, and rejected counts plus Firebase error codes. `sent` means FCM accepted the push, not that the phone displayed it. Older occurrence records lack these diagnostic fields until retried.

Backend Cloud Logs use structured `operation.started`, `operation.stage`, `operation.succeeded`, and `operation.failed` entries for callable functions. Each has an `operationId`, `operation`, `uid`, and elapsed time; stages identify validation, quota, AI, persistence, and device registration progress. `dispatchDue` logs a claim and delivery result per occurrence, then a run summary when it found due reminders. Empty minute scans produce no app log. Logs omit reminder text, transcripts, audio, push tokens, API keys, exception messages, and stacks. See [the log queries](docs/FIREBASE_GOOGLE_CLOUD_SETUP.md#6-view-backend-logs).

Delivery is best effort. Chrome is not required. A notification action opens the authenticated app and applies the requested action; the service worker does not hold credentials. Each page load and successful change starts an empty Ask conversation. Confirmed sessions are saved under their reminder and can be deleted individually there. Unconfirmed drafts are discarded on the next load or by daily cleanup after 24 hours. Raw audio is sent for transcription and is not stored in Firestore.

The first upgraded load for each account permanently removes the previous long dashboard conversation. Existing reminder records remain.

Upcoming groups each reminder into a parent with Edit and Delete and up to three future dates. Only an alert accepted by FCM gets Done and Snooze 10 min. **Done** acknowledges that occurrence and stops its repeat-until-Done nudges; recurring reminders keep their next regular occurrence. Snooze delays the current occurrence. Swiping a phone notification away hides it without changing the reminder.

Upcoming lists only reminders with a scheduled next alert. A one-time reminder whose alert was sent and has no next time appears in History as **Alert sent**, where Done or Snooze remain available. This also applies to a recurrence after its final scheduled alert.

Delete is permanent and requires confirmation. It stops scheduling first, then removes the reminder and its occurrence, action, event, proposal, and linked conversation records in retryable pages. Cloud Logs remain. An already displayed or in-flight phone notification may remain.

The microphone and reminder action buttons request short haptic feedback when the browser permits it. No sound is played by this feature. Firefox for Android currently has an [open `navigator.vibrate()` issue](https://bugzilla.mozilla.org/show_bug.cgi?id=1653318), so button haptics may be unavailable on that browser.
