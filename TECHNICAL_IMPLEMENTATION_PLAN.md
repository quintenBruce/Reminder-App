# Technical implementation plan

Status: planned, not implemented. This document refines [PLAN.md](PLAN.md). Prices and platform behavior were checked on 2026-10-11 and must be rechecked before deployment.

## Selected stack and languages

| Layer | Choice | Language |
| --- | --- | --- |
| Firefox for Android installable PWA | React, Vite, Firebase Web SDK, service worker | TypeScript, HTML, CSS |
| Shared domain logic | Validated action/schedule types and recurrence calculator | TypeScript |
| API and background jobs | Cloud Functions for Firebase, 2nd generation, Node runtime | TypeScript |
| Data and access | Cloud Firestore, Firebase Authentication, Firestore rules | Firestore rules language, JSON configuration |
| Hosting and push | Firebase Hosting, Firebase Cloud Messaging (FCM) | JSON configuration, TypeScript service worker |
| AI | OpenAI `gpt-transcribe` and GPT-6.1 Sol with Responses API structured output | Called from TypeScript functions |

There is no Kotlin, Java, Gradle, or native Android application in the initial version. Do not run the application or build tools in this workspace, per the repository instruction.

## How Cloud Functions fit

Cloud Functions are small backend entry points deployed to Google, not a server process we maintain. The PWA calls an HTTPS function when it has a recorded voice clip, text request, or confirmed action. Google starts an instance to handle the request and can scale it down when idle. A separate scheduled function is invoked by one Cloud Scheduler job every minute; it checks Firestore for due reminders and sends FCM pushes. The OpenAI key stays in Secret Manager, accessible only to the functions.

```text
Phone mic -> HTTPS function -> OpenAI transcript -> interpret function
    -> structured proposal -> PWA readback + explicit confirmation
    -> commit function -> Firestore

Cloud Scheduler (UTC, every minute) -> dispatch function
    -> Firestore due query + claim -> FCM -> phone service worker
    -> Done/Snooze action -> authenticated app route -> action function
```

The scheduled function is not one job per reminder. A single job handles all due occurrences. Server code computes future times using deterministic rules. Model output is never sent straight to the database.

## Interface and theme

- Use a clean Material 3 visual system: generous spacing, clear type, calm surfaces, restrained indigo/teal accent, rounded controls, and accessible contrast. Home is conversation-led with a prominent microphone button and a compact list of upcoming reminders. Keep full reminder management one tap away.
- Follow Android's light/dark preference via `prefers-color-scheme`; provide an in-app System/Light/Dark setting. Set matching PWA `theme-color` for the browser frame. Material Web color tokens can guide component colors.
- A PWA can follow the system's **light/dark mode**. Standard web theming does not provide a dependable way to read the phone's Material You wallpaper palette, so the app will use its own coordinated Material 3 palette. A user-selectable accent can be added later.
- Main screens: Home/conversation, Upcoming, Reminder detail/edit, History, Settings. The Home screen puts voice capture at thumb reach, shows the transcript and confirmation card, and gives clear success/error feedback. Text input remains available.
- Voice is recorded after the user taps the mic and sent when they stop recording. Use the recommended OpenAI file transcription path for bounded clips, then GPT-6.1 Sol at low reasoning effort for interpretation. Its cost is modest at this personal volume and it is better suited to complex edits than a model optimized for high-volume scoped tasks. Browser speech synthesis reads the reply aloud; the full reply is always visible. This is push-to-talk, not an always-open live voice session.
- Full reminder text may appear on the lock screen, per user preference. Add a later privacy toggle for generic alerts. Install from Firefox for Android's menu; no Chrome dependency.

## Proposed repository layout

```text
web/                    React PWA, styles, manifest, service worker
functions/              Firebase HTTPS/callable/scheduled functions
shared/                 Schedule types, validation, recurrence engine
firestore.rules          Single-user read rules; server-owned writes
firestore.indexes.json  Due-reminder query indexes
firebase.json           Hosting, function, and rule configuration
docs/                   Setup and device validation instructions
```

Build to `web/dist` for Firebase Hosting. Use one TypeScript toolchain and shared types for browser and server. Pin dependency versions and commit the lockfile. Keep Firebase project IDs and public web configuration out of secret storage, but never commit OpenAI keys or service account credentials.

## Data contracts

