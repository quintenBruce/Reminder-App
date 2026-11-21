import { writeFileSync } from 'node:fs';
const region = process.env.FUNCTIONS_REGION || 'us-south1';
if (!/^[a-z]+(?:-[a-z0-9]+)+[0-9]$/.test(region)) throw new Error('Invalid FUNCTIONS_REGION');
writeFileSync('functions/.env', `FUNCTIONS_REGION=${region}\nOPENAI_MODEL=${process.env.OPENAI_MODEL || 'gpt-6.1-sol'}\n`);
