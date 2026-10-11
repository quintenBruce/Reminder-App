# Personal voice reminder PWA: implementation plan

## Decisions

- Build an installable PWA for the user's Samsung Android phone, targeting Firefox for Android. No Chrome installation is required.
- Use a new, dedicated Firebase project on the Blaze plan. Use Firebase Hosting, Google sign-in, Firestore, Cloud Functions, Cloud Scheduler, and Firebase Cloud Messaging (FCM).
- Allow only one configured Google account. Push-to-talk input and spoken replies are in scope from the first version. Every voice-requested create, edit, cancel, dismiss, and snooze action gets a readback and explicit confirmation before it is committed. Direct UI and notification buttons act on tap.
- Support conversational references to existing reminders from the first version. Initial minimum repeat interval: five minutes. Notification delivery is best effort, with no promise of exact timing or offline delivery.
- Dismiss hides the current alert but does not mark it Done or stop a repeat-until-done schedule. Keep text conversation history until the user deletes it.
- Do not run the application, Gradle, Java, or other app/build commands during development, per the workspace instruction. Verification will use source review and documented device test steps for the user.

## Architecture

```text
Android Firefox PWA (mic, chat, reminder list, notification service worker)
  -> Firebase Auth (one allowlisted UID)
  -> Firebase HTTPS / callable functions
       -> OpenAI transcription + structured intent extraction
       -> deterministic schedule validator and recurrence calculator
       -> Firestore reminders, occurrences, conversation turns, device tokens
  -> Cloud Scheduler, once per minute
       -> scheduled function claims due occurrences -> FCM web push
  <- notification actions -> authenticated function -> Firestore update
```

The LLM interprets a request and proposes actions. It does not calculate future occurrences or mutate records directly. The app shows the transcript, the proposed change, and the next few reminder times. The user confirms by voice or tap. Ambiguous target reminders or dates cause a clarifying question, not a guess.

## Product behavior

1. **Capture:** Tap and hold or tap to record; show recording state and allow cancellation. Send a short audio clip to an authenticated HTTPS function, with a size and duration limit. Do not retain raw audio after transcription. Provide typed input as a fallback.
2. **Interpret:** Transcribe audio with OpenAI. Give the model the current time, device time zone, recent conversation, and a bounded list of candidate reminders. Require a structured proposal: create, edit, cancel, dismiss occurrence, snooze occurrence, list, or clarification. The server validates all referenced reminder IDs and fields.
3. **Review:** Show and speak a concise readback, including title, first due time, recurrence, end condition, and any repeat-until-done behavior. Preview the next five occurrences. Wait for explicit confirmation. Treat a second voice turn such as “yes, but at 10” as a revision of the pending proposal.
4. **Manage:** Upcoming, overdue, completed, and canceled views. Search and filter. Open a reminder for edit, dismiss the current alert, snooze, mark the occurrence Done, or cancel the series. Dismiss only hides the alert; repeat-until-done nudges continue. Done completes the occurrence and stops its nudges. Cancel stops the series. Swiping away an Android notification is not treated as completion.
5. **Notify:** Ask for browser notification permission during setup, register the PWA's service worker and FCM token, and send a test notification. Show Done and Snooze actions where the installed Firefox version supports them. Tapping a notification opens the relevant reminder. The app always provides the same actions inside its UI.
6. **Speak back:** Use browser speech synthesis initially, with visible text as the fallback. Add OpenAI speech generation only if device voice quality is inadequate; this avoids an extra API charge for each response.

## Schedule semantics

- Store the user's original wording alongside a normalized schedule. Keep the schedule deterministic and independent of the LLM output after confirmation.
- Support one-time reminders; minute/hour intervals; daily/weekly/monthly/yearly schedules; weekdays; selected days; multiple times per day; nth weekday of month; start/end dates; and “repeat every N minutes until done” as a separate nag rule. Reject or clarify incompatible combinations.
- Store IANA time zone, wall-clock rule, next due instant in UTC, and explicit daylight-saving policy. For nonexistent local times, move to the next valid local time; for repeated local times, fire once at the first occurrence. Display these choices in previews when relevant.
- Smart time-zone default: one-time reminders represent a fixed instant. Recurring “9 AM” reminders use the time zone known when confirmed. When the app next sees a device time-zone change, ask whether to move future wall-clock reminders to the new zone; do not silently reschedule while the app is closed. Later settings expose “follow current zone” and “fixed zone” defaults.
- Minimum interval is five minutes for the first version. If a request is more frequent, explain the limit and ask for a different interval.

## Data and operations

