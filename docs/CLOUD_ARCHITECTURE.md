# Cloud deployment design — not deployed

This document is architectural only. No AWS resources, credentials, infrastructure deployment or cloud benchmark were created. The current implementation runs against local files and a Compose-oriented stack.

| Current component | Potential target | Required implementation/operational work |
| --- | --- | --- |
| `ObjectStorage` local adapter | S3 | Implement streaming upload/read, bounded temporary materialization and idempotent deletion; opaque keys; least-privilege access; encryption and private bucket policies; reconciliation for upload/DB partial failures |
| PostgreSQL / Flyway | RDS PostgreSQL | TLS, backups and restore drills, availability/failover configuration, separate migration/runtime identities, connection budgets per replica and V2 rollout validation |
| Redis Lua rate counters | ElastiCache Redis | TLS/authentication, network isolation, compatible Lua support, failover behavior and no-eviction policy; loss of rate counters is not loss of asset state |
| Local Kafka broker | MSK or another managed Kafka service | Configure authenticated TLS clients, provision topics/partitions and replication outside the app, retention/DLT policy, broker ACLs, consumer lag alerts and replay procedures |
| Application container | ECS or EKS | Separate API, relay and parser/worker roles as needed, health-based rollout, resource bounds, workload identities, graceful shutdown, secrets injection and centralized logs |

## Proposed flow and boundaries

An authenticated client reaches a TLS gateway and stateless API replicas. The API rate-limits through Redis, streams bytes through an S3 adapter and commits its receipt/job/outbox in PostgreSQL. Relay replicas share PostgreSQL and publish into Kafka. Worker replicas consume one group, read the same object service and commit processing results. SSE clients reconnect with durable cursors after a replica restart. HTTP/worker instances must share signing configuration and the database; sticky routing is not a correctness requirement for replay.

Keep database, cache and broker access private. The local Compose profile uses plaintext internal services and is not a cloud security template. `KafkaConfig` currently declares topics with replication factor 1 for local use; production topic provisioning and a profile that disables application topic creation are required before connecting to a managed cluster.

## Capacity and failure behavior

- Budget two database connections per active worker delivery, plus API, SSE and relay demand. Multiply that budget by replica count before sizing RDS. A larger consumer count alone is not a safe scaling strategy.
- API acceptance latency excludes processing, but still includes hashing, object transfer and a database transaction. The outbox separates broker availability from upload acceptance; it does not make disk or database capacity unlimited.
- An S3 PUT and database commit are not atomic. Preserve objects when commit outcome is unknown; reconcile only unreferenced objects older than a safe window. The existing cleanup outbox can drive S3 deletes, but that adapter and reconciliation job are not implemented.
- Isolate untrusted parsers with CPU, memory, temporary-disk and wall-clock enforcement. A Java transaction timeout cannot terminate a hostile PDF parser. Current API/worker flags do not by themselves provide that isolation.
- A broker outage accumulates outbox rows. A worker outage beyond Kafka retention requires an operator replay from stored payloads. DLT records can duplicate when acknowledgement precedes a failed database commit; redrive tooling must verify the original job/event correlation.
- Configure gateway/SSE timeouts and trusted proxy handling deliberately. The app currently ignores forwarded headers; deployers must not simply trust arbitrary X-Forwarded-For values for rate limits.

## Before any deployment claim

Implement and contract-test the S3 adapter, configure secret/key rotation and TLS/IAM, run the full container suite, measure representative workloads, verify database/object restore, rehearse broker/worker failures, add storage quotas and retention, and record actual deployment artifacts. None of those operational outcomes is established by this document.
