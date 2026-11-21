# Android development

For Firebase configuration and distribution, follow [the release checklist](../docs/FRIENDS_RELEASE_SETUP.md). App Check has been removed: do not register debug tokens or enable its enforcement.

The app uses package `com.qcbru.remind`, minimum Android 8 (API 26), target SDK 37, and JDK 21 for the build. Keep the real `app/google-services.json` for live sign-in. CI uses `google-services.example.json` only for compilation; it cannot authenticate against production.

Build from this directory:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

On this Windows machine the JDK is available at `C:\Program Files\Android\Android Studio2\jbr`. The writable Gradle cache is `C:\Reminder App\android\.gradle-cache`. These are local build settings, not dependencies on those paths in the distributed app.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. A release APK uses the `REMIND_KEYSTORE`, `REMIND_STORE_PASSWORD`, `REMIND_KEY_ALIAS`, and `REMIND_KEY_PASSWORD` environment variables. Keep credentials private. Use `assembleRelease` for distribution.

Google sign-in requires the signing certificate SHA-1 and SHA-256 registered in Firebase. The same signed APK works on other phones; there is no per-phone token setup. Changing signing keys changes the fingerprints and normally requires uninstalling an existing differently signed build.

Before shipping, complete the two-account/device, background delivery, offline, reboot, and priority-permission tests in the release checklist. Automated tests do not verify OEM notification behavior.
