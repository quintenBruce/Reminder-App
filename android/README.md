# Android development

Use the existing debug build for friends. See [the setup checklist](../docs/FRIENDS_RELEASE_SETUP.md) and [friend instructions](../docs/FRIEND_INSTALL.md).

From this directory:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Keep using and privately back up `C:\Users\qcbru\.android\debug.keystore`. Its SHA fingerprints must be registered in Firebase; friends do not need their own registrations. No App Check tokens or release signing variables are needed.

Build requirements: JDK 21, Android SDK 37, and the real `app/google-services.json`. CI’s example Firebase config is for compilation only.

On this machine, JDK 21 is at `C:\Program Files\Android\Android Studio2\jbr`; the writable Gradle cache is `C:\Reminder App\android\.gradle-cache`. These paths are not dependencies of the installed app.
