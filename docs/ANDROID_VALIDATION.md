# Android migration validation

Checked on 2026-11-03 with a build-only Firebase configuration (the real
Android Firebase app has not yet been registered).

| Check | Result |
| --- | --- |
| Shared, web, and Functions tests | 65 passed |
| Web TypeScript and Functions builds | Passed |
| Production PWA build | Passed (existing bundle size warning) |
| Android JVM tests | 11 passed: recurrence, DST, account isolation, local actions, and notification deduplication |
| Android debug app APK | Built |
| Compose UI test APK | Built; execution awaits an emulator or phone |
| Firebase Emulator callable contract tests | Pending Firebase emulator and Android app configuration |
| Real Samsung phone acceptance | Pending real `google-services.json`, App Check debug token, and phone access |

## Remaining acceptance run

With the real Firebase Android config, verify Google sign-in; a five-minute
reminder and the setup test push; Done and Snooze from both the app and the
notification; a spoken request and spoken readback; an offline local alert and
queued action; reconnect and account sync; a restart; and disabled PWA web push
on the same phone. Record the phone model and Android version, along with any
failure, here when the device is available.
