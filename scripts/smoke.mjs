import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
const base = process.env.BASE_URL ?? 'http://localhost:8080';
const image = await readFile(new URL('../benchmarks/fixtures/sample.png', import.meta.url));
async function call(method, path, { token, body, headers = {}, expected = 200 } = {}) {
  const response = await fetch(base + path, { method, headers: { ...headers, ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, expected, method + ' ' + path + ' returned an unexpected HTTP status');
  return response.status === 204 ? null : response.json();
}
async function account() {
  const result = await call('POST', '/api/v1/auth/register', { expected: 201,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'smoke-' + randomUUID() + '@example.test', password: randomBytes(24).toString('base64url') }) });
  return result.accessToken;
}
function file() { const form = new FormData(); form.append('file', new Blob([image], { type: 'image/png' }), 'sample.png'); return form; }
async function terminal(token, id) {
  for (let i = 0; i < 30; i++) {
    const result = await call('GET', '/api/v1/assets/' + id + '/status', { token });
    if (['COMPLETED', 'DUPLICATE', 'FAILED', 'REJECTED'].includes(result.status)) return result.status;
    await delay(1000);
  }
  throw new Error('Processing did not reach a terminal state within the smoke-test deadline');
}
await call('GET', '/actuator/health');
const token = await account(), stranger = await account(), key = randomUUID();
const created = await call('POST', '/api/v1/assets', { token, body: file(), headers: { 'Idempotency-Key': key }, expected: 202 });
const replay = await call('POST', '/api/v1/assets', { token, body: file(), headers: { 'Idempotency-Key': key }, expected: 202 });
assert.deepEqual(replay, created);
assert.equal(await terminal(token, created.id), 'COMPLETED');
const duplicate = await call('POST', '/api/v1/assets', { token, body: file(), expected: 202 });
assert.equal(await terminal(token, duplicate.id), 'DUPLICATE');
await call('GET', '/api/v1/assets/' + created.id, { token: stranger, expected: 404 });
const stream = await fetch(base + '/api/v1/assets/' + created.id + '/events', { headers: { Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(15000) });
assert.equal(stream.status, 200); assert.match(await stream.text(), /COMPLETED/);
for (const id of [created.id, duplicate.id]) await call('DELETE', '/api/v1/assets/' + id, { token, expected: 204 });
console.log('Smoke passed: health, auth, upload, idempotency, processing, deduplication, ownership, SSE and deletion.');