- `UserSettings`: allowlisted UID, preferred IANA time zone, theme mode, notification preview mode, default snooze interval, and last device time zone.
- `Reminder`: ID, title, optional notes, original wording, normalized `ScheduleRule`, status, next due UTC instant, version, created/updated timestamps.
- `ScheduleRule`: discriminated variants for one-time, elapsed interval, daily/weekly/monthly/yearly wall-clock patterns, date limits, exclusions, and repeat-until-done nudges. Minimum elapsed interval: five minutes.
- `Occurrence`: stable ID derived from reminder ID and scheduled instant, scheduled/delivery times, state (`pending`, `sent`, `done`, `dismissed`, `snoozed`, `missed`), attempt count, and action timestamps.
- `Proposal`: intent (`create`, `edit`, `cancel`, `done`, `dismiss`, `snooze`, `list`, `clarify`), candidate reminder ID, normalized changes, human readback, preview times, expiry, and base reminder version. Confirmation commits a proposal by ID; the browser does not send an unchecked schedule as a confirmed write.
- `ConversationTurn`: transcript or typed text, assistant response, referenced reminder IDs, and optional proposal ID. Keep a short rolling history. Do not persist raw audio by default.
- `Device`: FCM token, last seen, permission status. Remove invalid or stale tokens.

Store records below `users/{uid}/...`. Client reads are restricted to the allowlisted UID. Mutations go through functions so schedule invariants, idempotency keys, and occurrence state cannot be bypassed by a browser write.

## Function contracts

1. `transcribeAudio` (HTTPS): verify Firebase ID token and app origin; enforce clip size/duration; forward the file to OpenAI; return transcript; discard audio. A typed request skips this function.
2. `proposeAction` (callable): accept text and conversation ID; fetch a bounded set of relevant reminders; call the Responses API with a strict action schema; validate target IDs and schedule fields; compute the next five times; persist a short-lived proposal; return a readback or clarification.
3. `confirmProposal` (callable): check owner, proposal expiry, target version, and idempotency key; transactionally create/update/cancel/act; return committed state. If the reminder changed since proposal creation, require a new proposal.
4. `listReminders`/Firestore read subscription: authenticated reads for upcoming/history and relevant target candidates. Limit result size and pagination.
5. `registerDevice` (callable): store or refresh the FCM token after notification permission is granted. Send a setup test push.
6. `dispatchDue` (scheduled): once per UTC minute, claim due records in batches, send FCM, record outcome, and calculate next occurrences. Retry transient failures within a bounded window. Use stable occurrence IDs and notification tags to reduce visible duplicates.
7. `applyOccurrenceAction` (callable): Done, Dismiss, or Snooze the current occurrence, then stop any repeat-until-done nudges. Notification action buttons open the authenticated PWA route and apply the action there; no long-lived privileged token is embedded in a push.

All mutation functions require the allowlisted UID. Add App Check and per-account rate limits before deployment. Return structured error codes for expired proposal, ambiguous target, permission denied, offline, and failed push enrollment.

## Time and recurrence rules

- Interpret “every five minutes” as elapsed-time intervals anchored to a start instant. Interpret “every day at 9” as local wall-clock recurrence in an IANA time zone.
- Use a shared deterministic recurrence implementation with Temporal-compatible zoned arithmetic. Store UTC instants for dispatch and wall-clock rules for future calculation. Model output supplies fields, never precomputed future dates as authority.
- DST policy: when a local time is skipped, move forward to the next valid time; when repeated, fire once at the earlier instant. Show the next five occurrences during confirmation.
- One-time reminders stay fixed to an instant while traveling. For repeating wall-clock reminders, keep the confirmed zone until the app observes a phone-zone change and asks whether to switch future occurrences. Expose a configurable fixed/follow-device default later.
- If the scheduler was delayed, record missed occurrences and send at most one catch-up alert per reminder rather than a burst. The app always shows overdue items. Delivery remains best effort because browser push, connectivity, and device power management can delay it.

## Security and deployment

- Create a dedicated Firebase project and enable Blaze. Use Google sign-in; after first login, record the Firebase UID as the sole permitted account in function configuration and Firestore rules. Do not use an email comparison as the only access check.
- Put the OpenAI key in Secret Manager. Set OpenAI project spend alerts and a hard spend limit; set Google Cloud budget alerts, noting that Google Cloud budgets are notifications rather than a universal hard cap.
- Use one region for Firestore and functions where available, likely `us-central1` for a Chicago-based user; confirm the exact Firestore location before creation because it is difficult to change.
- Deploy static assets to Firebase Hosting over HTTPS. Configure one FCM web push VAPID key and service worker. Restrict function origins, set request size limits, and log delivery outcomes without logging raw audio or secrets.
- Use minimum instances of zero and a low maximum instance count for this single-user app. Keep one scheduler job at `* * * * *` in UTC to avoid scheduler DST anomalies.

