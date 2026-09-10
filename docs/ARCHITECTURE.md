# Architecture and invariants

The application is a modular monolith that can run API and worker roles together or in separate processes. Kafka decouples request latency from parsing. The PostgreSQL schema, shared object volume and Kafka protocol are the contracts between replicas. This is a local, container-oriented deployment design; there is no cloud deployment.

## Durable state

`assets` is the aggregate root. `asset_processing_jobs` has one row per asset and records the one accepted processing event UUID. Workers verify both IDs before changing state. `processing_events` is an append-only status history exposed as DTOs through SSE. The numeric cursor is ordered per asset because all transitions lock its asset row. IDs can have gaps and are not a global commit-order promise.

`idempotency_records` has a primary key on `(owner_id, idempotency_key)`. A transaction-scoped PostgreSQL advisory lock serializes requests for the same owner/key, including the first insertion. Hash collisions can only serialize unrelated requests. The immutable request fingerprint covers sanitized filename, detected media type, byte count and SHA-256. Reuse with different input returns 409. The original 202 creation representation is replayed even if processing has since completed or the asset has been deleted. GET then reflects current state (404 for a tombstone).

`content_fingerprints` has a primary key on `(owner_id, sha256)`. Competing workers use INSERT ON CONFLICT and read the winning canonical asset. Only validated content enters this table. Deduplication does not share storage objects or reveal another owner's content. The first successful processing transaction wins; upload order is not a canonical ordering guarantee. Deleting a canonical asset removes its fingerprint; existing duplicate history remains valid historical information.

## Delivery

Asset, job, QUEUED status event and outbox row commit together. The relay takes one due row with FOR UPDATE SKIP LOCKED, publishes with broker acknowledgement, then marks it published. The DB transaction remains open during the bounded broker call. Multiple relays can work concurrently. A crash after acknowledgement and before commit republishes the same event. Kafka producer idempotence reduces transport duplicates within a producer session; database idempotence handles application-level redelivery.

`ProcessingCoordinator` acquires `pg_try_advisory_xact_lock` over a namespaced 64-bit hash of the asset ID. It retains that transaction through both `ProcessingTransactions.start` and `process`, which each use `REQUIRES_NEW`. Thus an overlapping delivery cannot enter the gap between the committed start and final transaction. Contention throws `ProcessingBusyException` without incrementing attempts; the error handler retries and recovery refuses to quarantine a busy peer. A hash collision can delay unrelated assets but cannot mix their state. The lock is transaction-scoped, so it cannot leak into a pooled session after completion.

The start records PROCESSING and a durable attempt. The result transaction locks the asset row, inspects bytes, then writes metadata, fingerprint, terminal event and job completion together. A crash releases the coordinator lock and rolls back an unfinished result; redelivery can restart a previously started asset. At most four starts are permitted, including starts interrupted by process death. Recovery acquires the same coordinator lock, verifies correlation/current state, waits for DLT acknowledgement, then commits FAILED. Already terminal or deleted work needs no new DLT record. A DLT acknowledgement followed by DB failure can still produce duplicate DLT messages. Kafka offsets are acknowledged only after successful handling/recovery.

**Cost:** an active delivery can use two JDBC connections: one for the coordinator and one for the inner transaction. The default two consumer threads fit in the 16-connection pool with room for HTTP/relay work. Size pools for at least twice local worker concurrency plus API/relay headroom; raising consumer concurrency without doing so can cause pool starvation. Neither a Spring transaction timeout nor Kafka's poll interval kills a hostile parser. Parsing still holds a row lock and connection during I/O. Separate, resource-limited parser processes and measured capacity are required before scaling public workloads. A lease/fencing protocol could reduce connection occupancy, at greater state-machine complexity.

Relay outcome counters increment after DB commit. A crash after broker acknowledgement but before commit leaves the row pending and does not count it as a committed publication. A separate counter records relay transaction failures. Terminal worker counters likewise run after the result transaction commits. Delivery-duration timers include failed attempts and no-ops; they are not end-to-end asset latency.

Deletion locks the same asset row, writes a tombstone and queues object deletion through the outbox. Object deletion is idempotent and retried by the relay. Pending processing of deleted assets is a no-op. Tombstones and request receipts are retained; deletion is not a promise to erase all database metadata or backups.

## Object storage boundary

The local adapter creates opaque UUID keys with CREATE_NEW and never constructs paths from user filenames. Uploads stream with a fixed buffer, size enforcement and SHA-256. Bytes exist before the asset transaction. Ordinary transaction failures delete the newly written object; process death or a failed cleanup can leave an unreferenced object. Operators must reconcile these after quiescing uploads and preserving recent objects. Automatic garbage collection and cross-store atomicity are not claimed.

Workers use the storage interface to materialize a bounded temporary file for parsers. An S3 adapter can implement this contract without changing domain services. The current deployment requires the same volume on API/worker replicas and is not a multi-host object store.

## Queries and indexes

- `(owner_id, created_at DESC, id)` supports the default owner-scoped listing with stable UUID tie-breaks.
- `(owner_id, status, created_at DESC)` and `(owner_id, media_type, created_at DESC)` support actual API filters. All three exclude tombstones.
- `(asset_id, id)` supports per-asset event replay after a cursor.
- The partial outbox index covers due unpublished events. Published rows are retained for inspection; an operator retention policy is required for long-running installations.
- Fingerprint and idempotency primary keys enforce invariants as well as lookups. No global SHA index is needed because deduplication is owner-scoped.

Pagination uses bounded offsets and an allowlist of sort fields. This is suitable for a portfolio-scale dataset; cursor pagination is a future improvement for deep histories.

PostgreSQL READ COMMITTED is the assumed isolation level. Advisory locks serialize first insertion of a request key; unique constraints remain the final authority. `INSERT ... ON CONFLICT` waits for competing fingerprints before a subsequent statement reads the winner. Changing the isolation level requires retesting that protocol. V2 adds checks for retry bounds, duplicate linkage shape, positive image/PDF metadata and legal status events, without changing the published V1 checksum. No new index was added: existing access paths already have indexes, and alternate sort fields may still require a sort. EXPLAIN/load testing against a representative dataset is not claimed.

JPA entities store IDs rather than eager relationship graphs. List responses intentionally omit metadata; a detail request uses one asset lookup and one metadata lookup. There is no per-row metadata query in listing. `@Version` detects stale updates in addition to explicit worker/delete locks. Controllers map authenticated input and responses; services own transactions. Spring context startup exercises constructor wiring, but an automated package-dependency rule is not yet enforced.

Implementation references: [PostgreSQL transaction-scoped advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS), [Spring transaction propagation and pool sizing](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html), and [Spring Kafka recovery failures](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).

## Trust boundaries

The HTTP API authenticates JWTs and derives the owner exclusively from the signed subject. Every asset query, status query and SSE poll checks ownership. Kafka is an internal trusted transport, but payload version/IDs and job correlation are still checked. Redis holds rate counters only; PostgreSQL is always authoritative for status. Infrastructure ports are private in Compose. Internet deployment needs TLS, authenticated Kafka, external secret management and an isolated parser runtime; see SECURITY.md.
