import { randomBytes, randomUUID } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { cpus, totalmem, platform, arch } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
const base = process.env.BASE_URL ?? 'http://localhost:8080';
const concurrency = Number(process.env.CONCURRENCY ?? 5), duration = Number(process.env.DURATION_SECONDS ?? 30);
const mode = process.env.MODE ?? 'upload';
if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 100 || !Number.isInteger(duration) || duration < 1 || duration > 300 || !['upload', 'lifecycle'].includes(mode)) throw new Error('Invalid benchmark parameters');
const fixture = await readFile(new URL('./fixtures/sample.png', import.meta.url));
let token = process.env.TOKEN;
if (!token) {
  const response = await fetch(base + '/api/v1/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: 'benchmark-' + randomUUID() + '@example.test', password: randomBytes(24).toString('base64url') }), signal: AbortSignal.timeout(15000) });
  if (response.status !== 201) throw new Error('Benchmark account registration failed: HTTP ' + response.status);
  token = (await response.json()).accessToken;
}
const headers = { Authorization: 'Bearer ' + token }, counts = {}, processing = {}, samples = [], lifecycle = [];
let requests = 0, errors = 0;
const started = performance.now(), deadline = started + duration * 1000;
async function worker() {
  while (performance.now() < deadline) {
    const form = new FormData(); form.append('file', new Blob([fixture], { type: 'image/png' }), 'sample.png');
    const begin = performance.now();
    try {
      const response = await fetch(base + '/api/v1/assets', { method: 'POST', headers: { ...headers, 'Idempotency-Key': randomUUID() }, body: form, signal: AbortSignal.timeout(30000) });
      requests++; counts[response.status] = (counts[response.status] ?? 0) + 1;
      if (samples.length < 100000) samples.push(performance.now() - begin);
      if (response.status === 202 && mode === 'lifecycle') {
        const { id } = await response.json();
        for (let attempt = 0; attempt < 30; attempt++) {
          const statusResponse = await fetch(base + '/api/v1/assets/' + id + '/status', { headers, signal: AbortSignal.timeout(15000) });
          if (!statusResponse.ok) { processing['http_' + statusResponse.status] = (processing['http_' + statusResponse.status] ?? 0) + 1; break; }
          const { status } = await statusResponse.json();
          if (['COMPLETED', 'DUPLICATE', 'FAILED', 'REJECTED'].includes(status)) { processing[status] = (processing[status] ?? 0) + 1; if (lifecycle.length < 100000) lifecycle.push(performance.now() - begin); break; }
          if (attempt === 29) processing.timeout = (processing.timeout ?? 0) + 1;
          await delay(1000);
        }
      } else await response.arrayBuffer();
      if (response.status === 429) await delay(1000);
    } catch { errors++; }
  }
}
await Promise.all(Array.from({ length: concurrency }, worker));
const elapsed = (performance.now() - started) / 1000;
function percentiles(values) { values.sort((a,b) => a-b); const pick = p => values.length ? values[Math.min(values.length-1, Math.floor(p*values.length))] : null; return { samples: values.length, p50: pick(0.5), p95: pick(0.95), p99: pick(0.99) }; }
const report = { measuredAt: new Date().toISOString(), environment: { platform: platform(), arch: arch(), cpu: cpus()[0]?.model, logicalCpus: cpus().length, ramBytes: totalmem(), node: process.version },
  configuration: { base, concurrency, requestedDurationSeconds: duration, mode, fixtureBytes: fixture.length, fixture: '64x64 PNG; same content, unique request keys' },
  results: { elapsedSeconds: elapsed, uploadResponses: requests, networkErrors: errors, httpStatuses: counts, uploadResponsesPerSecond: requests / elapsed, uploadLatencyMs: percentiles(samples), processing, lifecycleLatencyMs: percentiles(lifecycle) } };
await mkdir(new URL('./results/', import.meta.url), { recursive: true });
const output = new URL('./results/' + Date.now() + '.json', import.meta.url);
await writeFile(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (errors || Object.keys(counts).some(code => code !== '202')) process.exitCode = 1;
