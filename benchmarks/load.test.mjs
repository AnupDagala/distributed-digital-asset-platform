import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

// Protocol fixtures check driver verdicts. They are not application performance measurements.
for (const scenario of [
  { mode: 'lifecycle', terminal: 'FAILED', expected: 1 },
  { mode: 'idempotency', replay: false, expected: 1 },
  { mode: 'status', expected: 0 },
]) test('driver verdict: ' + JSON.stringify(scenario), async () => {
  const temporaryRoot = resolve(tmpdir());
  const output = await mkdtemp(join(temporaryRoot, 'asset-benchmark-test-'));
  const server = createServer((req, res) => {
    req.resume();
    req.on('end', () => setTimeout(() => {
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'POST') {
        res.statusCode = 202;
        res.setHeader('Idempotency-Replayed', String(scenario.replay ?? true));
        res.end(JSON.stringify({ id: 'fixture-asset', createdAt: '2026-01-01T00:00:00Z' }));
      } else res.end(JSON.stringify({ id: 'fixture-asset', status: scenario.terminal ?? 'COMPLETED' }));
    }, 5));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const result = await new Promise(resolve => execFile(process.execPath, [fileURLToPath(new URL('./load.mjs', import.meta.url))], {
      env: { ...process.env, BASE_URL: 'http://127.0.0.1:' + server.address().port, MODE: scenario.mode,
        DURATION_SECONDS: '1', CONCURRENCY: '1', TOKEN: 'fixture-token', BENCHMARK_OUTPUT_DIR: output }, timeout: 15000,
    }, (error, stdout, stderr) => resolve({ code: error?.code ?? 0, stdout, stderr })));
    assert.equal(result.code, scenario.expected, result.stderr);
    const report = JSON.parse(result.stdout);
    assert.equal(report.results.success, scenario.expected === 0);
    if (scenario.terminal) assert.ok(report.results.processing.FAILED > 0);
    if (scenario.replay === false) assert.ok(report.results.replayMismatches > 0);
    if (scenario.mode === 'status') assert.ok(report.results.statusReadsPerSecond > 0);
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (dirname(resolve(output)) !== temporaryRoot || !basename(output).startsWith('asset-benchmark-test-'))
      throw new Error('Unexpected fixture output path');
    await rm(output, { recursive: true, force: true });
  }
});
