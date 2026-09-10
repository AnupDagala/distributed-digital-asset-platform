# Implementation plan

1. Establish Java 21 / Spring Boot, PostgreSQL migrations and domain invariants; compile.
2. Add JWT authentication, streaming storage/validation, owner-scoped asset APIs and database-backed request idempotency; test.
3. Add transactional outbox, Kafka delivery, idempotent workers and content fingerprints; test.
4. Add Redis rate limiting, replayable SSE, bounded retries and durable dead-letter handling; test.
5. Exercise unit, PostgreSQL, Kafka, Redis, REST, concurrency and security behavior.
6. Add container configuration, structured logging, metrics and public CI.
7. Document implemented behavior, prepare a reproducible benchmark, run final checks, inspect Git contents and commit. Publish only if GitHub authentication is available and the target name is unused.

## Architectural constraints

- PostgreSQL owns durable state. Outbox delivery and Kafka consumption are at least once.
- Idempotency keys are scoped to authenticated owners and persist independently of asset deletion.
- Object bytes are streamed to immutable UUID keys before the asset transaction. Failed transactions clean up best effort; a crash can leave an orphan, documented for operators.
- Workers serialize on the asset row while performing bounded file inspection. A separate committed start transition makes PROCESSING observable and permits crash recovery without a lease.
- SSE reads durable processing events with Last-Event-ID; Redis is an atomic distributed rate limiter, not an authority for asset state.
- The local storage adapter uses a shared volume; API and worker roles can run in separate processes with access to that volume. A storage interface isolates business logic from the adapter.
- The algorithm component is streaming KMP matching for PDF end-marker validation, preserving linear time across arbitrary stream chunk boundaries.

## Environment discovered

Windows; system Java 17 and Git available. Java 21 and Maven will be bootstrapped into the parent workspace. Docker and GitHub CLI are not on PATH. No application repository or existing files were present.

## Outcome

Implementation phases completed. The final clean build passed with 59 tests passed and 19 container-only cases skipped. The runtime dependency audit and source secret scan passed. Docker engine execution, benchmark measurement and GitHub publication remain unverified or blocked as detailed in docs/VERIFICATION.md. Git commit is pending the requested author identity.
