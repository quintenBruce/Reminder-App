# Send Remind to friends

Use the existing **debug APK and debug signing key**. No new release key or per-friend Firebase setup is needed.

## 1. Keep your existing key

Back up `C:\Users\qcbru\.android\debug.keystore` privately. Keep using this key for updates; a different key normally requires uninstalling the old app. Do not send the keystore to friends or commit it.

Build from `android` with:

```powershell
./gradlew.bat assembleDebug
```

Send `android/app/build/outputs/apk/debug/app-debug.apk`. This is a debug build for your friend group, not a production/store build.

## 2. Check Firebase once

In project **reminder-app-a2150**:

- **Authentication → Sign-in method:** Google must be enabled.
- **Project settings → Android app (`com.qcbru.remind`):** keep your existing debug SHA-1 and SHA-256 fingerprints. They identify the signing key, not your phone. Run `./gradlew.bat signingReport` from `android` if you need to check them.
- **App Check → APIs:** turn off enforcement for Firestore and Authentication if enabled. The new app no longer sends App Check tokens.

If you change fingerprints, download the updated `google-services.json` into `android/app/` and rebuild. Keep the Google web OAuth client—it is still used by Android sign-in.

## 3. Enable friend links

Put your **debug SHA-256 fingerprint** into `hosting/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.qcbru.remind",
    "sha256_cert_fingerprints": ["YOUR_DEBUG_SHA256_FINGERPRINT"]
  }
}]
```

Use the colon-separated fingerprint from `signingReport`. This fingerprint is public and can be committed.

## 4. Deploy and test

Run the existing Cloud Build **remind-deploy** trigger on the latest code. Confirm the whole deployment succeeds, including **syncReminderDevices**. Keep your existing OpenAI secret and Firestore rules.

Check that [the hosted association file](https://reminder-app-a2150.web.app/.well-known/assetlinks.json) shows the JSON above.

Before sending widely, test with one friend:

- Install the same APK and sign in with different Google accounts.
- Add each other through an invitation link.
- Create and edit a shared reminder; check both phones update while the apps are closed.
- Check Done/snooze are personal, and test a priority alarm with fullscreen permission enabled.

If their Google sign-in is blocked, check Google Cloud → Google Auth Platform → Audience for external-account/testing restrictions. No friend should need a debug token.

## 5. Send it

Send the APK plus [these short instructions](FRIEND_INSTALL.md). If email blocks APK attachments, use a private download link instead. Updates should use the same signing key and a higher `versionCode`.

Your backend pays for everyone’s usage. The app allows **100 combined AI operations/day**, resetting at midnight UTC; voice usually uses two. Limits are in `functions/src/limits.ts`. Set a Cloud budget alert; quotas are not a hard spending cap. For automatic cleanup of old counters, enable Firestore TTL on collection group `rateLimits`, field `expiresAt`.
