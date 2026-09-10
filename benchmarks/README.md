# Reproducible HTTP load test

This driver has not been run against the full Compose stack in the implementation environment. There are no measured throughput claims or committed result numbers.

Requirements: a running application with PostgreSQL, Kafka, Redis and object storage; Node.js 20+. Use a dedicated local benchmark environment with enough free disk. The test creates an account unless TOKEN is supplied, and leaves its accepted assets for later inspection. Do not use a production token or a database containing real user data.

From the repository root:

```sh
node benchmarks/load.mjs
```

Defaults: 5 concurrent clients, 30 seconds of upload submissions, one 64x64 PNG fixture, unique idempotency keys and repeated identical content. Most successfully processed assets are therefore content duplicates. This measures small-file control-path behavior, not realistic large-document parser throughput. There is no warm-up phase. Requests are sent as fast as each client receives responses.

Configure BASE_URL, CONCURRENCY (1–100), DURATION_SECONDS (1–300), MODE (upload or lifecycle), and optional TOKEN via environment variables. In lifecycle mode a client polls once per second until terminal status or its processing timeout before starting another upload. Completion time can exceed the submission duration while in-flight work drains. For example, in PowerShell:

```powershell
$env:CONCURRENCY='10'
$env:DURATION_SECONDS='60'
$env:MODE='lifecycle'
node benchmarks/load.mjs
```

Rate limiting remains active: the default 120 requests/minute can produce 429s. To measure capacity beyond that limit, explicitly set a suitable REQUESTS_PER_MINUTE for an isolated benchmark Compose deployment, recreate the app, and record that configuration alongside results. The driver never disables protection automatically. It counts all HTTP outcomes and returns a nonzero exit status for non-202 upload responses or network errors.

Raw JSON is written to benchmarks/results (gitignored). Each report includes OS/architecture, CPU model/logical cores, RAM, Node version, fixture size, concurrency, requested and actual duration, response counts by HTTP status, response rate and sampled p50/p95/p99 request latency. Lifecycle mode adds terminal outcomes and lifecycle latency. Latency storage is capped at the first 100,000 samples; results expose the sample count. HTTP response rate is not processing throughput.

For comparisons, also record Docker CPU/memory allocation, image versions, application limits, worker concurrency, warm/cold state and concurrent machine workloads. Preserve raw results and repeat runs; correctness-test timing is not a substitute for this measurement.
