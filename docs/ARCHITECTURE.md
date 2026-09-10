# Architecture and invariants

The application is a modular monolith that can run API and worker roles together or in separate processes. Kafka decouples request latency from parsing. The PostgreSQL schema, shared object volume and Kafka protocol are the contracts between replicas. This is a local, container-oriented deployment design; there is no cloud deployment.

## Durable state

`assets` is the aggregate root. `asset_processing_jobs` has one row per asset and records the one accepted processing event UUID. Workers verify both IDs before changing state. `processing_events` is an append-only status history exposed as DTOs through SSE. The numeric cursor is ordered per asset because all transitions lock its asset row. IDs can have gaps and are not a global commit-order promise.

`idempotency_records` has a primary key on `(owner_id, idempotency_key)`. A transaction-scoped PostgreSQL advisory lock serializes requests for the same owner/key, including the first insertion. Hash collisions can only serialize unrelated requests. The immutable request fingerprint covers sanitized filename, detected media type, byte count and SHA-256. Reuse with different input returns 409. The original 202 creation representation is replayed even if processing has since completed or the asset has been deleted. GET then reflects current state (404 for a tombstone).

`content_fingerprints` has a primary key on `(owner_id, sha256)`. Competing workers use INSERT ON CONFLICT and read the winning canonical asset. Only validated content enters this table. Deduplication does not share storage objects or reveal another owner's content. The first successful processing transaction wins; upload order is not a canonical ordering guarantee. Deleting a canonical asset removes its fingerprint; existing duplicate history remains valid historical information.

## Delivery

Asset, job, QUEUED status event and outbox row commit together. The relay takes one due row with FOR UPDATE SKIP LOCKED, publishes with broker acknowledgement, then marks it published. The DB transaction remains open during the bounded broker call. Multiple relays can work concurrently. A crash after acknowledgement and before commit republishes the same event. Kafka producer idempotence reduces transport duplicates within a producer session; database idempotence handles application-level redelivery.

Workers commit a PROCESSING transition, then lock the asset for the bounded inspection and final update transaction. This deliberately occupies a database connection during file I/O, trading some throughput for a simple, auditable concurrency protocol. A crash rolls back the final transaction; redelivery can process a previously started asset. A duplicate arriving after a terminal transition is a no-op. Kafka offsets are acknowledged only after handling succeeds. The job row also enforces a four-start budget across consumer restarts; exhaustion goes directly to dead-letter recovery.

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

## Trust boundaries

The HTTP API authenticates JWTs and derives the owner exclusively from the signed subject. Every asset query, status query and SSE poll checks ownership. Kafka is an internal trusted transport, but payload version/IDs and job correlation are still checked. Redis holds rate counters only; PostgreSQL is always authoritative for status. Infrastructure ports are private in Compose. Internet deployment needs TLS, authenticated Kafka, external secret management and an isolated parser runtime; see SECURITY.md.
