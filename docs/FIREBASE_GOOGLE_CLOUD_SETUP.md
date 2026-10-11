# Remind: Firebase, Google Cloud, and GitHub setup

This guide deploys the existing TypeScript PWA, Firestore rules, and Cloud Functions from GitHub. Use a **new dedicated Firebase project**. The first deployment uses a temporary owner value so you can sign in and discover your Firebase UID. The second deployment locks the app to that UID.

## 1. Have these ready

- The URL of your existing GitHub repository and its default branch (`main` or `master`). This local checkout currently has no Git remote; add the remote and push the committed source before connecting Cloud Build.
- A Google account with permission to create a Google Cloud project, attach billing, and administer the GitHub repository.
- An OpenAI API key. It goes only into Secret Manager, never into GitHub, `web/.env`, or Cloud Build substitutions.

Record these values privately as you proceed: `PROJECT_ID`, Firebase Web `apiKey`, `appId`, `messagingSenderId`, Web Push VAPID **public** key, reCAPTCHA v3 **site** key, and ultimately `OWNER_UID`. The web keys are public configuration, not server credentials.

## 2. Create the dedicated project and set billing

1. In [Firebase console](https://console.firebase.google.com/), choose **Add project**. Pick a unique project ID and save it as `PROJECT_ID`. Google Analytics is optional for this single-user app.
2. Upgrade the project to **Blaze** and attach your existing Google Cloud billing account. Functions and scheduled dispatch require billing. In [Google Cloud Billing > Budgets & alerts](https://console.cloud.google.com/billing/), create a monthly budget, for example $10, with alerts at $2 and $5. A budget alert is not a hard spending cap.
3. In Firebase **Build > Firestore Database**, create a database in **Native mode**. Choose `us-central1` if available; the functions are configured for that region. Choose the region carefully because changing the database location later requires migration. Start in production mode; the repository rules deny access except to the owner after deployment.
4. In Firebase **Build > Authentication > Sign-in method**, enable **Google** and select a project support email. In **Settings > Authorized domains**, make sure `<PROJECT_ID>.firebaseapp.com` is present.
5. In **Project settings > Your apps**, register a **Web** app. Copy `apiKey`, `appId`, `messagingSenderId`, and `authDomain`. Use `authDomain=<PROJECT_ID>.firebaseapp.com` and open the PWA at `https://<PROJECT_ID>.firebaseapp.com` for the initial Firefox sign-in. Firebase documents this same-origin arrangement for redirect sign-in in Firefox. Hosting also provides a `.web.app` URL, but it needs extra redirect-domain configuration if used for sign-in.
6. In **Project settings > Cloud Messaging > Web configuration**, create a Web Push certificate/key pair and copy its **public VAPID key**.
7. Register `https://<PROJECT_ID>.firebaseapp.com` with [reCAPTCHA v3](https://www.google.com/recaptcha/admin/create). In Firebase **Security > App Check**, register the Web app with the reCAPTCHA v3 **secret** key. Copy the corresponding **site** key for the build substitution. Keep the secret key in the reCAPTCHA/Firebase consoles. App Check is already enforced in callable function code. Wait until the phone works before enabling additional Firestore product enforcement in the App Check console.
8. In [Google Cloud Secret Manager](https://console.cloud.google.com/security/secret-manager), create a secret named exactly `OPENAI_API_KEY` with your OpenAI key as its value. The functions bind this secret at deployment. You can alternatively set it with `firebase functions:secrets:set OPENAI_API_KEY` from an authenticated CLI.

Firebase may prompt you to enable Cloud Functions, Cloud Build, Artifact Registry, Cloud Run, Cloud Scheduler, Eventarc, Firebase Rules, and Secret Manager APIs at first deployment. Enable them in this same project. The Cloud Scheduler job is created by the scheduled function; do not create a separate job manually.

## 3. Connect GitHub to Cloud Build

1. Enable the [Cloud Build API](https://console.cloud.google.com/apis/library/cloudbuild.googleapis.com) for the dedicated project.
2. In [Cloud Build > Triggers](https://console.cloud.google.com/cloud-build/triggers), choose **Connect repository**. Select **GitHub (Cloud Build GitHub App)**, authorize the app for only this repository if you prefer, and connect it. Initial connection requires GitHub repository admin permission.
3. Create a **user-managed service account** named `remind-deployer` in [IAM & Admin > Service Accounts](https://console.cloud.google.com/iam-admin/serviceaccounts). Use it as the trigger's **Service account**. Grant it, on this project, **Firebase Admin** (`roles/firebase.admin`), **API Keys Viewer** (`roles/serviceusage.apiKeysViewer`), **Cloud Functions Admin** (`roles/cloudfunctions.admin`), and **Service Account User** (`roles/iam.serviceAccountUser`). These are the roles listed by Google for Firebase deployment and function deployment. A new project can require additional build or service-agent permissions during the first 2nd generation function deploy; use the explicit failed permission in the Cloud Build log to add only the needed role. Avoid a downloaded JSON service-account key.
4. Create a trigger named `remind-deploy` with **Push to a branch**, select the connected repo, set the branch pattern to `^main$` or `^master$` to match its actual default branch, and set **Cloud Build configuration file** to `cloudbuild.yaml`. Choose **Cloud Logging only** if asked for a log destination with the custom service account.
5. In the trigger's **Substitution variables**, enter the following. For the first build, use `_OWNER_UID=BOOTSTRAP`. That deliberately permits no real user in the app or Firestore rules.

   | Substitution | Value |
   | --- | --- |
   | `_OWNER_UID` | `BOOTSTRAP` initially; real Firebase UID after first sign-in |
   | `_FIREBASE_API_KEY` | Web app `apiKey` |
   | `_FIREBASE_AUTH_DOMAIN` | `<PROJECT_ID>.firebaseapp.com` |
   | `_FIREBASE_APP_ID` | Web app `appId` |
   | `_FIREBASE_MESSAGING_SENDER_ID` | Web app `messagingSenderId` |
   | `_FIREBASE_VAPID_KEY` | Web Push **public** VAPID key |
   | `_RECAPTCHA_SITE_KEY` | reCAPTCHA v3 **site** key |

   `VITE_FIREBASE_PROJECT_ID` comes from the Cloud Build `$PROJECT_ID` built-in variable. The pipeline validates all values, installs from the committed lockfile, runs tests, checks types, builds both packages, then deploys Hosting, rules, and functions. It generates ignored deployment files so the UID is never hard-coded in tracked rules. The pipeline never receives the OpenAI key.

## 4. Bootstrap the owner UID

1. Push the committed source to the connected GitHub default branch. Watch the `remind-deploy` run in Cloud Build. Resolve any explicit first-deploy API or IAM prompts, then rerun the trigger. Do not proceed until the deployment succeeds.
2. On your Samsung phone or desktop Firefox, open `https://<PROJECT_ID>.firebaseapp.com` and use **Continue with Google** with your intended account. The app will say that the account is not allowlisted; this is expected during bootstrap.
3. In Firebase **Authentication > Users**, find that Google user and copy its exact **User UID**.
4. Edit the trigger substitution `_OWNER_UID` to the real UID. Run the trigger manually on the same committed revision or push another commit. After it succeeds, reload the app. It should open the signed-in interface. Sign in with another account only as a negative access check.

## 5. Phone validation

On the Samsung phone, use current Firefox for Android, open the `.firebaseapp.com` URL, then use Firefox's **Install** or **Add to Home screen** menu. In app **Settings**, choose **Enable & test** and allow notifications. Allow microphone access when prompted. Check a typed reminder, a spoken reminder, a voice edit, the confirmation readback and next dates, a five-minute repeat-until-Done reminder, Dismiss, Snooze, Done, and a completed item in History. Check foreground, background, closed tab, phone restart, and temporary offline recovery. Record the actual phone/Android/Firefox versions while testing. Browser delivery is best effort; Samsung battery settings may delay background pushes.

If sign-in loops in Firefox, verify you opened `.firebaseapp.com`, not `.web.app`, and that Firebase Authentication lists that domain as authorized. If `Enable & test` fails, check browser notification permission, App Check registration, Web Push VAPID key, and the `registerDevice` function log. If Cloud Build fails, read the first failed step and its exact permission or validation error before changing roles or configuration.

## Local verification and deployment files

The local commands are `pnpm install --frozen-lockfile`, `pnpm test`, `pnpm run typecheck:web`, `pnpm run build:functions`, and `pnpm run build:web`. The root pnpm lockfile pins local and Cloud Build dependencies; `functions/package-lock.json` pins the separate dependency install done by the Cloud Functions Node buildpack. `scripts/prepare-deploy.mjs` needs the variables shown in `cloudbuild.yaml`; it generates ignored `.firebase-deploy.json`, `firestore.deploy.rules`, and `functions/.env`. The checked-in `firestore.rules` keeps its deny-all placeholder, so a missing owner variable cannot accidentally open access. `firebase.json` has no build hook; always build before deploying manually.

## References

- [Firebase Web setup](https://firebase.google.com/docs/web/setup), [Google sign-in](https://firebase.google.com/docs/auth/web/google-signin), [redirect sign-in in Firefox](https://firebase.google.com/docs/auth/web/redirect-best-practices)
- [App Check reCAPTCHA v3](https://firebase.google.com/docs/app-check/web/recaptcha-provider), [Web Push setup](https://firebase.google.com/docs/cloud-messaging/web/get-started), [Functions secrets](https://firebase.google.com/docs/functions/config-env)
- [Cloud Build to Firebase](https://docs.cloud.google.com/build/docs/deploying-builds/deploy-firebase), [GitHub triggers](https://docs.cloud.google.com/build/docs/automating-builds/github/build-repos-from-github), [user-managed build accounts](https://docs.cloud.google.com/build/docs/securing-builds/configure-user-specified-service-accounts), [Firebase function IAM](https://firebase.google.com/docs/projects/iam/permissions)
