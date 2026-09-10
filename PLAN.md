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
- Workers use a transaction-scoped advisory lock across the separate start/result transactions and an asset row lock during inspection. PROCESSING remains observable and starts survive crashes; active delivery uses two database connections. Parser execution still needs external resource isolation.
- SSE reads durable processing events with Last-Event-ID; Redis is an atomic distributed rate limiter, not an authority for asset state.
- The local storage adapter uses a shared volume; API and worker roles can run in separate processes with access to that volume. A storage interface isolates business logic from the adapter.
- The algorithm component is streaming KMP matching for PDF end-marker validation, preserving linear time across arbitrary stream chunk boundaries.

## Initial environment

The initial implementation began on Windows with system Java 17 and Git. Portable Java 21 and Maven were installed in the parent workspace. Docker's daemon remains unavailable. The source was subsequently committed and pushed to the configured GitHub repository.

## Outcome

Implementation and a subsequent in-place hardening review are complete. [Current verification](docs/VERIFICATION.md) records executed test/security checks and Docker limitations; [review findings](docs/REVIEW.md) explain the changes and remaining gaps. The initial published commit is a4eb32c099eb9f34eae5a5c72ffa2aa183f53862. Git history records later changes; this planning document is not the source of current test counts.
