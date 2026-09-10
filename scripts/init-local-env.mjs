import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

const contents = [
  'DATABASE_USER=assets',
  'DATABASE_PASSWORD=' + randomBytes(32).toString('hex'),
  'JWT_SECRET=' + randomBytes(32).toString('base64'),
  'REDIS_PASSWORD=' + randomBytes(32).toString('hex'),
].join('\n') + '\n';
await writeFile('.env', contents, { flag: 'wx', mode: 0o600 });
console.log('Created .env with fresh local secrets. Existing files are never overwritten.');
