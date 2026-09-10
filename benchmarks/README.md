# Reproducible HTTP load test

This driver has not been run against the full Compose stack in the implementation environment. There are no measured throughput claims or committed result numbers.

Requirements: a running application with PostgreSQL, Kafka, Redis and object storage; Node.js 20+. Use a dedicated local benchmark environment with enough free disk. The test creates an account unless TOKEN is supplied, and leaves its accepted assets for later inspection. Do not use a production token or a database containing real user data.

From the repository root:

```sh
node benchmarks/load.mjs
```

Defaults: 5 concurrent clients, 30 seconds of upload submissions, one 64x64 PNG fixture, unique idempotency keys and repeated identical content. Most successfully processed assets are therefore content duplicates. This measures small-file control-path behavior, not realistic large-document parser throughput. There is no warm-up phase. Requests are sent as fast as each client receives responses.

Configure BASE_URL, CONCURRENCY (1–100), DURATION_SECONDS (1–300), MODE, and optional TOKEN via environment variables. BASE_URL rejects embedded credentials/query parameters to avoid recording them in reports. In lifecycle mode a client polls once per second, up to 30 polls, before starting another upload. Slow requests can extend this wait; completion time can exceed submission duration while in-flight work drains. For example, in PowerShell:

```powershell
$env:CONCURRENCY='10'
$env:DURATION_SECONDS='60'
$env:MODE='lifecycle'
node benchmarks/load.mjs
```

Rate limiting remains active: the default 120 requests/minute can produce 429s. To measure capacity beyond that limit, explicitly set a suitable REQUESTS_PER_MINUTE for an isolated benchmark Compose deployment, recreate the app, and record that configuration alongside results. The driver never disables protection automatically. It counts all HTTP outcomes and returns a nonzero exit status for non-202 upload responses or network errors.

| MODE | Workload | Interpretation |
| --- | --- | --- |
| `upload` | Concurrent registrations with unique request keys | Creation/acceptance latency and rate; work can still be queued when the driver exits |
| `idempotency` | Seed once, then concurrently replay the same key/input | Verify stable ID/timestamp and replay header while measuring replay latency |
| `status` | Seed one asset, then repeatedly GET its status | Read latency/rate; no claim about parser throughput |
| `lifecycle` | Register, then poll each asset until terminal | Observed completion rate and end-to-end latency, including polling delay; a closed-loop measurement |

For POSIX shells, run each mode explicitly, for example `MODE=idempotency CONCURRENCY=10 DURATION_SECONDS=60 node benchmarks/load.mjs`. The PowerShell example above applies to all four modes by changing MODE. A seed request in idempotency/status mode is outside the timed interval. Accounts and seed assets remain in the dedicated benchmark environment.

Raw JSON is written to benchmarks/results (gitignored), or BENCHMARK_OUTPUT_DIR if explicitly supplied. Each report includes machine/runtime metadata, fixture size, concurrency, requested/actual duration, upload and status HTTP counts, all-response and successful-upload p50/p95/p99 latencies, status latency and lifecycle outcomes. Timing includes consuming response bodies. Storage is capped at the first 100,000 latency samples, which may bias long runs; sample counts are explicit. The driver reports accepted uploads, successful status reads and observed successful completions separately. Those rates are not interchangeable. FAILED/REJECTED outcomes, processing timeouts, HTTP errors, invalid responses and replay mismatches all produce a nonzero exit code.

`node --test benchmarks/load.test.mjs` checks the driver's verdicts against small local protocol fixtures. These tests do not run the platform and must never be presented as benchmark results. They are included in CI. Representative JPEG/PDF/file-size mixes, warm-up, repeated trials, saturation tests and an independent Kafka throughput measurement remain future benchmark work.

For comparisons, also record Docker CPU/memory allocation, image versions, application limits, worker concurrency, warm/cold state and concurrent machine workloads. Preserve raw results and repeat runs; correctness-test timing is not a substitute for this measurement.

`requestErrors` includes transport, timeout and response-parse failures. `acceptedUploadResponsesPerSecond` counts 202 responses, including replays in idempotency mode; it does not count newly created assets. Latencies in failed runs must not be presented as successful capacity results.