## Monthly cost estimate in USD

Assumptions: 30 days; 20 seconds of audio and 2,000 text input / 500 total billable output tokens per voice turn; `gpt-transcribe` at $0.0045/minute; GPT-6.1 Sol at $2/1M input and $10/1M output tokens, using standard processing. Browser speech synthesis has no OpenAI TTS charge. These are usage estimates, not a quote; longer prompts, reasoning output above this allowance, retries, and model changes increase cost.

| Voice turns | Audio minutes | Transcription | Sol interpretation | OpenAI subtotal |
| --- | ---: | ---: | ---: | ---: |
| Average: 2/day (60/month) | 20 | $0.09 | $0.54 | **about $0.63/month** |
| Busy: 10/day (300/month) | 100 | $0.45 | $2.70 | **about $3.15/month** |

GPT-6 Luna would lower those estimates to about **$0.12/month** at 2/day or **$0.59/month** at 10/day under the same assumptions. Keep Sol as the initial model for complex conversational edits; consider Luna only after representative reminder phrases show equal reliability. Confirmation and deterministic validation remain mandatory with either model.

For Firebase: one minute scheduler produces about 43,200 function invocations in a 30-day month, below the published 2 million/month no-cost invocation allowance. FCM is no-cost. A single Cloud Scheduler job is within the current allowance of three no-charge jobs per Google account; otherwise it is $0.10/month. Firestore, Hosting, function compute, Secret Manager, and build artifacts should be within their no-cost allowances at this personal scale **if the account's allowances are available**, but can incur charges. Blaze billing is required for Functions. Allow a practical initial budget of **$10/month total**, with alerts at $2 and $5; review actual usage after the first month.

## Implementation order and review gates

1. Create the Firebase project, region, billing alerts, Google sign-in, UID allowlist, Secret Manager entry, and FCM web credentials.
2. Build the Material 3 PWA shell, theme, mic capture, typed input, Google sign-in, and read-only upcoming/history views.
3. Implement shared schedule types, recurrence calculator, DST and interval fixtures, Firestore rules, and transaction-based mutations.
4. Add transcription, structured proposals, conversational target resolution, spoken readback, confirmation, and clarification turns.
5. Add FCM enrollment, due dispatcher, retry ledger, notification actions, and overdue handling.
6. Review source and configuration without running the application here. Provide device test steps for the user: install in Firefox for Android, grant permissions, test 5-minute reminder, background/closed browser, restart, offline recovery, recurrence, DST, Done/Snooze, and account denial.

## Remaining device detail

Before final push behavior is accepted, record the Samsung model, Android version, and Firefox version. Firefox for Android is the initial target. Validate installability, notification permission, push with the PWA in the background, push after its tab is closed, push after Firefox is swiped away, and notification action buttons on that device. The last two cases may differ and are not guaranteed by documentation. If the Firebase Messaging SDK cannot register or deliver there, evaluate standards-based Web Push with the same PWA and backend before changing the product architecture. In-app Done and Snooze remain available regardless of notification button support.

## References

- [Cloud Functions for Firebase](https://firebase.google.com/docs/functions)
- [Scheduled functions and Scheduler cost](https://firebase.google.com/docs/functions/schedule-functions)
- [Firebase pricing](https://firebase.google.com/pricing)
- [Firebase web push setup](https://firebase.google.com/docs/cloud-messaging/web/get-started)
- [Firebase JavaScript SDK browser support](https://firebase.google.com/docs/web/environments-js-sdk)
- [Install web apps with Firefox for Android](https://support.mozilla.org/en-US/kb/use-web-apps-firefox-android)
- [Firefox for Android web push](https://support.mozilla.org/en-US/kb/push-notifications-firefox)
- [Firefox notification action support](https://bugzilla.mozilla.org/show_bug.cgi?id=1959931)
- [OpenAI API pricing](https://developers.openai.com/api/docs/pricing)
- [GPT-6 Luna model pricing](https://developers.openai.com/api/docs/models/gpt-6-luna)
- [GPT-6.1 Sol model pricing](https://developers.openai.com/api/docs/models/gpt-6.1-sol)
- [OpenAI model selection](https://developers.openai.com/api/docs/guides/model-selection)
- [OpenAI transcription workflow](https://developers.openai.com/api/docs/guides/transcription)
- [Material Web color tokens](https://material-web.dev/theming/color/)
- [System light/dark CSS](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/%40media/prefers-color-scheme)
- [Temporal zoned time behavior](https://tc39.es/proposal-temporal/docs/zoneddatetime.html)
