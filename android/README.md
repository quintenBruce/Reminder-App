# Remind for Android

Open this directory in Android Studio. The project uses Kotlin, Jetpack Compose, Material 3,
Android API 37, AGP 9.5.0, Gradle 9.6.0, and the existing Firebase backend in `us-south1`.

## Firebase setup required for a usable install

These instructions use this machine's existing Android Studio debug signing key at
`C:\Users\qcbru\.android\debug.keystore`. If that key is replaced or the app is
built on another machine, rerun `./gradlew signingReport` from `android/` and use
the new debug fingerprints instead.

1. Open [Firebase Console](https://console.firebase.google.com/project/reminder-app-a2150/overview)
   and verify the selected project ID is `reminder-app-a2150`. On **Project overview**,
   select **Add app** (or the Android icon), then select **Android**. If an Android
   app with package `com.qcbru.remind` is already listed under **Project settings**
   > **General** > **Your apps**, open that app instead of adding a duplicate.
2. Set **Android package name** to `com.qcbru.remind` exactly. An optional nickname
   is `Remind Android`. Enter this machine's debug **SHA-1** if the registration form
   asks for it, then select **Register app**:

   ```text
   F8:65:8B:16:EE:78:48:5C:AA:DF:34:39:6C:58:BA:F4:6E:6A:AF:04
   ```

3. Open the gear icon > **Project settings** > **General** > **Your apps** > the
   `com.qcbru.remind` Android app. Under **SHA certificate fingerprints**, use
   **Add fingerprint** until both values are present. Add **SHA-256**:

   ```text
   53:17:7A:FA:28:EE:6E:91:10:94:64:06:82:50:DB:32:2E:6D:A3:E8:93:91:8A:CB:27:C9:48:13:F4:14:D8:35
   ```

4. In **Build** > **Authentication** > **Sign-in method**, verify that **Google** is
   enabled. If it is disabled, enable it, choose a project support email, and save.
   This may already be enabled for the PWA.
5. Return to **Project settings** > **General** > **Your apps** > the Android app and
   download the updated **google-services.json** after adding the fingerprints and
   enabling Google sign-in. Copy it to the exact path
   `C:\Reminder App\android\app\google-services.json`. Check that Windows has not
   named it `google-services (1).json` or `google-services.json.txt`. The file should
   contain `com.qcbru.remind` as its Android client package. The committed
   `google-services.example.json` is a build-only placeholder; do not rename it.
   The project already includes the Google services Gradle plugin and Firebase SDKs,
   so the console's Gradle setup snippets are unnecessary.
6. In **Security** > **App Check** > **Apps**, register this Android app with the
   **Play Integrity** provider, using the SHA-256 above if prompted. The debug build
   uses Firebase's debug App Check provider. Open `android/` in Android Studio and
   run the app once on an emulator or phone. In **Logcat**, filter for
   `DebugAppCheckProvider`; copy the UUID shown after **Enter this debug secret into
   the allow list in the Firebase Console**. In Firebase Console, go to **Security**
   > **App Check** > **Apps** > the Android app's three-dot menu > **Manage debug
   tokens** and add that UUID. Keep this token private: do not put it in Git or a
   support message. The app's callable Functions require App Check.
7. Run the app again and sign in with Google. Grant notification permission when
   prompted. In the app's Settings, allow **Alarms & reminders** for accurate local
   fallback and use **Enable & test** to check native alerts. The app schedules
   inexact alarms if access is denied.
8. Once native alerts work, on that phone open the PWA's Settings and disable
   **Web notifications** to avoid duplicate phone alerts. The PWA remains available
   for viewing and editing.

`google-services.json` contains Firebase app identifiers and should be committed
with the Android client; keep the App Check debug token and signing key private.
Local SDK settings and generated files are ignored by Git. Only use
`google-services.example.json` to compile or run automated tests that do
not contact Firebase. Replace it with the real config before installing on a phone.

## Checks

From the repository root run `pnpm test`, `pnpm run typecheck:web`,
`pnpm run build:functions`, and `pnpm run build:web`. From `android/` run
`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` after adding
`google-services.json`. The unit suite checks recurrence parity, DST, local state,
account isolation, action persistence, and alert deduplication. The Android test
APK contains Compose UI tests; running it needs an emulator or phone.

The remaining phone acceptance check is sign-in, one pushed alert with Done and
Snooze, one offline local alert, and rescheduling after a restart. Record the phone
model and Android version when performing it. The local fallback can only schedule
reminders that have already synced to that phone.
