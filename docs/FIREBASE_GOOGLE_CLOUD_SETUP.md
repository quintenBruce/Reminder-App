# Remind: Firebase, Google Cloud, and GitHub setup

This guide deploys the TypeScript PWA, Firestore rules, and Cloud Functions from GitHub to Firebase project `reminder-app-a2150`. Any verified Google account can sign in; each account has a private reminder list and conversation history. One successful deployment is enough to start using the app.

## 1. Have these ready

- The [existing GitHub repository](https://github.com/quintenBruce/Reminder-App) and its default branch (`main` or `master`).
- A Google account with permission to create a Google Cloud project, attach billing, and administer the GitHub repository.
- An OpenAI API key. It goes only into Secret Manager, never into GitHub, `web/.env`, or Cloud Build substitutions.

The Firebase Web config you supplied is already recorded in `cloudbuild.yaml` and `web/.env.example`: project ID, API key, auth domain, app ID, and messaging sender ID. These are public client configuration, not server credentials. You still need the Web Push VAPID **public** key and reCAPTCHA v3 **site** key. The app does not currently use Firebase Storage or Analytics, so `storageBucket` and `measurementId` are not needed.

## 2. Create the dedicated project and set billing

1. In [Firebase console](https://console.firebase.google.com/), open the project you created: `reminder-app-a2150`. Google Analytics is optional for this app.
2. Upgrade the project to **Blaze** and attach your existing Google Cloud billing account. Functions and scheduled dispatch require billing. In [Google Cloud Billing > Budgets & alerts](https://console.cloud.google.com/billing/), create a monthly budget, for example $10, with alerts at $2 and $5. A budget alert is not a hard spending cap.
3. In Firebase **Build > Firestore Database**, create the **default** database in **Native mode** at `us-south1` (Dallas), the region you selected. Keep the Cloud Build `_FUNCTIONS_REGION` substitution at `us-south1` so the callable and scheduled functions are colocated with Firestore. Changing the database location later requires migration. Start in production mode; the repository rules isolate accounts after deployment.
4. In Firebase **Build > Authentication > Sign-in method**, enable **Google** and select a project support email. In **Settings > Authorized domains**, make sure `reminder-app-a2150.firebaseapp.com` is present.
5. The Web app is already registered, and its config is in the repository. Open the app at **https://reminder-app-a2150.firebaseapp.com** for normal use, including the first sign-in. This canonical URL matches `authDomain`, which supports Firebase redirect sign-in in Firefox. The `.web.app` URL needs additional redirect-domain configuration if you later choose to use it for sign-in. There is no special first-time URL or UID setup.
6. In **Project settings > Cloud Messaging > Web configuration**, create a Web Push certificate/key pair and copy its **public VAPID key**.
7. Register `https://reminder-app-a2150.firebaseapp.com` with [reCAPTCHA v3](https://www.google.com/recaptcha/admin/create). In Firebase **Security > App Check**, register the Web app with the reCAPTCHA v3 **secret** key. Copy the corresponding **site** key for the build substitution. Keep the secret key in the reCAPTCHA/Firebase consoles. App Check is already enforced in callable function code. Wait until the phone works before enabling additional Firestore product enforcement in the App Check console.
8. In [Google Cloud Secret Manager](https://console.cloud.google.com/security/secret-manager), create a secret named exactly `OPENAI_API_KEY` with your OpenAI key as its value. The functions bind this secret at deployment. You can alternatively set it with `firebase functions:secrets:set OPENAI_API_KEY` from an authenticated CLI.

Firebase may prompt you to enable Cloud Functions, Cloud Build, Artifact Registry, Cloud Run, Cloud Scheduler, Eventarc, Firebase Rules, and Secret Manager APIs at first deployment. Enable them in this same project. The Cloud Scheduler job is created by the scheduled function; do not create a separate job manually.

## 3. Connect GitHub to Cloud Build

1. Enable the [Cloud Build API](https://console.cloud.google.com/apis/library/cloudbuild.googleapis.com) for the dedicated project.
2. In [Cloud Build > Triggers](https://console.cloud.google.com/cloud-build/triggers), choose **Connect repository**. Select **GitHub (Cloud Build GitHub App)**, authorize the app for only this repository if you prefer, and connect it. Initial connection requires GitHub repository admin permission.
3. Create a **user-managed service account** named `remind-deployer` in [IAM & Admin > Service Accounts](https://console.cloud.google.com/iam-admin/serviceaccounts). Use it as the trigger's **Service account**. Grant it, on this project, **Firebase Admin** (`roles/firebase.admin`), **API Keys Viewer** (`roles/serviceusage.apiKeysViewer`), **Cloud Functions Admin** (`roles/cloudfunctions.admin`), **Service Account User** (`roles/iam.serviceAccountUser`), and **Logs Writer** (`roles/logging.logWriter`) for the configured Cloud Logging destination. Google lists the first four for Firebase and function deployment; Cloud Build requires Logs Writer for a user-managed account writing build logs to Cloud Logging. A new project can require additional build or service-agent permissions during the first 2nd generation function deploy; use the explicit failed permission in the Cloud Build log to add only the needed role. Avoid a downloaded JSON service-account key.
4. Create a trigger named `remind-deploy` with **Push to a branch**, select the connected repo, set the branch pattern to `^main$` or `^master$` to match its actual default branch, and set **Cloud Build configuration file** to `cloudbuild.yaml`. Choose **Cloud Logging only** if asked for a log destination with the custom service account.
5. In the trigger's **Substitution variables**, set the two still-missing public keys below. The other values are already defaults in `cloudbuild.yaml` and can be overridden in the trigger if the Firebase project changes.

   | Substitution | Value |
   | --- | --- |
   | `_FUNCTIONS_REGION` | `us-south1` (Dallas; already the pipeline default) |
   | `_FIREBASE_API_KEY` | Already set from the supplied Web config |
   | `_FIREBASE_AUTH_DOMAIN` | `reminder-app-a2150.firebaseapp.com` (already set) |
   | `_FIREBASE_APP_ID` | Already set from the supplied Web config |
   | `_FIREBASE_MESSAGING_SENDER_ID` | Already set from the supplied Web config |
   | `_FIREBASE_VAPID_KEY` | Web Push **public** VAPID key |
   | `_RECAPTCHA_SITE_KEY` | reCAPTCHA v3 **site** key |

   `VITE_FIREBASE_PROJECT_ID` comes from the Cloud Build `$PROJECT_ID` built-in variable; create the trigger in `reminder-app-a2150`. The pipeline validates values, installs from the committed lockfile, runs tests, checks types, builds both packages, then deploys Hosting, rules, and functions. The pipeline never receives the OpenAI key.

## 4. Deploy and sign in

1. Push the committed source to the connected GitHub default branch. Watch the `remind-deploy` run in Cloud Build. Resolve any explicit first-deploy API or IAM prompts, then rerun the trigger. Wait until the deployment succeeds.
2. On your Samsung phone or desktop Firefox, open [https://reminder-app-a2150.firebaseapp.com](https://reminder-app-a2150.firebaseapp.com) and use **Continue with Google**. Any verified Google account can sign up. Every account receives its own `users/{uid}` data in Firestore; account A cannot read or change account B's reminders or conversations through the app.
3. When switching accounts on one phone, use **Settings > Sign out**. The app unregisters that account's push token before signing out. Each account must use **Enable & test** after signing in to receive its own notifications. If sign-out cannot reach the server, retry it while online so the old account's push registration can be removed.

Open signup means others who know the URL can use the app and consume project resources. Callable functions require Google sign-in and App Check, with an app-wide limit of 30 transcription and 30 interpretation requests per UTC day. Set a Google Cloud budget alert and review usage; the alert is not a hard spending cap.

## 5. Phone validation

On the Samsung phone, use current Firefox for Android, open the `.firebaseapp.com` URL, then use Firefox's **Install** or **Add to Home screen** menu. In app **Settings**, choose **Enable & test** and allow notifications. Allow microphone access when prompted. Check a typed reminder, a spoken reminder, a voice edit, the confirmation readback and next dates, a five-minute repeat-until-Done reminder, Dismiss, Snooze, Done, and a completed item in History. Sign out and use a second Google account; verify that its reminders and conversation start empty, and that its test notification is registered separately. Check foreground, background, closed tab, phone restart, and temporary offline recovery. Record the actual phone/Android/Firefox versions while testing. Browser delivery is best effort; Samsung battery settings may delay background pushes.

If sign-in loops in Firefox, verify you opened `.firebaseapp.com`, not `.web.app`, and that Firebase Authentication lists that domain as authorized. If `Enable & test` fails, check browser notification permission, App Check registration, Web Push VAPID key, and the `registerDevice` function log. If Cloud Build fails, read the first failed step and its exact permission or validation error before changing roles or configuration.

## Local verification and deployment files

The Cloud Build pipeline runs `pnpm install --frozen-lockfile`, `pnpm test`, `pnpm run typecheck:web`, `pnpm run build:functions`, and `pnpm run build:web`. The root pnpm lockfile pins local and Cloud Build dependencies; `functions/package-lock.json` pins the separate dependency install done by the Cloud Functions Node buildpack. `scripts/prepare-deploy.mjs` validates required settings and generates the ignored `functions/.env` file. Firestore rules permit each verified Google account to read only its own `users/{uid}` subtree; browser writes are denied. `firebase.json` has no build hook; always build before deploying manually.

## References

- [Firebase Web setup](https://firebase.google.com/docs/web/setup), [Google sign-in](https://firebase.google.com/docs/auth/web/google-signin), [redirect sign-in in Firefox](https://firebase.google.com/docs/auth/web/redirect-best-practices)
- [App Check reCAPTCHA v3](https://firebase.google.com/docs/app-check/web/recaptcha-provider), [Web Push setup](https://firebase.google.com/docs/cloud-messaging/web/get-started), [Functions secrets](https://firebase.google.com/docs/functions/config-env)
- [Cloud Build to Firebase](https://docs.cloud.google.com/build/docs/deploying-builds/deploy-firebase), [GitHub triggers](https://docs.cloud.google.com/build/docs/automating-builds/github/build-repos-from-github), [user-managed build accounts](https://docs.cloud.google.com/build/docs/securing-builds/configure-user-specified-service-accounts), [Firebase function IAM](https://firebase.google.com/docs/projects/iam/permissions)
