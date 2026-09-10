# Senior engineering review

Review baseline: `a4eb32c099eb9f34eae5a5c72ffa2aa183f53862` on `main`. This was an in-place review and hardening pass, not a rewrite. Executed results are kept in [VERIFICATION.md](VERIFICATION.md).

## Concrete findings and changes

| Priority | Problem at baseline | Change and evidence |
| --- | --- | --- |
| High | Rate/body filters inspected raw request URI. A context prefix or encoded path could miss protection while MVC still routed to an auth endpoint. | Shared decoded application-path selection; context/encoded unit cases and a real HTTP encoded-login regression returning 429 |
| High | Worker start and result were separate transactions with no guard across the gap. Overlapping deliveries could spend all four starts before a result, and recovery could race an active delivery. | `ProcessingCoordinator` holds a transaction-scoped advisory lock across independent start/result transactions and recovery; deterministic gap test rejects six competitors without spending additional attempts |
| Medium | Any self-registered account could scrape Prometheus metrics. | Require `SCOPE_metrics.read`, absent from ordinary tokens; tests cover anonymous, ordinary-account and operator access and blocked actuator endpoints |
| Medium | JSON accepted duplicate keys/trailing root values. A chunked body could place a large tail after a valid object. | Strict duplicate/unknown/trailing-token rejection plus bounded stream; real HTTP oversized-tail regression and mass-assignment tests |
| Medium | Outbox success/retry metrics incremented before DB commit. A publish followed by rollback could be counted as durable success. | Counters run after commit; separate transaction-failure counter; actual broker acknowledgement followed by injected rollback, republication and single terminal effect |
| Medium | Important state invariants existed only in Java. | Additive V2 checks enforce retry bounds, duplicate linkage shape, metadata shape and event status; invalid direct SQL writes fail |
| Medium | Redis's shell command bypassed the official entrypoint's usual user-switch path. | Explicit `user: redis`; Compose schema validated. Runtime user/volume behavior remains unverified without Docker |
| Medium | Benchmark exit status ignored processing failures and status-read failures; it lacked replay/status modes and mixed header latency with response completion. | Failed processing/HTTP/replays now fail the run, four explicit modes, consumed-body latency and separate acceptance/read/completion rates; three Node protocol-verdict tests |
| Low | Token TTL comparison rounded minutes down; a sub-millisecond Redis window could become zero milliseconds. | Exact duration boundaries and focused configuration tests |
| Low | PNG/JPEG inspection performed an unused PDF marker scan. Documentation still described an uncommitted/unpublished repository. | PDF-only KMP scan, binary/boundary reference tests, updated README/verification, role evidence and cloud design |

## Architecture assessment

Controllers remain thin adapters: they translate authentication/request fields and response status/headers. DTOs exclude JPA internals. `AssetRegistration` combines JPA and JDBC under one transaction for the asset, job, initial event, outbox and optional idempotency record. Real rollback tests verify that all five tables remain empty and the uploaded object is removed on a known rollback.

Outbox publishers use `FOR UPDATE SKIP LOCKED`; competing relays can progress independently. Broker acknowledgements are not atomically coupled to PostgreSQL, so duplicate publication is expected after a crash. Producer idempotence does not change that end-to-end guarantee. Consumers validate schema and event/job/asset identity, then guard terminal effects using PostgreSQL. DLT recovery waits for acknowledgement and does not commit FAILED after a failed publish. No exactly-once delivery is claimed.

The worker coordinator deliberately favors a small locking protocol over leases and fencing tokens. It occupies two database connections per active delivery; the result transaction still holds an asset row lock during parsing. These are material costs, not cloud-scale performance claims. [ARCHITECTURE.md](ARCHITECTURE.md) records sizing, isolation, indexes and alternative lease-based coordination.

The content fingerprint primary key resolves concurrent same-owner uploads. Other owners get independent canonical content. Deleting a canonical asset removes its fingerprint but leaves historical duplicate references; objects are not physically shared. Request idempotency is separate and replays an immutable creation receipt even after processing or deletion.

There are no eager JPA graphs in list reads and no per-row metadata fetch. Existing indexes support owner/date/filter, cursor and due-outbox access. No speculative index or cloud SDK was added. Startup exercises the dependency wiring; no automated package-architecture rule or large-data query-plan benchmark is claimed.

## Test quality and review scope

New tests use real PostgreSQL transactions/constraints and an embedded Kafka broker where those guarantees matter. Mocks are limited to controlled timing/failure injection, isolated unit collaborators, and Redis in the native integration runner. The relay race uses latches; the worker race pauses after the actual start transaction commits; the publish/rollback test sends to the real broker. Driver protocol fixtures test verdicts, not application throughput.

The source review covered authentication, JWT claims and expiry, salted PBKDF2, ownership on all routes/SSE polls, filenames and storage paths, signature/size checks, image/PDF parsing, safe errors, SQL parameters, deserialization, CORS, actuator exposure, log/metric contents, container configuration and dependency scanning. `dummyHash` is intentional unknown-user password verification, not dummy production logic. CSRF is disabled for the stateless bearer-token API; no cookie authentication or wildcard CORS is configured. Loopback defaults are for host execution; Compose uses service DNS. Broad exception handlers at API/relay boundaries protect errors or retry external operations; they are not empty catches.

## Remaining weaknesses

- No parser process isolation, hard CPU deadline, malware scanning or tenant storage quotas. File/pixel/page limits are partial protections.
- Local shared storage prevents a credible multi-host deployment until a tested object-store adapter exists. Orphan cleanup and operational retention are manual.
- HMAC signing/verifying share authority; key rotation, token revocation, account lifecycle and operator-token provisioning remain outside the implementation.
- Live Redis and Linux container behavior are not verified on this host. CI configuration is not evidence that a particular GitHub Actions run passed.
- No measured throughput, capacity, failover, backup restore or cloud deployment. Kafka replication factor 1 is a local setting.
- Database connections/row locks remain occupied during parsing. SSE sends are polled serially, so a slow client can delay peers; capacity is process-local.
- Offset pagination and alternate-field sorts need representative query-plan/load testing. Backlog/age gauges, cross-service tracing and automated package-boundary checks remain missing.

The repository provides inspectable backend engineering evidence, while these gaps prevent a claim of production readiness.
