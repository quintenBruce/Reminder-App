import { readFileSync, writeFileSync } from 'node:fs';

const required = [
  'OWNER_UID', 'VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_AUTH_DOMAIN',
  'VITE_FIREBASE_PROJECT_ID', 'VITE_FIREBASE_APP_ID',
  'VITE_FIREBASE_MESSAGING_SENDER_ID', 'VITE_FIREBASE_VAPID_KEY',
  'VITE_RECAPTCHA_SITE_KEY',
];
const missing = required.filter(name => !process.env[name]?.trim());
if (missing.length) throw new Error(`Missing deploy settings: ${missing.join(', ')}`);

const uid = process.env.OWNER_UID.trim();
if (!/^[A-Za-z0-9_-]{1,128}$/.test(uid)) throw new Error('OWNER_UID is not a valid Firebase UID.');
if (process.env.VITE_OWNER_UID !== uid) throw new Error('VITE_OWNER_UID must equal OWNER_UID.');
if (process.env.GOOGLE_CLOUD_PROJECT &&
    process.env.VITE_FIREBASE_PROJECT_ID !== process.env.GOOGLE_CLOUD_PROJECT) {
  throw new Error('The web Firebase project ID differs from the deployment project.');
}

const rules = readFileSync('firestore.rules', 'utf8');
const placeholder = 'REPLACE_WITH_OWNER_UID';
if (!rules.includes(placeholder)) throw new Error('Firestore owner placeholder was not found.');
writeFileSync('firestore.deploy.rules', rules.replaceAll(placeholder, uid));

const config = JSON.parse(readFileSync('firebase.json', 'utf8'));
config.firestore.rules = 'firestore.deploy.rules';
writeFileSync('.firebase-deploy.json', JSON.stringify(config, null, 2) + '\n');
writeFileSync('functions/.env', `OWNER_UID=${uid}\nOPENAI_MODEL=${process.env.OPENAI_MODEL || 'gpt-6.1-sol'}\n`);
console.log('Generated ignored Firebase deployment files.');