- `users/{uid}`: allowed account settings, preferred time zone, notification preferences.
- `users/{uid}/reminders/{id}`: title, notes, original request, normalized rule, status, version, next due instant, timestamps.
- `users/{uid}/occurrences/{id}`: reminder ID, scheduled instant, state, delivery attempt, dismissal/completion/snooze metadata, and a stable idempotency key.
- `users/{uid}/devices/{id}`: FCM registration, browser metadata, last seen time, notification permission state.
- `users/{uid}/conversations/{id}/turns/{id}`: transcript and assistant text, retained until the user deletes it. Send only bounded relevant context to the model. Provide clear-history and individual conversation deletion. Do not store raw voice by default.
- Use server-side transactions for confirmed mutations and due-occurrence claims. Every operation has a client request ID so retries cannot create duplicate reminders or snoozes. Keep a delivery ledger; collapse duplicate displayed notifications by occurrence ID. If a push fails, retry within a bounded window and leave an overdue item visible in the app.
- Schedule one UTC Cloud Scheduler job per minute. The job queries due occurrences, claims a batch, sends FCM web push, records outcome, and computes the next occurrence. Never create one Cloud Scheduler job per reminder.
- Treat snooze as a new due time for the current occurrence. It does not alter the recurrence rule. Done stops repeat-until-done nudges for that occurrence. Dismiss hides the current alert and leaves the reminder active, so the next scheduled nudge can still arrive.

## Security and privacy

- Use Google sign-in, then enforce the exact allowlisted Firebase UID in Firestore rules **and** every function. Do not rely on email text alone. Put the UID in configuration after the first sign-in and verify the Google provider.
- Keep the OpenAI API key in Google Secret Manager and call OpenAI only from functions. Never place it in PWA code or Firestore.
- Require Firebase Auth on all APIs; enable App Check on callable functions and use request validation, rate limits, audio limits, and a small spend budget. Keep Firestore rules least privilege; only server functions may write occurrence and delivery fields.
- Keep text conversation history until user deletion; send only the relevant subset to OpenAI. Avoid raw audio retention. Provide export and delete for reminder and conversation data. Full reminder text in push payloads is the user's chosen lock-screen default, with a privacy setting later.

## Delivery phases

1. **Foundation and browser check:** Git repository, TypeScript workspace, Firebase configuration for a dedicated project, PWA shell, service worker, Google sign-in, allowlist rules, and deployment configuration. Validate install and a test push on the user's Firefox for Android before relying on it for reminder delivery. No production deployment until the project and billing setup are reviewed.
2. **Deterministic reminder core:** schema, recurrence calculator, DST handling, occurrence preview, CRUD, idempotent mutations, and representative schedule fixtures.
3. **Conversation:** push-to-talk upload, transcription, structured action proposals, target resolution for edits/canceling, clarification turns, spoken readback, and mandatory confirmation.
4. **Delivery:** FCM enrollment and test push, minute scheduler, occurrence ledger, retry and stale-token handling, in-app and notification Done/Snooze actions.
5. **Polish and validation:** accessibility, offline cached viewing, settings, cost controls, export/delete, and a real-device checklist covering backgrounded Firefox, phone restart, network outage, time-zone change, DST, duplicate sends, and permission revocation.

## Acceptance criteria

- A spoken “remind me every weekday at 8:30 to take my medicine” produces the correct next five times and saves only after confirmation.
- A spoken “move that to 9” and “cancel my medicine reminder” resolve the intended reminder or ask for clarification when multiple reminders match.
- “Every five minutes until I mark it done” continues after Dismiss and stops after Done; Snooze delays only the current occurrence.
- A notification reaches the Samsung phone in a normal connected/background state; Done and Snooze update the app's state. An offline or delayed push leaves an accurate overdue record when the app reconnects.
- Unauthorized accounts cannot read, mutate, or trigger model requests for the user's reminders.

## Cost and platform choice

Firebase is the preferred choice because the user already uses Google Cloud and Firebase combines auth, hosting, storage, functions, and web push. A Cloudflare Workers/D1 alternative could also be inexpensive, but would require more custom identity and push plumbing for this personal app. Firebase Functions require Blaze billing. FCM has no charge; Firestore and Functions have no-cost allowances; a single Cloud Scheduler job is within the current three-job allowance, though build storage, secrets, network use, and OpenAI audio/text usage may incur charges. Set budget alerts and API spend limits before deployment. Recheck prices when deploying.

## Open setup inputs

- Dedicated Firebase/Google Cloud project ID and region.
- The Google account to allowlist, resolved to Firebase UID during setup.
- OpenAI API key supplied through Secret Manager, never committed.
- The user has a recent Samsung phone with current Android and Firefox. Record exact versions during the first device validation and check push and notification actions there.

## Sources checked 2026-10-11

- [Firebase PWA overview](https://firebase.google.com/docs/web/pwa)
- [Firebase scheduled functions](https://firebase.google.com/docs/functions/schedule-functions)
- [Cloud Scheduler cron and time zones](https://docs.cloud.google.com/scheduler/docs/configuring/cron-job-schedules)
- [Firebase web push setup](https://firebase.google.com/docs/cloud-messaging/web/get-started)
- [Firebase pricing](https://firebase.google.com/pricing)
- [OpenAI transcription](https://developers.openai.com/api/docs/guides/speech-to-text)
- [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Install web apps with Firefox for Android](https://support.mozilla.org/en-US/kb/use-web-apps-firefox-android)
- [Firefox for Android web push](https://support.mozilla.org/en-US/kb/push-notifications-firefox)
- [Firefox notification action support](https://bugzilla.mozilla.org/show_bug.cgi?id=1959931)
- [Web push delivery](https://web.dev/articles/push-notifications-overview)
