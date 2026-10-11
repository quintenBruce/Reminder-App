import { writeFileSync } from 'node:fs';

const required = [
  'FUNCTIONS_REGION', 'VITE_FIREBASE_FUNCTIONS_REGION',
  'VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_AUTH_DOMAIN',
  'VITE_FIREBASE_PROJECT_ID', 'VITE_FIREBASE_APP_ID',
  'VITE_FIREBASE_MESSAGING_SENDER_ID', 'VITE_FIREBASE_VAPID_KEY',
  'VITE_RECAPTCHA_SITE_KEY',
];
const missing = required.filter(name => !process.env[name]?.trim());
if (missing.length) throw new Error(`Missing deploy settings: ${missing.join(', ')}`);

if (process.env.VITE_FIREBASE_FUNCTIONS_REGION !== process.env.FUNCTIONS_REGION) {
  throw new Error('The web and server Functions regions must match.');
}
if (!/^[a-z]+(?:-[a-z0-9]+)+[0-9]$/.test(process.env.FUNCTIONS_REGION)) {
  throw new Error('FUNCTIONS_REGION is not a valid region ID.');
}
if (process.env.GOOGLE_CLOUD_PROJECT &&
    process.env.VITE_FIREBASE_PROJECT_ID !== process.env.GOOGLE_CLOUD_PROJECT) {
  throw new Error('The web Firebase project ID differs from the deployment project.');
}

writeFileSync('functions/.env', `FUNCTIONS_REGION=${process.env.FUNCTIONS_REGION}\nOPENAI_MODEL=${process.env.OPENAI_MODEL || 'gpt-6.1-sol'}\n`);
console.log('Generated Functions environment file.');
