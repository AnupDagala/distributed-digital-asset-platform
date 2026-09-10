import { randomBytes, randomUUID } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { cpus, totalmem, platform, arch } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { resolve } from 'node:path';
const base = process.env.BASE_URL ?? 'http://localhost:8080';
const endpoint = new URL(base);
if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.search || endpoint.hash)
  throw new Error('BASE_URL must be HTTP(S) without credentials, query or fragment');
const concurrency = Number(process.env.CONCURRENCY ?? 5), duration = Number(process.env.DURATION_SECONDS ?? 30);
const mode = process.env.MODE ?? 'upload';
if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 100 || !Number.isInteger(duration) || duration < 1 || duration > 300 || !['upload', 'lifecycle', 'idempotency', 'status'].includes(mode)) throw new Error('Invalid benchmark parameters');
const fixture = await readFile(new URL('./fixtures/sample.png', import.meta.url));
let token = process.env.TOKEN;
if (!token) {
  const response = await fetch(base + '/api/v1/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: 'benchmark-' + randomUUID() + '@example.test', password: randomBytes(24).toString('base64url') }), signal: AbortSignal.timeout(15000) });
  if (response.status !== 201) throw new Error('Benchmark account registration failed: HTTP ' + response.status);
  token = (await response.json()).accessToken;
}
const headers = { Authorization: 'Bearer ' + token }, counts = {}, statusCounts = {}, processing = {}, samples = [], successfulSamples = [], statusSamples = [], lifecycle = [];
let requests = 0, errors = 0, replayMismatches = 0;
const replayKey = randomUUID();
let seed;
if (['idempotency', 'status'].includes(mode)) {
  const form = new FormData(); form.append('file', new Blob([fixture], { type: 'image/png' }), 'sample.png');
  const response = await fetch(base + '/api/v1/assets', { method: 'POST', headers: { ...headers, 'Idempotency-Key': replayKey }, body: form, signal: AbortSignal.timeout(30000) });
  if (response.status !== 202) throw new Error('Benchmark seed failed: HTTP ' + response.status);
  seed = await response.json();
  if (!seed.id) throw new Error('Benchmark seed response is invalid');
}
async function readStatus(id) {
  const begin = performance.now();
  const response = await fetch(base + '/api/v1/assets/' + id + '/status', { headers, signal: AbortSignal.timeout(15000) });
  const body = await response.text();
  statusCounts[response.status] = (statusCounts[response.status] ?? 0) + 1;
  if (statusSamples.length < 100000) statusSamples.push(performance.now() - begin);
  const data = response.ok ? JSON.parse(body) : null;
  if (response.ok && (!data?.status || data.id !== id)) throw new Error('Invalid status response');
  return { code: response.status, data };
}
const started = performance.now(), deadline = started + duration * 1000;
async function worker() {
  while (performance.now() < deadline) {
    const begin = performance.now();
    try {
      if (mode === 'status') {
        const result = await readStatus(seed.id);
        if (result.code === 429) await delay(1000);
        continue;
      }
      const form = new FormData(); form.append('file', new Blob([fixture], { type: 'image/png' }), 'sample.png');
      const response = await fetch(base + '/api/v1/assets', { method: 'POST', headers: { ...headers, 'Idempotency-Key': mode === 'idempotency' ? replayKey : randomUUID() }, body: form, signal: AbortSignal.timeout(30000) });
      const responseText = await response.text();
      requests++; counts[response.status] = (counts[response.status] ?? 0) + 1;
      if (samples.length < 100000) samples.push(performance.now() - begin);
      const receipt = response.status === 202 ? JSON.parse(responseText) : null;
      if (response.status === 202 && !receipt?.id) throw new Error('Invalid upload response');
      if (response.status === 202 && successfulSamples.length < 100000) successfulSamples.push(performance.now() - begin);
      if (response.status === 202 && mode === 'idempotency' &&
          (response.headers.get('Idempotency-Replayed') !== 'true' || receipt.id !== seed.id || receipt.createdAt !== seed.createdAt)) replayMismatches++;
      if (response.status === 202 && mode === 'lifecycle') {
        const { id } = receipt;
        for (let attempt = 0; attempt < 30; attempt++) {
          const result = await readStatus(id);
          if (result.code !== 200) { processing['http_' + result.code] = (processing['http_' + result.code] ?? 0) + 1; break; }
          const { status } = result.data;
          if (['COMPLETED', 'DUPLICATE', 'FAILED', 'REJECTED'].includes(status)) { processing[status] = (processing[status] ?? 0) + 1; if (lifecycle.length < 100000) lifecycle.push(performance.now() - begin); break; }
          if (attempt === 29) processing.timeout = (processing.timeout ?? 0) + 1;
          await delay(1000);
        }
      }
      if (response.status === 429) await delay(1000);
    } catch { errors++; }
  }
}
await Promise.all(Array.from({ length: concurrency }, worker));
const elapsed = (performance.now() - started) / 1000;
function percentiles(values) { values.sort((a,b) => a-b); const pick = p => values.length ? values[Math.min(values.length-1, Math.ceil(p*values.length)-1)] : null; return { samples: values.length, p50: pick(0.5), p95: pick(0.95), p99: pick(0.99) }; }
const success = !(errors || replayMismatches || Object.keys(counts).some(code => code !== '202') ||
  Object.keys(statusCounts).some(code => code !== '200') || Object.keys(processing).some(state => !['COMPLETED', 'DUPLICATE'].includes(state)));
const report = { measuredAt: new Date().toISOString(), environment: { platform: platform(), arch: arch(), cpu: cpus()[0]?.model, logicalCpus: cpus().length, ramBytes: totalmem(), node: process.version },
  configuration: { base, concurrency, requestedDurationSeconds: duration, mode, fixtureBytes: fixture.length, fixture: '64x64 PNG; same content; unique request keys except idempotency mode' },
  results: { success, elapsedSeconds: elapsed, uploadResponses: requests, requestErrors: errors, replayMismatches, httpStatuses: counts,
    statusHttpStatuses: statusCounts, uploadResponsesPerSecond: requests / elapsed, acceptedUploadResponsesPerSecond: (counts['202'] ?? 0) / elapsed,
    statusReadsPerSecond: (statusCounts['200'] ?? 0) / elapsed,
    observedSuccessfulCompletionsPerSecond: ((processing.COMPLETED ?? 0) + (processing.DUPLICATE ?? 0)) / elapsed,
    uploadLatencyMs: percentiles(samples), successfulUploadLatencyMs: percentiles(successfulSamples), statusReadLatencyMs: percentiles(statusSamples),
    processing, lifecycleLatencyMs: percentiles(lifecycle) } };
const outputDirectory = process.env.BENCHMARK_OUTPUT_DIR ? resolve(process.env.BENCHMARK_OUTPUT_DIR) : new URL('./results/', import.meta.url);
await mkdir(outputDirectory, { recursive: true });
const output = typeof outputDirectory === 'string' ? resolve(outputDirectory, Date.now() + '.json') : new URL(Date.now() + '.json', outputDirectory);
await writeFile(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (!success) process.exitCode = 1;
